package myfinances.infrastructure.loan.migration;

import com.myfinaces.db.SqliteDatabase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import myfinances.application.loan.LoanApplicationService;
import myfinances.domain.loan.aggregate.LoanCommandResult;
import myfinances.domain.loan.aggregate.Outcome;
import myfinances.domain.loan.commands.AddPrincipalCommand;
import myfinances.domain.loan.commands.AdjustPrincipalCommand;
import myfinances.domain.loan.commands.CloseLoanCommand;
import myfinances.domain.loan.commands.LoanCommand;
import myfinances.domain.loan.commands.LoanCommandEnvelope;
import myfinances.domain.loan.commands.LoanCommandType;
import myfinances.domain.loan.commands.RegisterPaymentCommand;
import myfinances.domain.loan.projection.LoanSummaryProjection;
import myfinances.infrastructure.loan.di.LoanApplicationServiceFactory;
import myfinances.infrastructure.loan.projection.jdbc.JdbcLoanProjectionQueryRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Reparación histórica dirigida: encola los eventos que la migración/reconciliación
 * omitieron, siempre a través de {@link LoanApplicationService#process} (ruta canónica).
 *
 * <p>Modos:
 * <ul>
 *   <li>{@code -Dmyfinances.liveDb=<path>} — copia la base a un archivo temporal y repara ahí (seguro).</li>
 *   <li>{@code -Dmyfinances.targetDb=<path> -Dmyfinances.confirm=YES} — repara la base indicada en sitio.</li>
 * </ul>
 */
class HistoricalLoanRepairLiveDbTest {
    private static final String REPAIR_TAG = "historical-loan-repair-v1";
    private static final String OWNER_QFYW = "qfywGydrMASWGgUevInHxMdWZKJ2";
    private static final String OWNER_VYV = "VYV8M6Uf";

    private enum Kind { PAYMENT, TOPUP, ADJUST, CLOSE }

    private record RepairEvent(Kind kind, long amountCents, String accountId, String txIdPrefix, String reason) {}
    private record LoanPlan(String ownerId, String loanIdPrefix, String label, List<RepairEvent> events) {}

    private static List<LoanPlan> plans() {
        List<LoanPlan> plans = new ArrayList<>();
        // ==== qfywGydr ====
        plans.add(new LoanPlan(OWNER_QFYW, "399c7362", "LUISA BORROWED 50000", List.of(
            new RepairEvent(Kind.PAYMENT, 5_000_000L, "551b36bf-b427-4d4e-a24b-677229ef0f84", "636bc015", null)
        )));
        plans.add(new LoanPlan(OWNER_QFYW, "8b4d849a", "LUISA FERNANDEZ LENT 19000", List.of(
            new RepairEvent(Kind.PAYMENT, 1_900_000L, "683692ce-0f4b-4952-b34a-38d57dfa9f3a", "4041473d", null)
        )));
        plans.add(new LoanPlan(OWNER_QFYW, "b5608aa6", "Luisa LENT 28000", List.of(
            new RepairEvent(Kind.TOPUP, 600_000L, null, "26ccd1a4", null),
            new RepairEvent(Kind.ADJUST, -100_000L, null, "f2bb2a6c", "Corrección de préstamo otorgado a: Luisa"),
            new RepairEvent(Kind.TOPUP, 500_000L, null, "d186a158", null),
            new RepairEvent(Kind.PAYMENT, 1_800_000L, "551b36bf-b427-4d4e-a24b-677229ef0f84", "4d012bde", null)
        )));
        plans.add(new LoanPlan(OWNER_QFYW, "8a053d97", "William Castaño BORROWED 380000", List.of(
            new RepairEvent(Kind.ADJUST, -2_640_000L, null, null, "Cierre manual — saldo condonado")
        )));
        plans.add(new LoanPlan(OWNER_QFYW, "eba0a242", "Luisa Fernández LENT 388090", List.of(
            new RepairEvent(Kind.ADJUST, -3_500_000L, null, null, "Aumento anulado — ajuste histórico")
        )));
        // ==== VYV8M6Uf ====
        plans.add(new LoanPlan(OWNER_VYV, "29613932", "PEDRO LENT 500000", List.of(
            new RepairEvent(Kind.ADJUST, -10_000_000L, null, "ccf1f8c5", "Corrección de préstamo otorgado a: PEDRO"),
            new RepairEvent(Kind.ADJUST, -39_950_000L, null, "77ccfc1b", "Corrección de préstamo otorgado a: PEDRO")
        )));
        plans.add(new LoanPlan(OWNER_VYV, "39a2f695", "CARLOS FARFAN LENT 250000", List.of(
            new RepairEvent(Kind.ADJUST, -5_000_000L, null, null, "Aumento anulado — ajuste histórico"),
            new RepairEvent(Kind.CLOSE, 0L, null, null, "Cierre histórico")
        )));
        plans.add(new LoanPlan(OWNER_VYV, "d16f18ab", "EMPRESA BORROWED 900000", List.of(
            new RepairEvent(Kind.ADJUST, -10_000_000L, null, "66195526", "Corrección de deuda con: EMPRESA")
        )));
        plans.add(new LoanPlan(OWNER_VYV, "28740940", "Luisa Fernandez LENT 100000", List.of(
            new RepairEvent(Kind.ADJUST, -8_000_000L, null, null, "Cierre manual — saldo condonado")
        )));
        plans.add(new LoanPlan(OWNER_VYV, "952c9718", "Juan Pérez LENT 100000", List.of(
            new RepairEvent(Kind.ADJUST, -2_000_000L, null, null, "Cierre manual — saldo condonado")
        )));
        // Préstamos cerrados sin ningún pago registrado: el principal no puede llegar a 0
        // vía ajuste (NON_POSITIVE_RESULTING_PRINCIPAL) -> pago de cierre explícito.
        for (String[] spec : new String[][] {
            {"00185a73", "MARIA LENT 80000", "8000000"},
            {"3e2cbe67", "A LUISA LENT 50000", "5000000"},
            {"68f16df1", "A Luisa BORROWED 200000", "20000000"},
            {"94cdb82c", "JUAN PUEBLO LENT 50000", "5000000"},
            {"9bb4596c", "JUAN LENT 50000", "5000000"},
            {"c356934b", "JUAN LENT 50000", "5000000"},
            {"cac1ae23", "Alberto LENT 15000", "1500000"},
            {"d922ce5a", "A PEDRO LENT 300000", "30000000"},
        }) {
            plans.add(new LoanPlan(OWNER_VYV, spec[0], spec[1], List.of(
                new RepairEvent(Kind.PAYMENT, Long.parseLong(spec[2]), null, null,
                    "Cierre manual — liquidación sin movimiento registrado")
            )));
        }
        return List.copyOf(plans);
    }

    @Test
    void repairHistoricalLoans() throws Exception {
        String sourceDb = System.getProperty("myfinances.liveDb");
        String targetDb = System.getProperty("myfinances.targetDb");
        boolean direct = "YES".equals(System.getProperty("myfinances.confirm"));
        Assumptions.assumeTrue(sourceDb != null || (direct && targetDb != null),
            "Set -Dmyfinances.liveDb=<db> (copy mode) or -Dmyfinances.targetDb=<db> -Dmyfinances.confirm=YES");

        Path dbPath;
        if (direct) {
            dbPath = Path.of(targetDb);
            System.out.println("*** MODO DIRECTO — reparando en sitio: " + dbPath);
        } else {
            Path copy = Files.createTempFile("myfinances-repair", ".db");
            Files.copy(Path.of(sourceDb), copy, StandardCopyOption.REPLACE_EXISTING);
            dbPath = copy;
            System.out.println("Modo copia — reparando: " + dbPath);
        }

        SqliteDatabase database = new SqliteDatabase(dbPath);
        LoanApplicationService service = new LoanApplicationServiceFactory(database).loanApplicationService();
        JdbcLoanProjectionQueryRepository query = new JdbcLoanProjectionQueryRepository(database);

        int applied = 0, replayed = 0, failed = 0;
        for (LoanPlan plan : plans()) {
            String loanId = resolveLoanId(database, plan.ownerId(), plan.loanIdPrefix());
            if (loanId == null) {
                System.out.println("SKIP " + plan.label() + " — préstamo no encontrado (" + plan.loanIdPrefix() + ")");
                failed++;
                continue;
            }
            LoanSummaryProjection before = query.getSummaryProjection(resolveOwner(database, plan.ownerId()), loanId);
            String ownerId = before != null ? before.ownerId() : resolveOwner(database, plan.ownerId());
            long creationAt = creationOccurredAt(database, ownerId, loanId);
            long updatedAt = legacyUpdatedAt(database, ownerId, loanId);

            System.out.println("== " + plan.label() + " [" + loanId + "] before pending=" +
                (before == null ? "?" : before.pendingCents()) + " status=" + (before == null ? "?" : before.status()));

            String fingerprint = before == null ? null : before.journalFingerprint();
            for (RepairEvent event : plan.events()) {
                String txId = event.txIdPrefix() == null ? null : resolveTxId(database, event.txIdPrefix());
                String accountId = event.accountId() != null ? event.accountId()
                    : (txId != null ? txAccountId(database, txId) : null);
                RepairEvent resolved = new RepairEvent(event.kind(), event.amountCents(), accountId, event.txIdPrefix(), event.reason());
                long occurredAt = resolveOccurredAt(database, event, txId, creationAt, updatedAt);
                LoanCommand command = buildCommand(loanId, ownerId, resolved, txId, occurredAt, fingerprint);
                try {
                    LoanCommandResult result = service.process(command);
                    if (result.outcome() == Outcome.APPLIED) {
                        applied++;
                        fingerprint = result.currentSnapshot().journalFingerprint();
                        System.out.println("   APPLIED  " + event.kind() + " " + event.amountCents() +
                            " -> pending=" + result.currentSnapshot().pendingCents());
                    } else if (result.outcome() == Outcome.REPLAYED) {
                        replayed++;
                        if (result.currentSnapshot() != null) {
                            fingerprint = result.currentSnapshot().journalFingerprint();
                        }
                        System.out.println("   REPLAYED " + event.kind() + " " + event.amountCents());
                    } else {
                        failed++;
                        System.out.println("   FAILED   " + event.kind() + " " + event.amountCents() + " outcome=" + result.outcome());
                    }
                } catch (Exception ex) {
                    failed++;
                    System.out.println("   ERROR    " + event.kind() + " " + event.amountCents() + " -> " + ex.getMessage());
                }
            }
            LoanSummaryProjection after = query.getSummaryProjection(ownerId, loanId);
            System.out.println("   => after pending=" + (after == null ? "?" : after.pendingCents()) +
                " status=" + (after == null ? "?" : after.status()) +
                " principal=" + (after == null ? "?" : after.principalCents()) +
                " paid=" + (after == null ? "?" : after.totalPaidCents()));
        }
        System.out.println("=== RESUMEN applied=" + applied + " replayed=" + replayed + " failed=" + failed);
    }

    private static LoanCommand buildCommand(String loanId, String ownerId, RepairEvent event,
            String txId, long occurredAt, String fingerprint) {
        LoanCommandType type = switch (event.kind()) {
            case PAYMENT -> LoanCommandType.REGISTER_PAYMENT;
            case TOPUP -> LoanCommandType.ADD_PRINCIPAL;
            case ADJUST -> LoanCommandType.ADJUST_PRINCIPAL;
            case CLOSE -> LoanCommandType.CLOSE_LOAN;
        };
        LoanCommandEnvelope envelope = new LoanCommandEnvelope(
            type,
            deterministicOperationId(ownerId, loanId, event, txId),
            loanId,
            ownerId,
            fingerprint,
            occurredAt,
            ownerId,
            ownerId
        );
        return switch (event.kind()) {
            case PAYMENT -> new RegisterPaymentCommand(envelope, event.amountCents(), event.accountId(), txId, event.reason());
            case TOPUP -> new AddPrincipalCommand(envelope, event.amountCents(), event.accountId(), txId, event.reason());
            case ADJUST -> new AdjustPrincipalCommand(envelope, event.amountCents(), event.reason(), event.accountId(), txId, event.reason());
            case CLOSE -> new CloseLoanCommand(envelope, event.reason(), event.reason());
        };
    }

    private static long resolveOccurredAt(SqliteDatabase database, RepairEvent event, String txId,
            long creationAt, long updatedAt) {
        long base;
        if (txId != null) {
            base = txOccurredAt(database, txId);
        } else {
            base = updatedAt > 0 ? updatedAt : System.currentTimeMillis() / 1000L;
        }
        // Un evento no puede ordenar antes del CREATION en el journal canónico.
        return Math.max(base, creationAt + 1L);
    }

    private static String deterministicOperationId(String ownerId, String loanId, RepairEvent event, String txId) {
        String material = ownerId + '|' + loanId + '|' + event.kind() + '|' + event.amountCents()
            + '|' + (txId == null ? "-" : txId) + '|' + (event.reason() == null ? "-" : event.reason()) + '|' + REPAIR_TAG;
        UUID base = UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
        long msb = (base.getMostSignificantBits() & 0xffffffffffff0fffl) | 0x0000000000004000L;
        long lsb = (base.getLeastSignificantBits() & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(msb, lsb).toString();
    }

    private static String resolveOwner(SqliteDatabase database, String ownerPrefix) {
        try (Connection c = database.openConnection();
             PreparedStatement ps = c.prepareStatement("SELECT DISTINCT user_uid FROM loans WHERE user_uid LIKE ? LIMIT 1")) {
            ps.setString(1, ownerPrefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : ownerPrefix;
            }
        } catch (Exception ex) {
            return ownerPrefix;
        }
    }

    private static String resolveLoanId(SqliteDatabase database, String ownerPrefix, String loanIdPrefix) {
        try (Connection c = database.openConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id FROM loans WHERE user_uid LIKE ? AND id LIKE ? LIMIT 1")) {
            ps.setString(1, ownerPrefix + "%");
            ps.setString(2, loanIdPrefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception ex) {
            return null;
        }
    }

    private static String resolveTxId(SqliteDatabase database, String txIdPrefix) {
        try (Connection c = database.openConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id FROM transactions WHERE id LIKE ? LIMIT 1")) {
            ps.setString(1, txIdPrefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception ex) {
            return null;
        }
    }

    private static long creationOccurredAt(SqliteDatabase database, String ownerId, String loanId) {
        try (Connection c = database.openConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT MIN(occurred_at) FROM loan_journal_v1 WHERE owner_id = ? AND loan_id = ? AND event_type = 'CREATION'")) {
            ps.setString(1, ownerId);
            ps.setString(2, loanId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (Exception ex) {
            return 0L;
        }
    }

    private static long legacyUpdatedAt(SqliteDatabase database, String ownerId, String loanId) {
        try (Connection c = database.openConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT updated_at_epoch_sec FROM loans WHERE user_uid = ? AND id = ?")) {
            ps.setString(1, ownerId);
            ps.setString(2, loanId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (Exception ex) {
            return 0L;
        }
    }

    private static String txAccountId(SqliteDatabase database, String txId) {
        try (Connection c = database.openConnection();
             PreparedStatement ps = c.prepareStatement("SELECT account_id FROM transactions WHERE id = ?")) {
            ps.setString(1, txId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception ex) {
            return null;
        }
    }

    private static long txOccurredAt(SqliteDatabase database, String txId) {
        try (Connection c = database.openConnection();
             PreparedStatement ps = c.prepareStatement("SELECT occurred_at_epoch_sec FROM transactions WHERE id = ?")) {
            ps.setString(1, txId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (Exception ex) {
            return 0L;
        }
    }
}
