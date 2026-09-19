package myfinances.infrastructure.loan.migration;

import com.myfinaces.db.SqliteDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import myfinances.application.loan.LoanApplicationService;
import myfinances.domain.loan.aggregate.LoanCommandResult;
import myfinances.domain.loan.aggregate.Outcome;
import myfinances.domain.loan.commands.LoanCommandEnvelope;
import myfinances.domain.loan.commands.LoanCommandType;
import myfinances.domain.loan.commands.RegisterPaymentCommand;
import myfinances.domain.loan.journal.LoanType;
import myfinances.domain.loan.projection.LoanPaymentProjection;
import myfinances.domain.loan.projection.LoanProjectionQueryRepository;
import myfinances.domain.loan.projection.LoanSummaryProjection;
import myfinances.domain.loan.service.error.LoanAggregateException;
import myfinances.infrastructure.loan.jdbc.LoanPersistenceException;
import myfinances.infrastructure.loan.projection.jdbc.JdbcLoanProjectionQueryRepository;

public final class HistoricalLoanPaymentReconciler {
    private static final Comparator<LegacyPaymentRow> LEGACY_PAYMENT_ORDER = Comparator
        .comparingLong(LegacyPaymentRow::occurredAtEpochSec)
        .thenComparingLong(LegacyPaymentRow::createdAtEpochSec)
        .thenComparing(LegacyPaymentRow::linkedTransactionId, Comparator.nullsLast(String::compareTo));

    public ReconciliationReport reconcile(SqliteDatabase database, LoanApplicationService service) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(service, "service");

        long startedAt = System.currentTimeMillis();
        LoanProjectionQueryRepository queryRepository = new JdbcLoanProjectionQueryRepository(database);
        List<LegacyPaymentRow> payments = readLegacyPayments(database);
        Map<LoanKey, List<LegacyPaymentRow>> grouped = groupByLoan(payments);
        List<String> errors = new ArrayList<>();

        int loansProcessed = 0;
        int paymentsProcessed = 0;
        int paymentsReconciled = 0;
        int paymentsAlreadyExisting = 0;
        int paymentsOmitted = 0;

