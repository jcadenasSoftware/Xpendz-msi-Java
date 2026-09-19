package myfinances.domain.loan.projection;

public record LoanPaymentProjection(
    String sourceEventId,
    String operationId,
    String ownerId,
    String loanId,
    String accountId,
    String transactionId,
    long occurredAt,
    long amountCents,
    LoanPaymentDirection direction,
    String note
) {}
