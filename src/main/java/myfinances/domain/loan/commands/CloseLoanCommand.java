package myfinances.domain.loan.commands;

public record CloseLoanCommand(
    LoanCommandEnvelope envelope,
    String reason,
    String note
) implements LoanCommand {}