        for (Map.Entry<LoanKey, List<LegacyPaymentRow>> entry : grouped.entrySet()) {
            loansProcessed++;
            LoanKey key = entry.getKey();
            List<LegacyPaymentRow> loanPayments = new ArrayList<>(entry.getValue());
            loanPayments.sort(LEGACY_PAYMENT_ORDER);

            LoanSummaryProjection summary = queryRepository.getSummaryProjection(key.ownerId(), key.loanId());
            if (summary == null) {
                paymentsOmitted += loanPayments.size();
                errors.add("Préstamo sin resumen canónico: " + key.ownerId() + '/' + key.loanId());
                for (LegacyPaymentRow payment : loanPayments) {
                    logReconciler("RECONCILER_SKIP", key, payment, payment.linkedTransactionId(), null, payment.id(), "reason=noSummary");
                }
                continue;
            }

            Map<String, String> ownerJournalLoanByTransactionId = readOwnerJournalLoanByTransactionId(database, key.ownerId());
            Set<String> ownerJournalTransactionIds = ownerJournalLoanByTransactionId.keySet();
            Set<String> journalTransactionIds = new LinkedHashSet<>();
            Set<String> journalSignatures = new LinkedHashSet<>();
            for (LoanPaymentProjection projection : queryRepository.getPaymentProjections(key.ownerId(), key.loanId())) {
                if (projection.transactionId() != null && !projection.transactionId().isBlank()) {
                    journalTransactionIds.add(projection.transactionId());
                }
                journalSignatures.add(signature(projection.accountId(), projection.amountCents(), projection.occurredAt()));
            }

            String fingerprint = summary.journalFingerprint();
            Set<String> processedTransactionIds = new LinkedHashSet<>();

            for (LegacyPaymentRow payment : loanPayments) {
                paymentsProcessed++;
                String paymentSignature = signature(payment.accountId(), payment.principalCents(), payment.occurredAtEpochSec());
                logReconciler("RECONCILER_START", key, payment, payment.linkedTransactionId(), null, payment.id(), "fingerprint=" + fingerprint);

                if (journalSignatures.contains(paymentSignature)) {
                    paymentsAlreadyExisting++;
                    logReconciler("RECONCILER_SKIP", key, payment, payment.linkedTransactionId(), null, payment.id(), "reason=duplicateSignature");
                    continue;
                }

                String resolvedTransactionId = resolveTransactionId(database, payment, summary.loanType(), ownerJournalLoanByTransactionId, ownerJournalTransactionIds, journalTransactionIds, processedTransactionIds);
                if (resolvedTransactionId == null) {
                    paymentsOmitted++;
                    errors.add("Pago no recuperable: " + key.ownerId() + '/' + key.loanId() + '/' + payment.id());
                    logReconciler("RECONCILER_SKIP", key, payment, null, null, payment.id(), "reason=noTransaction");
                    continue;
                }

                if (journalTransactionIds.contains(resolvedTransactionId)) {
                    paymentsAlreadyExisting++;
                    processedTransactionIds.add(resolvedTransactionId);
                    logReconciler("RECONCILER_SKIP", key, payment, resolvedTransactionId, null, payment.id(), "reason=duplicateTransaction");
                    continue;
                }

                LoanCommandEnvelope envelope = new LoanCommandEnvelope(
                    LoanCommandType.REGISTER_PAYMENT,
                    CanonicalLoanEventIds.forTransportPayment(key.loanId(), payment.id(), payment.occurredAtEpochSec()),
                    key.loanId(),
                    key.ownerId(),
                    fingerprint,
                    payment.occurredAtEpochSec(),
                    key.ownerId(),
                    key.ownerId()
                );
                try {
                    logReconciler("RECONCILER_APPLY", key, payment, resolvedTransactionId, envelope.operationId(), payment.id(), "fingerprint=" + fingerprint);
                    LoanCommandResult result = service.process(new RegisterPaymentCommand(
                        envelope,
                        payment.principalCents(),
                        payment.accountId(),
                        resolvedTransactionId,
                        payment.note()
                    ));

                    if (result.outcome() == Outcome.APPLIED && result.currentSnapshot() != null) {
                        fingerprint = result.currentSnapshot().journalFingerprint();
                        paymentsReconciled++;
                        journalTransactionIds.add(resolvedTransactionId);
                        journalSignatures.add(paymentSignature);
                        processedTransactionIds.add(resolvedTransactionId);
                    } else if (result.outcome() == Outcome.REPLAYED) {
                        paymentsAlreadyExisting++;
                        processedTransactionIds.add(resolvedTransactionId);
                        logReconciler("RECONCILER_SKIP", key, payment, resolvedTransactionId, result.operationId(), result.event().eventId(), "reason=duplicateOutcome outcome=REPLAYED");
                    } else {
                        paymentsOmitted++;
                        errors.add("Resultado inesperado al reconciliar: " + key.ownerId() + '/' + key.loanId() + '/' + payment.id());
                        logReconciler("RECONCILER_SKIP", key, payment, resolvedTransactionId, result.operationId(), result.event().eventId(), "reason=unexpectedOutcome outcome=" + result.outcome());
                    }
                } catch (LoanAggregateException ex) {
                    paymentsOmitted++;
                    errors.add("Aggregate rechazó pago histórico: " + key.ownerId() + '/' + key.loanId() + '/' + payment.id() + " -> " + ex.getMessage());
                    logReconciler("RECONCILER_SKIP", key, payment, resolvedTransactionId, envelope.operationId(), payment.id(), "reason=aggregateRejected error=" + ex.getMessage());
                } catch (Exception ex) {
                    paymentsOmitted++;
                    errors.add("Fallo inesperado al reconciliar: " + key.ownerId() + '/' + key.loanId() + '/' + payment.id() + " -> " + ex.getMessage());
                    logReconciler("RECONCILER_SKIP", key, payment, resolvedTransactionId, envelope.operationId(), payment.id(), "reason=exception error=" + ex.getMessage());
                }
            }

            validateLoan(queryRepository, key, errors);
        }

