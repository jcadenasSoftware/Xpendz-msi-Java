package myfinances.infrastructure.loan.migration;

import com.myfinaces.db.SqliteDatabase;
import com.myfinaces.sync.DeviceId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import myfinances.application.loan.LoanApplicationService;
import myfinances.domain.loan.aggregate.LoanCommandResult;
import myfinances.domain.loan.aggregate.Outcome;
import myfinances.domain.loan.commands.AddPrincipalCommand;
import myfinances.domain.loan.commands.CloseLoanCommand;
import myfinances.domain.loan.commands.CreateLoanCommand;
import myfinances.domain.loan.commands.LoanCommandEnvelope;
import myfinances.domain.loan.commands.LoanCommandType;
import myfinances.domain.loan.commands.RegisterPaymentCommand;
import myfinances.domain.loan.journal.LoanType;
import myfinances.domain.loan.service.error.LoanAggregateException;
import myfinances.infrastructure.loan.jdbc.LoanPersistenceException;
import myfinances.infrastructure.loan.replay.HistoricalLoanReplayTool;

/**
 * One-time migration of legacy loan data into the canonical event-sourced journal.
 * Reads from legacy {@code loans}, {@code loan_payments} and {@code loan_movements}
 * and replays canonical commands through {@link LoanApplicationService}.
 */
public final class LegacyLoanMigration {

    private LegacyLoanMigration() {}

    public static void migrate(SqliteDatabase database, LoanApplicationService service) {
        List<LegacyLoan> loans = readLegacyLoans(database);
        for (LegacyLoan loan : loans) {
            if (alreadyCanonical(database, loan.ownerId, loan.loanId)) {
                continue;
            }
            List<LegacyMovement> movements = readMovements(database, loan.ownerId, loan.loanId);
            String fingerprint = migrateLoan(database, service, loan, movements);
            if (fingerprint == null) {
                continue;
            }
            migrateMovements(database, service, loan, movements, fingerprint);
        }

        HistoricalLoanPaymentReconciler reconciler = new HistoricalLoanPaymentReconciler();
        HistoricalLoanPaymentReconciler.ReconciliationReport report = reconciler.reconcile(database, service);
        System.out.println("[LegacyLoanMigration] historical reconciliation loansProcessed=" + report.loansProcessed()
            + " paymentsProcessed=" + report.paymentsProcessed()
            + " paymentsReconciled=" + report.paymentsReconciled()
            + " paymentsAlreadyExisting=" + report.paymentsAlreadyExisting()
            + " paymentsOmitted=" + report.paymentsOmitted()
            + " errors=" + report.errors().size()
            + " durationMs=" + report.durationMs());

        String localDeviceId = DeviceId.get();
        int replayed = 0;
        int replayFailed = 0;
        for (LegacyLoan loan : loans) {
            if (!alreadyCanonical(database, loan.ownerId, loan.loanId)) {
                continue;
            }
            if (Objects.equals(localDeviceId, loan.updatedBy)) {
                continue;
            }
            if (!hasCanonicalDrift(database, loan)) {
                continue;
            }
            if (replayDriftedLoan(database, loan)) {
                replayed++;
            } else {
                replayFailed++;
            }
        }
        if (replayed > 0 || replayFailed > 0) {
            System.out.println("[LegacyLoanMigration] drift replay replayed=" + replayed + " failed=" + replayFailed);
        }

        // Enlaza eventos históricos que quedaron con transaction_id NULL pese a
        // existir una transacción legacy equivalente, antes de que el backfill
        // pueda materializar duplicados.
        LegacyLoanTransactionLinkReconciler.reconcile(database);

        CanonicalLoanTransactionBackfill.run(database);
    }

    /**
     * Detects divergence between the transport {@code loans} row and the local
     * canonical snapshot/projection. Status is intentionally excluded: the
     * canonical status is derived from pending (CLOSE requires pending == 0),
     * so a remote CLOSED with pending &gt; 0 could never converge and would
     * replay on every sync.
     */
    private static boolean hasCanonicalDrift(SqliteDatabase database, LegacyLoan loan) {
        CanonicalState state = readCanonicalState(database, loan.ownerId, loan.loanId);
        if (state == null) {
            return true;
        }
        if (loan.principalCents != state.principalCents
            || !Objects.equals(normalize(loan.counterparty), normalize(state.counterparty))
            || !Objects.equals(normalize(loan.currency), normalize(state.currency))
            || !Objects.equals(normalize(loan.type), normalize(state.loanType))
            || !Objects.equals(normalize(loan.defaultAccountId), normalize(state.defaultAccountId))
            || !Objects.equals(normalize(loan.notes), normalize(state.notes))) {
            return true;
        }
        if (state.paymentCount == null || state.totalPaidCents == null) {
            return true;
        }
        long[] expected = expectedPayments(database, loan);
        return expected[0] != state.paymentCount || expected[1] != state.totalPaidCents;
    }

