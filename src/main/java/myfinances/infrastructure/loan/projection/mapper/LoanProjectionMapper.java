package myfinances.infrastructure.loan.projection.mapper;

import myfinances.domain.loan.journal.LoanEventPayload;
import myfinances.domain.loan.journal.LoanMovement;
import myfinances.domain.loan.journal.LoanType;
import myfinances.domain.loan.projection.LoanPaymentDirection;
import myfinances.domain.loan.projection.LoanPaymentProjection;
import myfinances.domain.loan.projection.LoanSummaryProjection;
import myfinances.domain.loan.snapshot.LoanSnapshot;
import myfinances.domain.loan.snapshot.LoanStatus;
import myfinances.infrastructure.loan.projection.model.LoanPaymentProjectionRecord;
import myfinances.infrastructure.loan.projection.model.LoanSummaryProjectionRecord;

public final class LoanProjectionMapper {
    public LoanPaymentProjectionRecord toRecord(LoanPaymentProjection projection) {
        return new LoanPaymentProjectionRecord(
            projection.sourceEventId(),
            projection.operationId(),
            projection.ownerId(),
            projection.loanId(),
            projection.accountId(),
            projection.transactionId(),
            projection.occurredAt(),
            projection.amountCents(),
            projection.direction() == null ? null : projection.direction().name(),
            projection.note()
        );
    }

    public LoanPaymentProjection toDomain(LoanPaymentProjectionRecord record) {
        return new LoanPaymentProjection(
            record.sourceEventId(),
            record.operationId(),
            record.ownerId(),
            record.loanId(),
            record.accountId(),
            record.transactionId(),
            record.occurredAt(),
            record.amountCents(),
            record.direction() == null ? null : LoanPaymentDirection.valueOf(record.direction()),
            record.note()
        );
    }

    public LoanPaymentProjection toPaymentProjection(LoanMovement event) {
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

    public LoanSummaryProjectionRecord toRecord(LoanSummaryProjection projection) {
        return new LoanSummaryProjectionRecord(
            projection.loanId(),
            projection.ownerId(),
            projection.counterparty(),
            projection.loanType().name(),
            projection.currency(),
            projection.defaultAccountId(),
            projection.notes(),
            projection.principalCents(),
            projection.totalPaidCents(),
            projection.pendingCents(),
            projection.overpaidCents(),
            projection.paymentCount(),
            projection.lastPaymentAt(),
            projection.progressPercent(),
            projection.status().name(),
            projection.closedAt(),
            projection.lastActivity(),
            projection.journalFingerprint()
        );
    }

    public LoanSummaryProjection toDomain(LoanSummaryProjectionRecord record) {
        return new LoanSummaryProjection(
            record.loanId(),
            record.ownerId(),
            record.counterparty(),
            LoanType.valueOf(record.loanType()),
            record.currency(),
            record.defaultAccountId(),
            record.notes(),
            record.principalCents(),
            record.totalPaidCents(),
            record.pendingCents(),
            record.overpaidCents(),
            record.paymentCount(),
            record.lastPaymentAt(),
            record.progressPercent(),
            LoanStatus.valueOf(record.status()),
            record.closedAt(),
            record.lastActivity(),
            record.journalFingerprint()
        );
    }

    public LoanSummaryProjection toSummaryProjection(LoanSnapshot snapshot, int paymentCount, Long lastPaymentAt) {
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
            paymentCount,
            lastPaymentAt,
            progressPercent,
            snapshot.status(),
            snapshot.closedAt(),
            snapshot.lastActivityAt(),
            snapshot.journalFingerprint()
        );
    }
}
