package myfinances.infrastructure.loan.migration;

import com.myfinaces.db.SqliteDatabase;
import com.myfinaces.db.TransactionKind;
import com.myfinaces.db.TransactionRepository;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import myfinances.infrastructure.loan.jdbc.LoanPersistenceException;

/**
 * Backfill idempotente de transacciones financieras para préstamos canónicos.
 * <p>
 * Recorre los eventos financieros del journal canónico ({@code loan_journal_v1})
 * y genera la {@code Transaction} equivalente a la que hoy crea {@code LoansView}
 * para los eventos que quedaron sin movimiento financiero (préstamos creados
 * antes de la integración financiera de Sprint 6A).
 * <p>
 * No modifica el journal: {@code transaction_id} forma parte del fingerprint
 * canónico, por lo que reescribirlo invalidaría la cadena. Cuando el evento ya
 * referencia un {@code transaction_id}, la transacción se recrea con ese mismo
 * id; cuando no, se usa un UUID determinístico derivado del {@code event_id},
 * lo que hace la migración idempotente entre ejecuciones.
 */
public final class CanonicalLoanTransactionBackfill {

    private CanonicalLoanTransactionBackfill() {}

    public record BackfillReport(
        int loansScanned,
        int loansAlreadyLinked,
        int loansRepaired,
        int transactionsCreated,
        List<String> errors
    ) {}

    public static BackfillReport run(SqliteDatabase database) {
        TransactionRepository txRepo = new TransactionRepository(database);

        Map<String, List<JournalEvent>> eventsByLoan = readFinancialEvents(database);
        int alreadyLinked = 0;
        int repaired = 0;
        int created = 0;
        List<String> errors = new ArrayList<>();

        for (Map.Entry<String, List<JournalEvent>> entry : eventsByLoan.entrySet()) {
            String loanId = entry.getKey();
            boolean loanRepaired = false;
            boolean loanFailed = false;

            for (JournalEvent event : entry.getValue()) {
                try {
                    Resolution resolution = resolveEvent(database, txRepo, event);
                    if (resolution == Resolution.CREATED) {
                        loanRepaired = true;
                        created++;
                    }
                } catch (Exception ex) {
                    loanFailed = true;
                    errors.add(loanId + "/" + event.eventId + " (" + event.eventType + "): " + ex.getMessage());
                }
            }

            if (loanRepaired) {
                repaired++;
            } else if (!loanFailed) {
                alreadyLinked++;
            }
        }

        BackfillReport report = new BackfillReport(
            eventsByLoan.size(), alreadyLinked, repaired, created, List.copyOf(errors));
        System.out.println("[CanonicalLoanTransactionBackfill] loansScanned=" + report.loansScanned()
            + " alreadyLinked=" + report.loansAlreadyLinked()
            + " repaired=" + report.loansRepaired()
            + " transactionsCreated=" + report.transactionsCreated()
            + " errors=" + report.errors().size());
        for (String error : report.errors()) {
            System.out.println("[CanonicalLoanTransactionBackfill]   error " + error);
        }
        return report;
    }

    private enum Resolution { CREATED, ALREADY_OK }

    private static Resolution resolveEvent(
        SqliteDatabase database,
        TransactionRepository txRepo,
        JournalEvent event
    ) throws SQLException {
        String accountId = event.accountId != null && !event.accountId.isBlank()
            ? event.accountId
            : event.defaultAccountId;
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalStateException("evento sin cuenta asociada");
        }
        if (event.loanType == null || event.loanType.isBlank()) {
            throw new IllegalStateException("evento sin tipo de préstamo");
        }
        if (event.amountCents == null || event.amountCents == 0L) {
            return Resolution.ALREADY_OK;
        }

        boolean isLent = "LENT".equalsIgnoreCase(event.loanType);
        String kind = kindFor(event, isLent);

        String transactionId = event.transactionId;
        if (transactionId == null || transactionId.isBlank()) {
            // Un replay regenera los event_id del journal, por lo que el id
            // determinístico cambiaría aunque la transacción financiera ya
            // exista. Si hay una transacción equivalente sin referenciar en el
            // journal, el evento se considera ya cubierto y no se crea otra.
            if (findUnreferencedCandidate(database, event, kind, accountId) != null) {
                return Resolution.ALREADY_OK;
            }
            // Un ADJUSTMENT sintético ("synth:adjust:*") solo reconcilia el
            // principal con el estado remoto: no representa flujo de dinero.
            // Si el replay no enlazó transacción y no hay candidato local
            // equivalente, no existe movimiento financiero que materializar.
            if (isSyntheticAdjustment(event)) {
                return Resolution.ALREADY_OK;
            }
            transactionId = deterministicTransactionId(event.eventId);
        }

