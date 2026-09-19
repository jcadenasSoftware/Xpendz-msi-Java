package myfinances.infrastructure.loan.migration;

import com.myfinaces.auth.AuthSession;
import com.myfinaces.db.SqliteDatabase;
import com.myfinaces.db.TransactionKind;
import com.myfinaces.db.TransactionRepository;
import com.myfinaces.sync.FirestoreSyncService;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import myfinances.infrastructure.loan.jdbc.LoanPersistenceException;

/**
 * Elimina transacciones fantasma materializadas por {@link CanonicalLoanTransactionBackfill}
 * a partir de ADJUSTMENT sintéticos emitidos por {@code HistoricalLoanReplayTool}
 * antes del Sprint 7J.2.
 * <p>
 * Un ADJUSTMENT sintético ({@code event_id == deterministic(loanId, "ADJUSTMENT",
 * "synth:adjust:"+loanId, occurredAt)}) solo reconcilia el principal con el
 * estado remoto: no representa flujo de dinero. Desde 7J.2 el backfill ya no lo
 * materializa; este reconciliador limpia los residuos históricos.
 * <p>
 * La única fuente de verdad es {@code loan_journal_v1}. El candidato fantasma
 * se localiza por su id determinístico {@code nameUUIDFromBytes("canonical-loan-tx:"+eventId)},
 * el mismo esquema que usa el backfill. Solo se elimina si además la fila no está
 * referenciada por ningún otro evento del journal ni enlazada por
 * {@code loan_payments}/{@code loan_movements}.
 * <p>
 * Idempotente y con reconvergencia: si el borrado remoto falla (offline), el
 * próximo pull resucita la fila local y la siguiente ejecución reintenta ambos
 * borrados. Se ejecuta en cada sincronización y solo actúa sobre residuos.
 */
public final class PhantomLoanTransactionReconciler {

    private PhantomLoanTransactionReconciler() {}

    public record CleanupReport(
        int syntheticAdjustmentsScanned,
        int phantomsDeleted,
        List<String> errors
    ) {}

    private record SyntheticAdjustment(
        String eventId,
        String loanId,
        String ownerId,
        Long amountCents,
        String accountId,
        long occurredAt,
        String loanType,
        String defaultAccountId
    ) {}

    public static CleanupReport reconcile(
        SqliteDatabase database,
        FirestoreSyncService sync,
        AuthSession session
    ) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(sync, "sync");
        Objects.requireNonNull(session, "session");

        TransactionRepository txRepo = new TransactionRepository(database);
        List<SyntheticAdjustment> events = readSyntheticAdjustments(database, session.uid());
        List<String> errors = new ArrayList<>();
        int deleted = 0;

        for (SyntheticAdjustment event : events) {
            String phantomTxId = deterministicTransactionId(event.eventId());
            try {
                if (!isPhantom(database, txRepo, event, phantomTxId)) {
                    continue;
                }
                txRepo.deleteFailedLoanTransaction(event.ownerId(), phantomTxId);
                deleted++;
                try {
                    sync.deleteTransaction(session, phantomTxId);
                } catch (Exception e) {
                    // El doc remoto puede no existir; si el borrado falla, el
                    // próximo pull resucita la fila y se reintenta en el sync siguiente.
                    System.out.println("[PhantomTxReconciler] remote delete failed " + phantomTxId + ": " + e.getMessage());
                }
            } catch (Exception ex) {
                errors.add(event.loanId() + "/" + event.eventId() + ": " + ex.getMessage());
            }
        }

