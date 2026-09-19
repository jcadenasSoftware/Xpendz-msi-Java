package myfinances.infrastructure.loan.mapper;

import myfinances.domain.loan.journal.LoanEventPayload;
import myfinances.domain.loan.journal.LoanEventType;
import myfinances.domain.loan.journal.LoanMovement;
import myfinances.domain.loan.journal.LoanType;
import myfinances.domain.loan.snapshot.LoanSnapshot;
import myfinances.domain.loan.snapshot.LoanStatus;
import myfinances.infrastructure.loan.model.LoanEventRecord;
import myfinances.infrastructure.loan.model.LoanSnapshotRecord;

public final class LoanInfrastructureMapper {
    public LoanEventRecord toRecord(LoanMovement event) {
        String payloadLoanType = null;
        String payloadCounterpartyName = null;
        String payloadCurrency = null;
        String payloadDefaultAccountId = null;
        String payloadNotes = null;
        String payloadLegacyDirection = null;
        String payloadLegacySource = null;
        String payloadReason = null;
        String payloadTargetEventId = null;
        boolean metadataCounterpartyPresent = false;
        String metadataCounterpartyValue = null;
        boolean metadataAccountPresent = false;
        String metadataAccountValue = null;
        boolean metadataNotesPresent = false;
        String metadataNotesValue = null;

        switch (event.payload()) {
            case LoanEventPayload.EmptyPayload ignored -> {
            }
            case LoanEventPayload.CreationPayload payload -> {
                payloadLoanType = payload.loanType().name();
                payloadCounterpartyName = payload.counterpartyName();
                payloadCurrency = payload.currency();
                payloadDefaultAccountId = payload.defaultAccountId();
                payloadNotes = payload.notes();
            }
            case LoanEventPayload.PaymentPayload payload -> {
                payloadLegacyDirection = payload.legacyDirection() == null ? null : payload.legacyDirection().name();
                payloadLegacySource = payload.legacySource();
            }
            case LoanEventPayload.AdjustmentPayload payload -> {
                payloadReason = payload.reason();
                payloadLegacySource = payload.legacySource();
            }
            case LoanEventPayload.MetadataChangedPayload payload -> {
                payloadLegacySource = payload.legacySource();
                metadataCounterpartyPresent = payload.changes().counterpartyName().present();
                metadataCounterpartyValue = payload.changes().counterpartyName().value();
                metadataAccountPresent = payload.changes().defaultAccountId().present();
                metadataAccountValue = payload.changes().defaultAccountId().value();
                metadataNotesPresent = payload.changes().notes().present();
                metadataNotesValue = payload.changes().notes().value();
            }
            case LoanEventPayload.ReversalPayload payload -> {
                payloadTargetEventId = payload.targetEventId();
                payloadReason = payload.reason();
            }
            case LoanEventPayload.ClosePayload payload -> payloadReason = payload.reason();
        }

        return new LoanEventRecord(
            event.eventId(),
            event.operationId(),
            event.loanId(),
            event.ownerId(),
            event.eventType().name(),
            event.eventSchemaVersion(),
            event.amountCents(),
            event.accountId(),
            event.transactionId(),
            event.note(),
            event.occurredAt(),
            event.recordedAt(),
            event.actorId(),
            event.originId(),
            payloadLoanType,
            payloadCounterpartyName,
            payloadCurrency,
            payloadDefaultAccountId,
            payloadNotes,
            payloadLegacyDirection,
            payloadLegacySource,
            payloadReason,
            payloadTargetEventId,
            metadataCounterpartyPresent,
            metadataCounterpartyValue,
            metadataAccountPresent,
            metadataAccountValue,
            metadataNotesPresent,
            metadataNotesValue
        );
    }

    public LoanMovement toDomain(LoanEventRecord record) {
        LoanEventType eventType = LoanEventType.valueOf(record.eventType());
        LoanEventPayload payload = switch (eventType) {
            case CREATION -> new LoanEventPayload.CreationPayload(
                LoanType.valueOf(record.payloadLoanType()),
                record.payloadCounterpartyName(),
                record.payloadCurrency(),
                record.payloadDefaultAccountId(),
                record.payloadNotes()
            );
            case TOPUP -> new LoanEventPayload.EmptyPayload();
            case PAYMENT -> new LoanEventPayload.PaymentPayload(
                record.payloadLegacyDirection() == null
                    ? null
                    : LoanEventPayload.LegacyPaymentDirection.valueOf(record.payloadLegacyDirection()),
                record.payloadLegacySource()
            );
            case ADJUSTMENT -> new LoanEventPayload.AdjustmentPayload(
                record.payloadReason(),
                record.payloadLegacySource()
            );
            case METADATA_CHANGED -> new LoanEventPayload.MetadataChangedPayload(
                new LoanEventPayload.MetadataChanges(
                    new LoanEventPayload.FieldValue<>(
                        record.metadataCounterpartyPresent(),
                        record.metadataCounterpartyValue()
                    ),
                    new LoanEventPayload.FieldValue<>(
                        record.metadataAccountPresent(),
                        record.metadataAccountValue()
                    ),
                    new LoanEventPayload.FieldValue<>(
                        record.metadataNotesPresent(),
                        record.metadataNotesValue()
                    )
                ),
                record.payloadLegacySource()
            );
            case REVERSAL -> new LoanEventPayload.ReversalPayload(
                record.payloadTargetEventId(),
                record.payloadReason()
            );
            case CLOSE -> new LoanEventPayload.ClosePayload(record.payloadReason());
        };

        return new LoanMovement(
            record.eventId(),
            record.operationId(),
            record.loanId(),
            record.ownerId(),
            eventType,
            record.eventSchemaVersion(),
            record.amountCents(),
            record.accountId(),
            record.transactionId(),
            record.note(),
            record.occurredAt(),
            record.recordedAt(),
            record.actorId(),
            record.originId(),
            payload
        );
    }

    public LoanSnapshotRecord toRecord(LoanSnapshot snapshot) {
        return new LoanSnapshotRecord(
            snapshot.loanId(),
            snapshot.ownerId(),
            snapshot.loanType().name(),
            snapshot.counterpartyName(),
            snapshot.currency(),
            snapshot.defaultAccountId(),
            snapshot.notes(),
            snapshot.principalCents(),
            snapshot.totalPaidCents(),
            snapshot.netBalanceCents(),
            snapshot.pendingCents(),
            snapshot.overpaidCents(),
            snapshot.status().name(),
            snapshot.closedAt(),
            snapshot.lastActivityAt(),
            snapshot.journalEventCount(),
            snapshot.journalFingerprint(),
            snapshot.reducerVersion()
        );
    }

    public LoanSnapshot toDomain(LoanSnapshotRecord record) {
        return new LoanSnapshot(
            record.loanId(),
            record.ownerId(),
            LoanType.valueOf(record.loanType()),
            record.counterpartyName(),
            record.currency(),
            record.defaultAccountId(),
            record.notes(),
            record.principalCents(),
            record.totalPaidCents(),
            record.netBalanceCents(),
            record.pendingCents(),
            record.overpaidCents(),
            LoanStatus.valueOf(record.status()),
            record.closedAt(),
            record.lastActivityAt(),
            record.journalEventCount(),
            record.journalFingerprint(),
            record.reducerVersion()
        );
    }
}