        long finishedAt = System.currentTimeMillis();
        return new ReconciliationReport(
            loansProcessed,
            paymentsProcessed,
            paymentsReconciled,
            paymentsAlreadyExisting,
            paymentsOmitted,
            List.copyOf(errors),
            finishedAt - startedAt
        );
    }

    private static void validateLoan(LoanProjectionQueryRepository queryRepository, LoanKey key, List<String> errors) {
        try {
            LoanSummaryProjection summary = queryRepository.getSummaryProjection(key.ownerId(), key.loanId());
            if (summary == null) {
                errors.add("Resumen faltante tras reconciliación: " + key.ownerId() + '/' + key.loanId());
                return;
            }
            List<LoanPaymentProjection> payments = queryRepository.getPaymentProjections(key.ownerId(), key.loanId());
            long totalPaid = payments.stream().mapToLong(LoanPaymentProjection::amountCents).sum();
            if (payments.size() != summary.paymentCount()) {
                errors.add("paymentCount inconsistente: " + key.ownerId() + '/' + key.loanId());
            }
            if (totalPaid != summary.totalPaidCents()) {
                errors.add("totalPaid inconsistente: " + key.ownerId() + '/' + key.loanId());
            }
            if (!summary.journalFingerprint().isBlank() && summary.totalPaidCents() >= 0L && summary.pendingCents() < 0L) {
                errors.add("pending inválido: " + key.ownerId() + '/' + key.loanId());
            }
        } catch (Exception ex) {
            errors.add("Validación fallida: " + key.ownerId() + '/' + key.loanId() + " -> " + ex.getMessage());
        }
    }

    private static Map<LoanKey, List<LegacyPaymentRow>> groupByLoan(List<LegacyPaymentRow> payments) {
        Map<LoanKey, List<LegacyPaymentRow>> grouped = new LinkedHashMap<>();
        for (LegacyPaymentRow payment : payments) {
            grouped.computeIfAbsent(new LoanKey(payment.ownerId(), payment.loanId()), ignored -> new ArrayList<>()).add(payment);
        }
        return grouped;
    }

    private static List<LegacyPaymentRow> readLegacyPayments(SqliteDatabase database) {
        String sql =
            "SELECT p.id, p.loan_id, p.user_uid, p.account_id, p.principal_cents, p.occurred_at_epoch_sec, " +
            "p.linked_transaction_id, p.note, p.created_at_epoch_sec, p.updated_at_epoch_sec, p.updated_by, l.type AS loan_type " +
            "FROM loan_payments p LEFT JOIN loans l ON l.user_uid = p.user_uid AND l.id = p.loan_id " +
            "ORDER BY p.user_uid, p.loan_id, p.occurred_at_epoch_sec, p.created_at_epoch_sec, COALESCE(p.linked_transaction_id, '')";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            List<LegacyPaymentRow> out = new ArrayList<>();
            while (result.next()) {
                out.add(new LegacyPaymentRow(
                    result.getString("id"),
                    result.getString("loan_id"),
                    result.getString("user_uid"),
                    result.getString("account_id"),
                    result.getLong("principal_cents"),
                    result.getLong("occurred_at_epoch_sec"),
                    result.getString("linked_transaction_id"),
                    result.getString("note"),
                    result.getLong("created_at_epoch_sec"),
                    result.getLong("updated_at_epoch_sec"),
                    result.getString("updated_by"),
                    result.getString("loan_type")
                ));
            }
            return List.copyOf(out);
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }

    private static String resolveTransactionId(
        SqliteDatabase database,
        LegacyPaymentRow payment,
        LoanType loanType,
        Map<String, String> ownerJournalLoanByTransactionId,
        Set<String> ownerJournalTransactionIds,
        Set<String> journalTransactionIds,
        Set<String> alreadyProcessedTransactionIds
    ) {
        if (payment.linkedTransactionId() != null && !payment.linkedTransactionId().isBlank()) {
            String journalLoanId = ownerJournalLoanByTransactionId.get(payment.linkedTransactionId());
            if (journalLoanId != null) {
                return journalLoanId.equals(payment.loanId()) ? payment.linkedTransactionId() : null;
            }
            if (alreadyProcessedTransactionIds.contains(payment.linkedTransactionId())) {
                return payment.linkedTransactionId();
            }
            return transactionExists(database, payment.ownerId(), payment.linkedTransactionId(), transactionKind(loanType, payment))
                ? payment.linkedTransactionId()
                : null;
        }

        List<String> candidates = findTransactionCandidates(database, payment, loanType);
        candidates.removeIf(ownerJournalTransactionIds::contains);
        candidates.removeIf(journalTransactionIds::contains);
        candidates.removeIf(alreadyProcessedTransactionIds::contains);
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    private static boolean transactionExists(SqliteDatabase database, String ownerId, String transactionId, String expectedKind) {
        String sql = "SELECT 1 FROM transactions WHERE user_uid = ? AND id = ? AND kind = ? LIMIT 1";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId);
            statement.setString(2, transactionId);
            statement.setString(3, expectedKind);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }

    private static List<String> findTransactionCandidates(SqliteDatabase database, LegacyPaymentRow payment, LoanType loanType) {
        String sql =
            "SELECT id FROM transactions WHERE user_uid = ? AND kind = ? AND account_id = ? AND amount_cents = ? AND occurred_at_epoch_sec = ? " +
            "ORDER BY created_at_epoch_sec ASC, id ASC";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, payment.ownerId());
            statement.setString(2, transactionKind(loanType, payment));
            statement.setString(3, payment.accountId());
            statement.setLong(4, payment.principalCents());
            statement.setLong(5, payment.occurredAtEpochSec());
            try (ResultSet result = statement.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (result.next()) {
                    out.add(result.getString("id"));
                }
                return out;
            }
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }

    private static String transactionKind(LoanType loanType, LegacyPaymentRow payment) {
        LoanType resolved = loanType;
        if (resolved == null && payment.loanTypeText() != null && !payment.loanTypeText().isBlank()) {
            resolved = LoanType.valueOf(payment.loanTypeText());
        }
        if (resolved == LoanType.LENT) {
            return "LOAN_REPAYMENT_PRINCIPAL_IN";
        }
        return "LOAN_REPAYMENT_PRINCIPAL_OUT";
    }

    private static String signature(String accountId, long amountCents, long occurredAt) {
        return accountId + '|' + amountCents + '|' + occurredAt;
    }

    private static Map<String, String> readOwnerJournalLoanByTransactionId(SqliteDatabase database, String ownerId) {
        String sql = "SELECT loan_id, transaction_id FROM loan_journal_v1 WHERE owner_id = ? AND event_type = 'PAYMENT' AND transaction_id IS NOT NULL";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId);
            try (ResultSet result = statement.executeQuery()) {
                Map<String, String> out = new LinkedHashMap<>();
                while (result.next()) {
                    out.put(result.getString("transaction_id"), result.getString("loan_id"));
                }
                return out;
            }
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }

    public record ReconciliationReport(
        int loansProcessed,
        int paymentsProcessed,
        int paymentsReconciled,
        int paymentsAlreadyExisting,
        int paymentsOmitted,
        List<String> errors,
        long durationMs
    ) {}

    private static void logReconciler(
        String label,
        LoanKey key,
        LegacyPaymentRow payment,
        String transactionId,
        String operationId,
        String eventId,
        String extras
    ) {
        System.out.println(
            "[LoanPaymentTrace] " + label
                + " loanId=" + valueOrDash(key.loanId())
                + " paymentId=" + valueOrDash(payment.id())
                + " transactionId=" + valueOrDash(transactionId)
                + " operationId=" + valueOrDash(operationId)
                + " eventId=" + valueOrDash(eventId)
                + " updatedAt=" + payment.updatedAtEpochSec()
                + " updatedBy=" + valueOrDash(payment.updatedBy())
                + " accountId=" + valueOrDash(payment.accountId())
                + " principalCents=" + payment.principalCents()
                + " occurredAt=" + payment.occurredAtEpochSec()
                + (extras == null || extras.isBlank() ? "" : " " + extras)
        );
    }

    private static String valueOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private record LoanKey(String ownerId, String loanId) {}

    private record LegacyPaymentRow(
        String id,
        String loanId,
        String ownerId,
        String accountId,
        long principalCents,
        long occurredAtEpochSec,
        String linkedTransactionId,
        String note,
        long createdAtEpochSec,
        long updatedAtEpochSec,
        String updatedBy,
        String loanTypeText
    ) {}
}
