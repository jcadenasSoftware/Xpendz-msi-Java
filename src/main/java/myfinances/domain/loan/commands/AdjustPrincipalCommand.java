package myfinances.domain.loan.commands;

public record AdjustPrincipalCommand(
    LoanCommandEnvelope envelope,
    long deltaCents,
    String reason,
    String accountId,
    String transactionId,
    String note
) implements LoanCommand {}