        CleanupReport report = new CleanupReport(events.size(), deleted, List.copyOf(errors));
        if (deleted > 0 || !errors.isEmpty()) {
            System.out.println("[PhantomTxReconciler] scanned=" + report.syntheticAdjustmentsScanned()
                + " phantomsDeleted=" + report.phantomsDeleted()
                + " errors=" + report.errors().size());
        }
        return report;
    }

    private static boolean isPhantom(
        SqliteDatabase database,
        TransactionRepository txRepo,
        SyntheticAdjustment event,
        String phantomTxId
    ) throws SQLException {
        TransactionRepository.TransactionSyncRow tx = txRepo.getForSyncByIdOrNull(event.ownerId(), phantomTxId);
        if (tx == null) {
            return false;
        }
        String expectedAccountId = event.accountId() != null && !event.accountId().isBlank()
            ? event.accountId()
            : event.defaultAccountId();
        boolean isLent = "LENT".equalsIgnoreCase(event.loanType());
        String expectedKind = expectedCorrectionKind(event, isLent);
        if (!expectedKind.equals(tx.kind())
            || !Objects.equals(expectedAccountId, tx.accountId())
            || tx.amountCents() != Math.abs(event.amountCents() == null ? 0L : event.amountCents())
            || tx.occurredAtEpochSec() != event.occurredAt()) {
            return false;
        }
        return !isReferencedElsewhere(database, event.ownerId(), phantomTxId);
    }

    private static String expectedCorrectionKind(SyntheticAdjustment event, boolean isLent) {
        boolean isIncrease = event.amountCents() != null && event.amountCents() > 0;
        return isLent
            ? (isIncrease ? TransactionKind.LOAN_LENT_CORRECTION_OUT.name() : TransactionKind.LOAN_LENT_CORRECTION_IN.name())
            : (isIncrease ? TransactionKind.LOAN_BORROWED_CORRECTION_IN.name() : TransactionKind.LOAN_BORROWED_CORRECTION_OUT.name());
    }

    private static boolean isReferencedElsewhere(SqliteDatabase database, String ownerId, String txId) throws SQLException {
        boolean hasMovements = tableExists(database, "loan_movements");
        String sql =
            "SELECT EXISTS(SELECT 1 FROM loan_journal_v1 WHERE owner_id = ? AND transaction_id = ?) " +
            "OR EXISTS(SELECT 1 FROM loan_payments WHERE user_uid = ? AND linked_transaction_id = ?) " +
            (hasMovements
                ? "OR EXISTS(SELECT 1 FROM loan_movements WHERE user_uid = ? AND linked_transaction_id = ?)"
                : "OR 0");
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId);
            statement.setString(2, txId);
            statement.setString(3, ownerId);
            statement.setString(4, txId);
            if (hasMovements) {
                statement.setString(5, ownerId);
                statement.setString(6, txId);
            }
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) != 0;
            }
        }
    }

    private static boolean tableExists(SqliteDatabase database, String name) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static List<SyntheticAdjustment> readSyntheticAdjustments(SqliteDatabase database, String ownerId) {
        String sql =
            "SELECT j.event_id, j.loan_id, j.owner_id, j.amount_cents, j.account_id, j.occurred_at, " +
            "       s.loan_type, s.default_account_id " +
            "FROM loan_journal_v1 j " +
            "JOIN loan_snapshots_v1 s ON s.owner_id = j.owner_id AND s.loan_id = j.loan_id " +
            "WHERE j.owner_id = ? AND j.event_type = 'ADJUSTMENT' AND j.transaction_id IS NULL " +
            "ORDER BY j.loan_id, j.occurred_at";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId);
            List<SyntheticAdjustment> out = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String loanId = rs.getString("loan_id");
                    long occurredAt = rs.getLong("occurred_at");
                    String eventId = rs.getString("event_id");
                    if (!CanonicalLoanEventIds.deterministic(
                            loanId, "ADJUSTMENT", "synth:adjust:" + loanId, occurredAt).equals(eventId)) {
                        continue;
                    }
                    Object amount = rs.getObject("amount_cents");
                    out.add(new SyntheticAdjustment(
                        eventId,
                        loanId,
                        rs.getString("owner_id"),
                        amount == null ? null : ((Number) amount).longValue(),
                        rs.getString("account_id"),
                        occurredAt,
                        rs.getString("loan_type"),
                        rs.getString("default_account_id")
                    ));
                }
            }
            return out;
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }

    private static String deterministicTransactionId(String eventId) {
        return UUID.nameUUIDFromBytes(
            ("canonical-loan-tx:" + eventId).getBytes(StandardCharsets.UTF_8)
        ).toString();
    }
}
