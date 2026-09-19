package myfinances.infrastructure.loan.migration;

import com.myfinaces.auth.AuthSession;
import com.myfinaces.db.SqliteDatabase;
import com.myfinaces.db.TransactionKind;
import com.myfinaces.db.TransactionRepository;
import com.myfinaces.sync.FirestoreSyncService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import myfinances.infrastructure.loan.jdbc.LoanPersistenceException;

/**
 * Elimina duplicados canónicos históricos: transacciones materializadas por
 * {@link CanonicalLoanTransactionBackfill} para eventos cuya transacción legacy
 * original ya existía pero no estaba enlazada en el journal (defecto corregido
 * en Sprint 7J.5).
 * <p>
 * Una transacción solo se elimina cuando TODAS estas condiciones se cumplen:
 * <ul>
 *   <li>el evento del journal ya referencia otra transacción (la legacy);</li>
 *   <li>existe una segunda transacción con id determinístico
 *       {@code canonical-loan-tx:{eventId}};</li>
 *   <li>esa segunda transacción reproduce exactamente la firma del evento
 *       (kind, cuenta, monto absoluto y ocurrido);</li>
 *   <li>no está referenciada por {@code loan_journal_v1},
 *       {@code loan_payments} ni {@code loan_movements}.</li>
 * </ul>
 * Ante cualquier duda la transacción se conserva. El borrado local usa el
 * mecanismo canónico del repositorio y el remoto {@code sync.deleteTransaction};
 * si el remoto falla, el próximo pull resucita la fila y el siguiente sync
 * reintenta (reconvergencia natural, mismo patrón que
 * {@link PhantomLoanTransactionReconciler}).
 */
public final class CanonicalLoanDuplicateReconciler {

    private CanonicalLoanDuplicateReconciler() {}

    public record DedupReport(
        int linkedEventsScanned,
        int duplicatesFound,
        int duplicatesDeleted,
        int conserved,
        List<String> errors
    ) {}

    private record LinkedEvent(
        String eventId,
        String loanId,
        String ownerId,
        String eventType,
        Long amountCents,
        String accountId,
        String transactionId,
        long occurredAt,
        String loanType,
        String defaultAccountId,
        String counterpartyName
    ) {}

    public static DedupReport reconcile(
        SqliteDatabase database,
        FirestoreSyncService sync,
        AuthSession session
    ) {
        return reconcile(database, sync, session, true);
    }

    /**
     * @param deleteRemote cuando es {@code false} solo se elimina localmente;
     *                     útil para reparar una copia de la base sin tocar
     *                     Firestore (dry-run controlado).
     */
    public static DedupReport reconcile(
        SqliteDatabase database,
        FirestoreSyncService sync,
        AuthSession session,
        boolean deleteRemote
    ) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(sync, "sync");
        Objects.requireNonNull(session, "session");

        TransactionRepository txRepo = new TransactionRepository(database);
        List<LinkedEvent> events = readLinkedEvents(database, session.uid());
        List<String> errors = new ArrayList<>();
        int found = 0;
        int deleted = 0;
        int conserved = 0;

        for (LinkedEvent event : events) {
            String canonicalTxId = CanonicalLoanEventIds.deterministicTransactionId(event.eventId());
            // El journal ya apunta a la materialización propia del evento:
            // no hay duplicado que limpiar.
            if (canonicalTxId.equals(event.transactionId())) {
                continue;
            }
            try {
                TransactionRepository.TransactionSyncRow canonical =
                    txRepo.getForSyncByIdOrNull(event.ownerId(), canonicalTxId);
                if (canonical == null) {
                    continue;
                }
                found++;
                if (!isProvenDuplicate(database, txRepo, event, canonical)) {
                    conserved++;
                    continue;
                }
                txRepo.deleteFailedLoanTransaction(event.ownerId(), canonicalTxId);
                deleted++;
                try {
                    if (deleteRemote) {
                        sync.deleteTransaction(session, canonicalTxId);
                    }
                } catch (Exception e) {
                    // El doc remoto puede no existir o el borrado fallar offline:
                    // el próximo pull resucita la fila y el siguiente sync reintenta.
                    System.out.println("[CanonicalDupReconciler] remote delete failed "
                        + canonicalTxId + ": " + e.getMessage());
                }
            } catch (Exception ex) {
                errors.add(event.loanId() + "/" + event.eventId() + ": " + ex.getMessage());
            }
        }

