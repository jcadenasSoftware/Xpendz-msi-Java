package myfinances.infrastructure.loan.migration;

import com.myfinaces.db.SqliteDatabase;
import com.myfinaces.db.TransactionKind;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import myfinances.infrastructure.loan.jdbc.LoanPersistenceException;

/**
 * Completa {@code transaction_id} faltantes en {@code loan_journal_v1} cuando
 * existe una transacción financiera legacy equivalente e inequívoca.
 * <p>
 * La migración histórica solo enlazaba movimientos que traían
 * {@code loan_movements.linked_transaction_id}; cuando ese campo venía NULL el
 * evento quedaba huérfano aunque la transacción original existiera, y el
 * backfill terminaba materializando un duplicado. Este reconciliador repara
 * ese enlace sin crear ni modificar transacciones.
 * <p>
 * Criterio de equivalencia (mismo modelo de kinds que el backfill):
 * cuenta = {@code event.account_id} o, en su defecto, la cuenta por defecto del
 * préstamo; {@code occurred_at} exacto; monto exacto salvo CREATION (un ajuste
 * remoto queda plegado en el principal del evento de creación); la transacción
 * no debe estar referenciada por journal, {@code loan_payments} ni
 * {@code loan_movements}; y el resultado debe ser único — cero o varios
 * candidatos dejan el evento en NULL.
 * <p>
 * Los ADJUSTMENT sintéticos ({@code synth:adjust:*}) y los eventos revertidos
 * nunca se enlazan: no representan flujo de dinero. El reconciliador solo
 * escribe {@code transaction_id}; el fingerprint almacenado en snapshot y
 * proyección permanece mutuamente consistente y se recomputa en el próximo
 * evento aplicado.
 */
public final class LegacyLoanTransactionLinkReconciler {

    private LegacyLoanTransactionLinkReconciler() {}

    public record LinkReport(
        int eventsScanned,
        int eventsLinked,
        int ambiguous,
        int unmatched,
        List<String> errors
    ) {}

    private record UnlinkedEvent(
        String eventId,
        String loanId,
        String ownerId,
        String eventType,
        Long amountCents,
        String accountId,
        long occurredAt,
        String loanType,
        String defaultAccountId,
        String counterpartyName
    ) {}

    public static LinkReport reconcile(SqliteDatabase database) {
        List<UnlinkedEvent> events = readUnlinkedEvents(database);
        List<String> errors = new ArrayList<>();
        int linked = 0;
        int ambiguous = 0;
        int unmatched = 0;

        for (UnlinkedEvent event : events) {
            try {
                List<String> candidates = findCandidates(database, event);
                if (candidates.size() == 1) {
                    linkTransaction(database, event.ownerId(), event.eventId(), candidates.getFirst());
                    linked++;
                } else if (candidates.isEmpty()) {
                    unmatched++;
                } else {
                    ambiguous++;
                }
            } catch (Exception ex) {
                errors.add(event.loanId() + "/" + event.eventId() + " (" + event.eventType() + "): " + ex.getMessage());
            }
        }

        LinkReport report = new LinkReport(events.size(), linked, ambiguous, unmatched, List.copyOf(errors));
        if (linked > 0 || ambiguous > 0 || !errors.isEmpty()) {
            System.out.println("[LegacyLoanTxLink] scanned=" + report.eventsScanned()
                + " linked=" + report.eventsLinked()
                + " ambiguous=" + report.ambiguous()
                + " unmatched=" + report.unmatched()
                + " errors=" + report.errors().size());
        }
        for (String error : report.errors()) {
            System.out.println("[LegacyLoanTxLink]   error " + error);
        }
        return report;
    }

    /**
     * Localiza la transacción legacy equivalente a un evento financiero, si la
     * correspondencia es inequívoca. Devuelve {@code null} cuando no hay
     * candidato o hay más de uno.
     */
    static String findEquivalentTransaction(
        SqliteDatabase database,
        String ownerId,
        String eventType,
        String loanType,
        Long amountCents,
        String accountId,
        String defaultAccountId,
        long occurredAt
    ) throws SQLException {
        return findEquivalentTransaction(
            database, ownerId, eventType, loanType, amountCents, accountId, defaultAccountId, occurredAt, null);
    }