    private static boolean replayDriftedLoan(SqliteDatabase database, LegacyLoan loan) {
        try {
            HistoricalLoanReplayTool.ReplayResult result =
                new HistoricalLoanReplayTool(database).replay(loan.ownerId, loan.loanId);
            if (result.success()) {
                System.out.println("[LegacyLoanMigration] replayed drifted loan " + loan.loanId
                    + " eventsApplied=" + result.eventsApplied());
                return true;
            }
            System.out.println("[LegacyLoanMigration] replay failed for " + loan.loanId
                + " status=" + result.status() + " errors=" + result.errors());
            return false;
        } catch (Exception ex) {
            System.out.println("[LegacyLoanMigration] replay error for " + loan.loanId + ": " + ex.getMessage());
            return false;
        }
    }

    private static CanonicalState readCanonicalState(SqliteDatabase database, String ownerId, String loanId) {
        String sql =
            "SELECT s.principal_cents, s.counterparty_name, s.currency, s.default_account_id, s.notes, s.loan_type, " +
            "p.payment_count, p.total_paid_cents " +
            "FROM loan_snapshots_v1 s " +
            "LEFT JOIN loan_summary_projection_v1 p ON p.owner_id = s.owner_id AND p.loan_id = s.loan_id " +
            "WHERE s.owner_id = ? AND s.loan_id = ?";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId);
            statement.setString(2, loanId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                Object paymentCount = result.getObject("payment_count");
                Object totalPaid = result.getObject("total_paid_cents");
                return new CanonicalState(
                    result.getLong("principal_cents"),
                    result.getString("counterparty_name"),
                    result.getString("currency"),
                    result.getString("default_account_id"),
                    result.getString("notes"),
                    result.getString("loan_type"),
                    paymentCount == null ? null : ((Number) paymentCount).longValue(),
                    totalPaid == null ? null : ((Number) totalPaid).longValue()
                );
            }
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }

    /**
     * Expected payment set mirrors the replay timeline: payment-type movements
     * plus {@code loan_payments} rows not covered by a movement's
     * linked_transaction_id. LOAN_* transactions carry no loan_id, so an orphan
     * repayment transaction on the same account cannot be attributed to this
     * loan (it may belong to another loan sharing the account).
     */
    private static long[] expectedPayments(SqliteDatabase database, LegacyLoan loan) {
        long[] paymentMovements = queryCountSum(database,
            "SELECT COUNT(*), COALESCE(SUM(amount_cents),0) FROM loan_movements " +
            "WHERE user_uid = ? AND loan_id = ? AND UPPER(movement_type) IN ('PAYMENT','PAYMENT_IN','PAYMENT_OUT')",
            loan.ownerId, loan.loanId);
        long[] paymentRows = queryCountSum(database,
            "SELECT COUNT(*), COALESCE(SUM(p.principal_cents),0) FROM loan_payments p " +
            "WHERE p.user_uid = ? AND p.loan_id = ? " +
            "AND (p.linked_transaction_id IS NULL OR NOT EXISTS " +
            "(SELECT 1 FROM loan_movements m WHERE m.loan_id = p.loan_id " +
            "AND m.linked_transaction_id = p.linked_transaction_id))",
            loan.ownerId, loan.loanId);
        return new long[]{paymentMovements[0] + paymentRows[0], paymentMovements[1] + paymentRows[1]};
    }

    private static long[] queryCountSum(SqliteDatabase database, String sql, String p1, String p2) {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, p1);
            statement.setString(2, p2);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return new long[]{result.getLong(1), result.getLong(2)};
                }
                return new long[]{0L, 0L};
            }
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean alreadyCanonical(SqliteDatabase database, String ownerId, String loanId) {
        String sql = "SELECT 1 FROM loan_journal_v1 WHERE owner_id = ? AND loan_id = ? LIMIT 1";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId);
            statement.setString(2, loanId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }

    private static String migrateLoan(SqliteDatabase database, LoanApplicationService service, LegacyLoan loan, List<LegacyMovement> movements) {
        long initialPrincipal = loan.principalCents;
        long occurredAt = loan.occurredAt;
        String transactionId = null;

        LegacyMovement creationMovement = null;
        for (LegacyMovement movement : movements) {
            if ("CREATION".equals(movement.type)) {
                creationMovement = movement;
                initialPrincipal = movement.amountCents;
                occurredAt = movement.occurredAt;
                transactionId = movement.linkedTransactionId;
                break;
            }
        }

        if (transactionId == null || transactionId.isBlank()) {
            String accountId = creationMovement != null && creationMovement.accountId != null
                ? creationMovement.accountId
                : loan.defaultAccountId;
            transactionId = linkLegacyTransaction(
                database, loan, "CREATION", initialPrincipal, accountId, occurredAt);
        }

        LoanCommandEnvelope envelope = new LoanCommandEnvelope(
            LoanCommandType.CREATE_LOAN,
            CanonicalLoanEventIds.deterministic(
                loan.loanId,
                "CREATION",
                creationMovement != null ? "mov:" + creationMovement.id : "loan:" + loan.loanId,
                occurredAt
            ),
            loan.loanId,
            loan.ownerId,
            null,
            occurredAt,
            loan.ownerId,
            loan.ownerId
        );

        try {
            CreateLoanCommand command = new CreateLoanCommand(
                envelope,
                LoanType.valueOf(loan.type),
                initialPrincipal,
                loan.counterparty,
                loan.currency,
                loan.defaultAccountId,
                transactionId,
                loan.notes
            );
            LoanCommandResult result = service.process(command);
            if (result.outcome() == Outcome.APPLIED && result.currentSnapshot() != null) {
                return result.currentSnapshot().journalFingerprint();
            }
        } catch (LoanAggregateException ex) {
            // Skip loan that cannot be recreated canonically
        }
        return null;
    }

    private static void migrateMovements(SqliteDatabase database, LoanApplicationService service, LegacyLoan loan, List<LegacyMovement> movements, String initialFingerprint) {
        String fingerprint = initialFingerprint;
        for (LegacyMovement movement : movements) {
            if ("CREATION".equals(movement.type)) {
                continue;
            }
            LoanCommandResult result = switch (movement.type) {
                case "TOPUP" -> processTopup(database, service, loan, movement, fingerprint);
                case "PAYMENT_IN", "PAYMENT_OUT" -> processPayment(database, service, loan, movement, fingerprint);
                case "CLOSE" -> processClose(service, loan, movement, fingerprint);
                default -> null;
            };
            if (result != null && result.outcome() == Outcome.APPLIED && result.currentSnapshot() != null) {
                fingerprint = result.currentSnapshot().journalFingerprint();
            }
        }
    }

    private static LoanCommandResult processTopup(SqliteDatabase database, LoanApplicationService service, LegacyLoan loan, LegacyMovement movement, String fingerprint) {
        LoanCommandEnvelope envelope = new LoanCommandEnvelope(
            LoanCommandType.ADD_PRINCIPAL,
            CanonicalLoanEventIds.deterministic(loan.loanId, "TOPUP", "mov:" + movement.id, movement.occurredAt),
            loan.loanId,
            loan.ownerId,
            fingerprint,
            movement.occurredAt,
            loan.ownerId,
            loan.ownerId
        );
        String transactionId = movement.linkedTransactionId;
        if (transactionId == null || transactionId.isBlank()) {
            transactionId = linkLegacyTransaction(
                database, loan, "TOPUP", movement.amountCents, movement.accountId, movement.occurredAt);
        }
        try {
            return service.process(new AddPrincipalCommand(envelope, movement.amountCents, movement.accountId, transactionId, movement.note));
        } catch (LoanAggregateException ex) {
            return null;
        }
    }

    private static LoanCommandResult processPayment(SqliteDatabase database, LoanApplicationService service, LegacyLoan loan, LegacyMovement movement, String fingerprint) {
        LoanCommandEnvelope envelope = new LoanCommandEnvelope(
            LoanCommandType.REGISTER_PAYMENT,
            CanonicalLoanEventIds.deterministic(loan.loanId, "PAYMENT", "mov:" + movement.id, movement.occurredAt),
            loan.loanId,
            loan.ownerId,
            fingerprint,
            movement.occurredAt,
            loan.ownerId,
            loan.ownerId
        );
        String transactionId = movement.linkedTransactionId;
        if (transactionId == null || transactionId.isBlank()) {
            transactionId = linkLegacyTransaction(
                database, loan, "PAYMENT", movement.amountCents, movement.accountId, movement.occurredAt);
        }
        try {
            return service.process(new RegisterPaymentCommand(envelope, movement.amountCents, movement.accountId, transactionId, movement.note));
        } catch (LoanAggregateException ex) {
            return null;
        }
    }

    /**
     * Cuando el movimiento legacy no trae {@code linked_transaction_id}, busca
     * la transacción financiera original equivalente (misma cuenta, kind,
     * ocurrido y —salvo CREATION— monto, sin estar ya referenciada). Solo
     * enlaza correspondencias inequívocas; sin candidato único queda NULL y el
     * backfill decide.
     */
    private static String linkLegacyTransaction(
        SqliteDatabase database,
        LegacyLoan loan,
        String eventType,
        long amountCents,
        String accountId,
        long occurredAt
    ) {
        try {
            return LegacyLoanTransactionLinkReconciler.findEquivalentTransaction(
                database,
                loan.ownerId,
                eventType,
                loan.type,
                amountCents,
                accountId,
                loan.defaultAccountId,
                occurredAt,
                loan.counterparty
            );
        } catch (SQLException ex) {
            return null;
        }
    }

    private static LoanCommandResult processClose(LoanApplicationService service, LegacyLoan loan, LegacyMovement movement, String fingerprint) {
        LoanCommandEnvelope envelope = new LoanCommandEnvelope(
            LoanCommandType.CLOSE_LOAN,
            CanonicalLoanEventIds.deterministic(loan.loanId, "CLOSE", "mov:" + movement.id, movement.occurredAt),
            loan.loanId,
            loan.ownerId,
            fingerprint,
            movement.occurredAt,
            loan.ownerId,
            loan.ownerId
        );
        try {
            return service.process(new CloseLoanCommand(envelope, movement.note != null ? movement.note : "Cierre", movement.note));
        } catch (LoanAggregateException ex) {
            return null;
        }
    }

    private static List<LegacyLoan> readLegacyLoans(SqliteDatabase database) {
        String sql =
            "SELECT id, user_uid, type, counterparty_name, principal_cents, currency, status, notes, " +
            "occurred_at_epoch_sec, account_id, updated_by FROM loans ORDER BY user_uid, occurred_at_epoch_sec";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            List<LegacyLoan> loans = new ArrayList<>();
            while (result.next()) {
                loans.add(new LegacyLoan(
                    result.getString("id"),
                    result.getString("user_uid"),
                    result.getString("type"),
                    result.getString("counterparty_name"),
                    result.getLong("principal_cents"),
                    result.getString("currency"),
                    result.getString("status"),
                    result.getString("notes"),
                    result.getLong("occurred_at_epoch_sec"),
                    result.getString("account_id"),
                    result.getString("updated_by")
                ));
            }
            return loans;
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }

    private static List<LegacyMovement> readMovements(SqliteDatabase database, String ownerId, String loanId) {
        String sql =
            "SELECT id, movement_type, amount_cents, account_id, linked_transaction_id, note, occurred_at_epoch_sec " +
            "FROM loan_movements WHERE user_uid = ? AND loan_id = ? ORDER BY occurred_at_epoch_sec, created_at_epoch_sec";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId);
            statement.setString(2, loanId);
            try (ResultSet result = statement.executeQuery()) {
                List<LegacyMovement> movements = new ArrayList<>();
                while (result.next()) {
                    movements.add(new LegacyMovement(
                        result.getString("id"),
                        result.getString("movement_type"),
                        result.getLong("amount_cents"),
                        result.getString("account_id"),
                        result.getString("linked_transaction_id"),
                        result.getString("note"),
                        result.getLong("occurred_at_epoch_sec")
                    ));
                }
                return movements;
            }
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }

    private record LegacyLoan(
        String loanId,
        String ownerId,
        String type,
        String counterparty,
        long principalCents,
        String currency,
        String status,
        String notes,
        long occurredAt,
        String defaultAccountId,
        String updatedBy
    ) {}

    private record LegacyMovement(
        String id,
        String type,
        long amountCents,
        String accountId,
        String linkedTransactionId,
        String note,
        long occurredAt
    ) {}

    private record CanonicalState(
        long principalCents,
        String counterparty,
        String currency,
        String defaultAccountId,
        String notes,
        String loanType,
        Long paymentCount,
        Long totalPaidCents
    ) {}
}
