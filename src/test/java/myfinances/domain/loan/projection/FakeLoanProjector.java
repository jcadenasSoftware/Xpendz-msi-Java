package myfinances.domain.loan.projection;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import myfinances.domain.loan.aggregate.LoanCommandResult;
import myfinances.domain.loan.journal.LoanEventPayload;
import myfinances.domain.loan.journal.LoanEventType;
import myfinances.domain.loan.journal.LoanMovement;
import myfinances.domain.loan.reducer.LoanReducer;
import myfinances.domain.loan.reducer.LoanReductionResult;
import myfinances.domain.loan.reducer.ReductionResultType;
import myfinances.domain.loan.repository.LoanRepository;
import myfinances.domain.loan.snapshot.LoanSnapshot;

public final class FakeLoanProjector implements LoanProjector {
    private final Map<String, LoanPaymentProjection> payments = new HashMap<>();
    private final Map<String, LoanSummaryProjection> summaries = new HashMap<>();

    @Override
    public void project(LoanCommandResult result) {
        for (LoanProjectionChange change : result.projectionChanges()) {
            switch (change.type()) {
                case NONE -> {}
                case ADD_PAYMENT_PROJECTION -> {
                    LoanPaymentProjection payment = toPaymentProjection(result.event());
                    payments.put(payment.sourceEventId(), payment);
                }
                case REMOVE_PAYMENT_PROJECTION -> payments.remove(change.sourceEventId());
                case REBUILD_LOAN_SNAPSHOT -> {
                    LoanSummaryProjection summary = toSummaryProjection(result.currentSnapshot(), paymentSummary(result.currentSnapshot().ownerId(), result.currentSnapshot().loanId()));
                    summaries.put(key(summary.ownerId(), summary.loanId()), summary);
                }
            }
        }
    }

    @Override
    public void rebuild(String ownerId, String loanId, LoanRepository repository, LoanReducer reducer) {
        List<LoanMovement> journal = repository.getJournal(ownerId, loanId);
        LoanReductionResult reduction = reducer.reduceCanonical(journal);
        if (reduction.type() != ReductionResultType.VALID || reduction.snapshot() == null) {
            return;
        }
        removeAll(ownerId, loanId);
        for (LoanMovement event : reduction.effectiveEvents()) {
            if (event.eventType() == LoanEventType.PAYMENT) {
                LoanPaymentProjection payment = toPaymentProjection(event);
                payments.put(payment.sourceEventId(), payment);
            }
        }
        LoanSummaryProjection summary = toSummaryProjection(reduction.snapshot(), paymentSummary(ownerId, loanId));
        summaries.put(key(ownerId, loanId), summary);
    }

    public List<LoanPaymentProjection> getPaymentProjections(String ownerId, String loanId) {
        return payments.values().stream()
            .filter(payment -> payment.ownerId().equals(ownerId) && payment.loanId().equals(loanId))
            .sorted(Comparator.comparingLong(LoanPaymentProjection::occurredAt))
            .toList();
    }

    public LoanSummaryProjection getSummaryProjection(String ownerId, String loanId) {
        return summaries.get(key(ownerId, loanId));
    }

    public List<LoanSummaryProjection> listSummaries(String ownerId, LoanSummaryFilter filter) {
        var comparator = switch (filter.sortBy()) {
            case COUNTERPARTY -> Comparator.comparing(LoanSummaryProjection::counterparty);
            case PRINCIPAL_CENTS -> Comparator.comparingLong(LoanSummaryProjection::principalCents);
            case PENDING_CENTS -> Comparator.comparingLong(LoanSummaryProjection::pendingCents);
            case LAST_ACTIVITY -> Comparator.comparingLong(LoanSummaryProjection::lastActivity);
        };
        if (!filter.ascending()) {
            comparator = comparator.reversed();
        }

        List<LoanSummaryProjection> result = summaries.values().stream()
            .filter(summary -> summary.ownerId().equals(ownerId))
            .filter(summary -> filter.loanType() == null || summary.loanType() == filter.loanType())
            .filter(summary -> filter.status() == null || summary.status() == filter.status())
            .sorted(comparator)
            .toList();

        if (filter.limit() != null && filter.limit() > 0) {
            return result.subList(0, Math.min(filter.limit(), result.size()));
        }
        return result;
    }

    private void removeAll(String ownerId, String loanId) {
        payments.values().removeIf(payment -> payment.ownerId().equals(ownerId) && payment.loanId().equals(loanId));
        summaries.remove(key(ownerId, loanId));
    }

    private static String key(String ownerId, String loanId) {
        return ownerId + "\u0000" + loanId;
    }

    private static LoanPaymentProjection toPaymentProjection(LoanMovement event) {
        LoanEventPayload.PaymentPayload payload = (LoanEventPayload.PaymentPayload) event.payload();
        LoanPaymentDirection direction = payload.legacyDirection() == null
            ? null
            : LoanPaymentDirection.valueOf(payload.legacyDirection().name());
        return new LoanPaymentProjection(
            event.eventId(),
            event.operationId(),
            event.ownerId(),
            event.loanId(),
            event.accountId(),
            event.transactionId(),
            event.occurredAt(),
            event.amountCents(),
            direction,
            event.note()
        );
    }

    private PaymentSummary paymentSummary(String ownerId, String loanId) {
        long lastPaymentAt = Long.MIN_VALUE;
        int count = 0;
        for (LoanPaymentProjection payment : payments.values()) {
            if (payment.ownerId().equals(ownerId) && payment.loanId().equals(loanId)) {
                count++;
                if (payment.occurredAt() > lastPaymentAt) {
                    lastPaymentAt = payment.occurredAt();
                }
            }
        }
        return new PaymentSummary(count, count == 0 ? null : lastPaymentAt);
    }

    private record PaymentSummary(int count, Long lastPaymentAt) {}

    private LoanSummaryProjection toSummaryProjection(LoanSnapshot snapshot, PaymentSummary paymentSummary) {
        int progressPercent = snapshot.principalCents() == 0
            ? 0
            : (int) (snapshot.totalPaidCents() * 100 / snapshot.principalCents());
        return new LoanSummaryProjection(
            snapshot.loanId(),
            snapshot.ownerId(),
            snapshot.counterpartyName(),
            snapshot.loanType(),
            snapshot.currency(),
            snapshot.defaultAccountId(),
            snapshot.notes(),
            snapshot.principalCents(),
            snapshot.totalPaidCents(),
            snapshot.pendingCents(),
            snapshot.overpaidCents(),
            paymentSummary.count(),
            paymentSummary.lastPaymentAt(),
            progressPercent,
            snapshot.status(),
            snapshot.closedAt(),
            snapshot.lastActivityAt(),
            snapshot.journalFingerprint()
        );
    }
}