    static String findEquivalentTransaction(
        SqliteDatabase database,
        String ownerId,
        String eventType,
        String loanType,
        Long amountCents,
        String accountId,
        String defaultAccountId,
        long occurredAt,
        String counterpartyName
    ) throws SQLException {
        UnlinkedEvent probe = new UnlinkedEvent(
            null, null, ownerId, eventType, amountCents, accountId, occurredAt, loanType, defaultAccountId,
            counterpartyName);
        List<String> candidates = findCandidates(database, probe);
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    /**
     * Devuelve todos los candidatos compatibles de un evento (vacío = sin
     * correspondencia; más de uno = ambiguo). Uso interno: auditoría de
     * clasificación de eventos sin enlace.
     */
    static List<String> candidateIds(
        SqliteDatabase database,
        String ownerId,
        String eventId,
        String eventType,
        String loanType,
        Long amountCents,
        String accountId,
        String defaultAccountId,
        long occurredAt,
        String counterpartyName
    ) throws SQLException {
        UnlinkedEvent probe = new UnlinkedEvent(
            eventId, null, ownerId, eventType, amountCents, accountId, occurredAt, loanType, defaultAccountId,
            counterpartyName);
        return findCandidates(database, probe);
    }

    private static List<String> findCandidates(SqliteDatabase database, UnlinkedEvent event) throws SQLException {
        if (event.amountCents() == null || event.amountCents() == 0L
            || event.loanType() == null || event.loanType().isBlank()) {
            return List.of();
        }
        String accountId = event.accountId() != null && !event.accountId().isBlank()
            ? event.accountId()
            : event.defaultAccountId();
        if (accountId == null || accountId.isBlank()) {
            return List.of();
        }
        boolean isLent = "LENT".equalsIgnoreCase(event.loanType());
        String kind = kindFor(event.eventType(), event.amountCents(), isLent);
        if (kind == null) {
            return List.of();
        }
        // CREATION se compara sin monto: el evento puede plegar ajustes
        // posteriores en el principal, mientras la transacción histórica
        // conserva el desembolso original.
        boolean ignoreAmount = "CREATION".equals(event.eventType());
        boolean hasMovements = tableExists(database, "loan_movements");
        // La materialización determinística del propio evento nunca es una
        // transacción legacy: excluirla permite distinguir el original real del
        // duplicado canónico cuando ambos comparten firma.
        String selfMaterialization = event.eventId() == null
            ? null
            : CanonicalLoanEventIds.deterministicTransactionId(event.eventId());
        String sql =
            "SELECT t.id, t.amount_cents FROM transactions t " +
            "WHERE t.user_uid = ? AND t.kind = ? AND t.account_id = ? AND t.occurred_at_epoch_sec = ? " +
            "AND NOT EXISTS (SELECT 1 FROM loan_journal_v1 j WHERE j.owner_id = t.user_uid AND j.transaction_id = t.id) " +
            "AND NOT EXISTS (SELECT 1 FROM loan_payments p WHERE p.user_uid = t.user_uid AND p.linked_transaction_id = t.id) " +
            (hasMovements
                ? "AND NOT EXISTS (SELECT 1 FROM loan_movements m WHERE m.user_uid = t.user_uid AND m.linked_transaction_id = t.id) "
                : "") +
            "ORDER BY t.created_at_epoch_sec, t.id";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, event.ownerId());
            statement.setString(2, kind);
            statement.setString(3, accountId);
            statement.setLong(4, event.occurredAt());
            List<String> candidates = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String id = result.getString("id");
                    if (id.equals(selfMaterialization)) {
                        continue;
                    }
                    if (ignoreAmount || result.getLong("amount_cents") == Math.abs(event.amountCents())) {
                        candidates.add(id);
                    }
                }
            }
            if (candidates.isEmpty()) {
                candidates = findLegacyFormatCandidates(database, event, kind, accountId);
            }
            return candidates;
        }
    }

    /**
     * Segundo pase, solo para CREATION: las instalaciones legacy más antiguas
     * registraban el desembolso como {@code EXPENSE}/{@code INCOME} en la
     * categoría de sistema {@code system-loan-{uid}} con la nota
     * {@code "<kind esperado>: <contraparte>"}. El {@code occurred_at} de esas
     * filas podía quedar retrasado a medianoche, por lo que el ancla temporal es
     * {@code created_at_epoch_sec == event.occurred_at} (el instante exacto en
     * que la app registró el préstamo) además del {@code occurred_at} exacto.
     */
    private static List<String> findLegacyFormatCandidates(
        SqliteDatabase database,
        UnlinkedEvent event,
        String expectedKind,
        String accountId
    ) throws SQLException {
        if (!"CREATION".equals(event.eventType())
            || event.counterpartyName() == null || event.counterpartyName().isBlank()) {
            return List.of();
        }
        String legacyKind = "LENT".equalsIgnoreCase(event.loanType()) ? "EXPENSE" : "INCOME";
        String expectedNote = expectedKind + ": " + event.counterpartyName();
        String sql =
            "SELECT t.id FROM transactions t " +
            "WHERE t.user_uid = ? AND t.kind = ? AND t.category_id = ? AND t.account_id = ? " +
            "AND t.amount_cents = ? AND t.note = ? " +
            "AND (t.occurred_at_epoch_sec = ? OR t.created_at_epoch_sec = ?) " +
            "AND NOT EXISTS (SELECT 1 FROM loan_journal_v1 j WHERE j.owner_id = t.user_uid AND j.transaction_id = t.id) " +
            "AND NOT EXISTS (SELECT 1 FROM loan_payments p WHERE p.user_uid = t.user_uid AND p.linked_transaction_id = t.id) " +
            "ORDER BY t.id";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, event.ownerId());
            statement.setString(2, legacyKind);
            statement.setString(3, "system-loan-" + event.ownerId());
            statement.setString(4, accountId);
            statement.setLong(5, Math.abs(event.amountCents()));
            statement.setString(6, expectedNote);
            statement.setLong(7, event.occurredAt());
            statement.setLong(8, event.occurredAt());
            List<String> candidates = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    candidates.add(result.getString("id"));
                }
            }
            return candidates;
        }
    }

    private static void linkTransaction(SqliteDatabase database, String ownerId, String eventId, String transactionId)
        throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "UPDATE loan_journal_v1 SET transaction_id = ? " +
                 "WHERE owner_id = ? AND event_id = ? AND transaction_id IS NULL")) {
            statement.setString(1, transactionId);
            statement.setString(2, ownerId);
            statement.setString(3, eventId);
            statement.executeUpdate();
        }
    }

    private static List<UnlinkedEvent> readUnlinkedEvents(SqliteDatabase database) {
        String sql =
            "SELECT j.event_id, j.loan_id, j.owner_id, j.event_type, j.amount_cents, " +
            "       j.account_id, j.occurred_at, s.loan_type, s.default_account_id, s.counterparty_name " +
            "FROM loan_journal_v1 j " +
            "JOIN loan_snapshots_v1 s ON s.owner_id = j.owner_id AND s.loan_id = j.loan_id " +
            "WHERE j.transaction_id IS NULL " +
            "AND j.event_type IN ('CREATION', 'TOPUP', 'PAYMENT', 'ADJUSTMENT') " +
            // Un evento revertido no debe recuperar su transacción.
            "AND NOT EXISTS (SELECT 1 FROM loan_journal_v1 r " +
            "WHERE r.owner_id = j.owner_id AND r.event_type = 'REVERSAL' " +
            "AND r.payload_target_event_id = j.event_id) " +
            "ORDER BY j.owner_id, j.loan_id, j.occurred_at, j.event_id";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            List<UnlinkedEvent> out = new ArrayList<>();
            while (result.next()) {
                String eventId = result.getString("event_id");
                String loanId = result.getString("loan_id");
                String eventType = result.getString("event_type");
                long occurredAt = result.getLong("occurred_at");
                // Un ADJUSTMENT sintético solo reconcilia el principal con el
                // estado remoto: no representa flujo de dinero y jamás debe
                // enlazarse a una transacción.
                if ("ADJUSTMENT".equals(eventType)
                    && CanonicalLoanEventIds.deterministic(
                        loanId, "ADJUSTMENT", "synth:adjust:" + loanId, occurredAt).equals(eventId)) {
                    continue;
                }
                Object amount = result.getObject("amount_cents");
                out.add(new UnlinkedEvent(
                    eventId,
                    loanId,
                    result.getString("owner_id"),
                    eventType,
                    amount == null ? null : ((Number) amount).longValue(),
                    result.getString("account_id"),
                    occurredAt,
                    result.getString("loan_type"),
                    result.getString("default_account_id"),
                    result.getString("counterparty_name")
                ));
            }
            return out;
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }

    private static String kindFor(String eventType, long amountCents, boolean isLent) {
        return switch (eventType) {
            case "CREATION" -> isLent
                ? TransactionKind.LOAN_LENT_OUT.name()
                : TransactionKind.LOAN_BORROWED_IN.name();
            case "TOPUP" -> isLent
                ? TransactionKind.LOAN_LENT_TOPUP.name()
                : TransactionKind.LOAN_BORROWED_TOPUP.name();
            case "ADJUSTMENT" -> {
                boolean isIncrease = amountCents > 0;
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
}
