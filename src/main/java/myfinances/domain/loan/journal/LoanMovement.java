package myfinances.domain.loan.journal;

public record LoanMovement(
    String eventId,
    String operationId,
    String loanId,
    String ownerId,
    LoanEventType eventType,
    int eventSchemaVersion,
    Long amountCents,
    String accountId,
    String transactionId,
    String note,
    long occurredAt,
    long recordedAt,
    String actorId,
    String originId,
    LoanEventPayload payload
) {}
