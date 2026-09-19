package myfinances.domain.loan.commands;

public record AddPrincipalCommand(
    LoanCommandEnvelope envelope,
    long amountCents,
    String accountId,
    String transactionId,
    String note
) implements LoanCommand {}