        DedupReport report = new DedupReport(events.size(), found, deleted, conserved, List.copyOf(errors));
        if (found > 0 || !errors.isEmpty()) {
            System.out.println("[CanonicalDupReconciler] linkedEvents=" + report.linkedEventsScanned()
                + " duplicatesFound=" + report.duplicatesFound()
                + " deleted=" + report.duplicatesDeleted()
                + " conserved=" + report.conserved()
                + " errors=" + report.errors().size());
        }
        for (String error : report.errors()) {
            System.out.println("[CanonicalDupReconciler]   error " + error);
        }
        return report;
    }

    /**
     * Demuestra que la transacción canónica es un duplicado: la tx enlazada en
     * el journal existe y coincide con la firma del evento, y la candidata
     * replica exactamente esa misma firma sin estar referenciada en ningún lado.
     */
    private static boolean isProvenDuplicate(
        SqliteDatabase database,
        TransactionRepository txRepo,
        LinkedEvent event,
        TransactionRepository.TransactionSyncRow canonical
    ) throws SQLException {
        String expectedAccountId = event.accountId() != null && !event.accountId().isBlank()
            ? event.accountId()
            : event.defaultAccountId();
        boolean isLent = "LENT".equalsIgnoreCase(event.loanType());
        String expectedKind = kindFor(event, isLent);
        if (expectedKind == null || expectedAccountId == null || event.amountCents() == null) {
            return false;
        }
        long expectedAmount = Math.abs(event.amountCents());

        // El duplicado debe reproducir la firma completa del evento.
        if (!expectedKind.equals(canonical.kind())
            || !Objects.equals(expectedAccountId, canonical.accountId())
            || canonical.amountCents() != expectedAmount
            || canonical.occurredAtEpochSec() != event.occurredAt()) {
            return false;
        }

        // El enlace del journal debe apuntar a una transacción legacy real que
        // exista y represente el mismo flujo (mismo kind, cuenta y ocurrido;
        // para CREATION el monto puede diferir por ajustes plegados).
        TransactionRepository.TransactionSyncRow legacy =
            txRepo.getForSyncByIdOrNull(event.ownerId(), event.transactionId());
        if (legacy == null) {
            return false;
        }
        boolean legacyMatchesSignature;
        if (expectedKind.equals(legacy.kind())) {
            legacyMatchesSignature = Objects.equals(expectedAccountId, legacy.accountId())
                && legacy.occurredAtEpochSec() == event.occurredAt()
                && ("CREATION".equals(event.eventType()) || legacy.amountCents() == expectedAmount);
        } else {
            // Formato legacy antiguo (solo CREATION): el desembolso quedó como
            // EXPENSE/INCOME en la categoría de sistema con nota
            // "<kind esperado>: <contraparte>"; el occurred_at podía quedar
            // retrasado a medianoche, por lo que también se acepta
            // created_at == occurred_at del evento.
            legacyMatchesSignature = isLegacyFormatOriginal(event, isLent, expectedKind,
                expectedAccountId, expectedAmount, legacy);
        }
        if (!legacyMatchesSignature) {
            return false;
        }

        return !isReferencedElsewhere(database, event.ownerId(), canonical.id());
    }

    private static boolean isLegacyFormatOriginal(
        LinkedEvent event,
        boolean isLent,
        String expectedKind,
        String expectedAccountId,
        long expectedAmount,
        TransactionRepository.TransactionSyncRow legacy
    ) {
        if (!"CREATION".equals(event.eventType())
            || event.counterpartyName() == null || event.counterpartyName().isBlank()) {
            return false;
        }
        String legacyKind = isLent ? "EXPENSE" : "INCOME";
        return legacyKind.equals(legacy.kind())
            && ("system-loan-" + event.ownerId()).equals(legacy.categoryId())
            && Objects.equals(expectedAccountId, legacy.accountId())
            && legacy.amountCents() == expectedAmount
            && Objects.equals(legacy.note(), expectedKind + ": " + event.counterpartyName())
            && (legacy.occurredAtEpochSec() == event.occurredAt()
                || legacy.createdAtEpochSec() == event.occurredAt());
    }

    private static String kindFor(LinkedEvent event, boolean isLent) {
        return switch (event.eventType()) {
            case "CREATION" -> isLent
                ? TransactionKind.LOAN_LENT_OUT.name()
                : TransactionKind.LOAN_BORROWED_IN.name();
            case "TOPUP" -> isLent
                ? TransactionKind.LOAN_LENT_TOPUP.name()
                : TransactionKind.LOAN_BORROWED_TOPUP.name();
            case "ADJUSTMENT" -> {
                boolean isIncrease = event.amountCents() != null && event.amountCents() > 0;
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
            default -> null;
        };
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

    private static List<LinkedEvent> readLinkedEvents(SqliteDatabase database, String ownerId) {
        String sql =
            "SELECT j.event_id, j.loan_id, j.owner_id, j.event_type, j.amount_cents, " +
            "       j.account_id, j.transaction_id, j.occurred_at, " +
            "       s.loan_type, s.default_account_id, s.counterparty_name " +
            "FROM loan_journal_v1 j " +
            "JOIN loan_snapshots_v1 s ON s.owner_id = j.owner_id AND s.loan_id = j.loan_id " +
            "WHERE j.owner_id = ? AND j.transaction_id IS NOT NULL " +
            "AND j.event_type IN ('CREATION', 'TOPUP', 'PAYMENT', 'ADJUSTMENT') " +
            "ORDER BY j.loan_id, j.occurred_at, j.event_id";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId);
            List<LinkedEvent> out = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Object amount = result.getObject("amount_cents");
                    out.add(new LinkedEvent(
                        result.getString("event_id"),
                        result.getString("loan_id"),
                        result.getString("owner_id"),
                        result.getString("event_type"),
                        amount == null ? null : ((Number) amount).longValue(),
                        result.getString("account_id"),
                        result.getString("transaction_id"),
                        result.getLong("occurred_at"),
                        result.getString("loan_type"),
                        result.getString("default_account_id"),
                        result.getString("counterparty_name")
                    ));
                }
            }
            return out;
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }
}
