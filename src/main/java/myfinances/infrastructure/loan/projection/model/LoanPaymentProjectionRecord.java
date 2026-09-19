package myfinances.infrastructure.loan.projection.model;

public record LoanPaymentProjectionRecord(
    String sourceEventId,
    String operationId,
    String ownerId,
    String loanId,
    String accountId,
    String transactionId,
    long occurredAt,
    long amountCents,
    String direction,
    String note
) {}