        if (txRepo.getForSyncByIdOrNull(event.ownerId, transactionId) != null) {
            return Resolution.ALREADY_OK;
        }

        String categoryId = "PAYMENT".equals(event.eventType)
            ? ensureRepaymentCategory(database, event.ownerId)
            : ensureLoanCategory(database, event.ownerId);

        txRepo.createWithId(
            transactionId,
            event.ownerId,
            accountId,
            categoryId,
            kind,
            Math.abs(event.amountCents),
            event.occurredAt,
            noteFor(event, isLent)
        );
        return Resolution.CREATED;
    }

    private static String findUnreferencedCandidate(
        SqliteDatabase database,
        JournalEvent event,
        String kind,
        String accountId
    ) throws SQLException {
        // CREATION se compara sin monto: un ajuste remoto queda "plegado" en el
        // principal del evento de creación tras el replay, mientras la
        // transacción histórica conserva el monto original.
        boolean ignoreAmount = "CREATION".equals(event.eventType);
        String sql =
            "SELECT t.id, t.amount_cents FROM transactions t " +
            "WHERE t.user_uid = ? AND t.kind = ? AND t.account_id = ? AND t.occurred_at_epoch_sec = ? " +
            "AND NOT EXISTS (SELECT 1 FROM loan_journal_v1 j WHERE j.owner_id = t.user_uid AND j.transaction_id = t.id) " +
            "ORDER BY t.created_at_epoch_sec, t.id LIMIT 8";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, event.ownerId);
            statement.setString(2, kind);
            statement.setString(3, accountId);
            statement.setLong(4, event.occurredAt);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    if (ignoreAmount || result.getLong("amount_cents") == Math.abs(event.amountCents)) {
                        return result.getString("id");
                    }
                }
                return null;
            }
        }
    }

    private static boolean isSyntheticAdjustment(JournalEvent event) {
        if (!"ADJUSTMENT".equals(event.eventType)) {
            return false;
        }
        String syntheticId = CanonicalLoanEventIds.deterministic(
            event.loanId, "ADJUSTMENT", "synth:adjust:" + event.loanId, event.occurredAt);
        return syntheticId.equals(event.eventId);
    }

    private static String deterministicTransactionId(String eventId) {
        return UUID.nameUUIDFromBytes(
            ("canonical-loan-tx:" + eventId).getBytes(StandardCharsets.UTF_8)
        ).toString();
    }

    private static String kindFor(JournalEvent event, boolean isLent) {
        return switch (event.eventType) {
            case "CREATION" -> isLent
                ? TransactionKind.LOAN_LENT_OUT.name()
                : TransactionKind.LOAN_BORROWED_IN.name();
            case "TOPUP" -> isLent
                ? TransactionKind.LOAN_LENT_TOPUP.name()
                : TransactionKind.LOAN_BORROWED_TOPUP.name();
            case "ADJUSTMENT" -> {
                boolean isIncrease = event.amountCents > 0;
                yield isLent
                    ? (isIncrease
                        ? TransactionKind.LOAN_LENT_CORRECTION_OUT.name()
                        : TransactionKind.LOAN_LENT_CORRECTION_IN.name())
                    : (isIncrease
                        ? TransactionKind.LOAN_BORROWED_CORRECTION_IN.name()
                        : TransactionKind.LOAN_BORROWED_CORRECTION_OUT.name());
            }
            case "PAYMENT" -> isLent
                ? TransactionKind.LOAN_REPAYMENT_PRINCIPAL_IN.name()
                : TransactionKind.LOAN_REPAYMENT_PRINCIPAL_OUT.name();
            default -> throw new IllegalStateException("evento financiero desconocido: " + event.eventType);
        };
    }

    private static String noteFor(JournalEvent event, boolean isLent) {
        String counterparty = event.counterparty != null ? event.counterparty : "";
        return switch (event.eventType) {
            case "CREATION" -> isLent
                ? "Préstamo otorgado a: " + counterparty
                : "Dinero recibido de: " + counterparty;
            case "TOPUP" -> isLent
                ? "Aumento de préstamo otorgado a: " + counterparty
                : "Aumento de deuda con: " + counterparty;
            case "ADJUSTMENT" -> isLent
                ? "Corrección de préstamo otorgado a: " + counterparty
                : "Corrección de deuda con: " + counterparty;
            case "PAYMENT" -> isLent
                ? "Pago recibido de: " + counterparty
                : "Pago realizado a: " + counterparty;
            default -> null;
        };
    }

    private static String ensureLoanCategory(SqliteDatabase database, String userUid) throws SQLException {
        String categoryId = "system-loan-" + userUid;
        if (!categoryExists(database, userUid, categoryId)) {
            insertCategory(database, userUid, categoryId, "Préstamos");
        }
        return categoryId;
    }

    private static String ensureRepaymentCategory(SqliteDatabase database, String userUid) throws SQLException {
        String categoryId = "sys_repayment_" + userUid;
        if (!categoryExists(database, userUid, categoryId)) {
            insertCategory(database, userUid, categoryId, "Devoluciones");
        }
        return categoryId;
    }

    private static void insertCategory(SqliteDatabase database, String userUid, String id, String name) throws SQLException {
        long now = System.currentTimeMillis() / 1000L;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT INTO categories (id, user_uid, name, parent_id, kind, created_at_epoch_sec, updated_at_epoch_sec) " +
                 "VALUES (?, ?, ?, NULL, NULL, ?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, userUid);
            statement.setString(3, name);
            statement.setLong(4, now);
            statement.setLong(5, now);
            statement.executeUpdate();
        }
    }

    private static boolean categoryExists(SqliteDatabase database, String userUid, String categoryId) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT 1 FROM categories WHERE user_uid = ? AND id = ? LIMIT 1")) {
            statement.setString(1, userUid);
            statement.setString(2, categoryId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static Map<String, List<JournalEvent>> readFinancialEvents(SqliteDatabase database) {
        String sql =
            "SELECT j.event_id, j.loan_id, j.owner_id, j.event_type, j.amount_cents, " +
            "       j.account_id, j.transaction_id, j.occurred_at, " +
            "       s.loan_type, s.counterparty_name, s.default_account_id " +
            "FROM loan_journal_v1 j " +
            "JOIN loan_snapshots_v1 s ON s.owner_id = j.owner_id AND s.loan_id = j.loan_id " +
            "WHERE j.event_type IN ('CREATION', 'TOPUP', 'ADJUSTMENT', 'PAYMENT') " +
            // Un evento con REVERSAL apuntándolo no debe recuperar su
            // transacción: la reversión elimina el artefacto financiero y
            // recrearla haría resucitar el pago en el siguiente replay.
            "AND NOT EXISTS (SELECT 1 FROM loan_journal_v1 r " +
            "WHERE r.owner_id = j.owner_id AND r.event_type = 'REVERSAL' " +
            "AND r.payload_target_event_id = j.event_id) " +
            "ORDER BY j.owner_id, j.loan_id, j.occurred_at, j.recorded_at, j.event_id";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            Map<String, List<JournalEvent>> byLoan = new LinkedHashMap<>();
            while (result.next()) {
                JournalEvent event = new JournalEvent(
                    result.getString("event_id"),
                    result.getString("loan_id"),
                    result.getString("owner_id"),
                    result.getString("event_type"),
                    nullableLong(result),
                    result.getString("account_id"),
                    result.getString("transaction_id"),
                    result.getLong("occurred_at"),
                    result.getString("loan_type"),
                    result.getString("counterparty_name"),
                    result.getString("default_account_id")
                );
                byLoan.computeIfAbsent(event.loanId, ignored -> new ArrayList<>()).add(event);
            }
            return byLoan;
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }

    private static Long nullableLong(ResultSet result) throws SQLException {
        Object value = result.getObject("amount_cents");
        return value == null ? null : ((Number) value).longValue();
    }

    private record JournalEvent(
        String eventId,
        String loanId,
        String ownerId,
        String eventType,
        Long amountCents,
        String accountId,
        String transactionId,
        long occurredAt,
        String loanType,
        String counterparty,
        String defaultAccountId
    ) {}
}
