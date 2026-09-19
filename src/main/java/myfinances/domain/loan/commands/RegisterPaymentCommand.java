package myfinances.domain.loan.commands;

public record RegisterPaymentCommand(
    LoanCommandEnvelope envelope,
    long amountCents,
    String accountId,
    String transactionId,
    String note
) implements LoanCommand {}
