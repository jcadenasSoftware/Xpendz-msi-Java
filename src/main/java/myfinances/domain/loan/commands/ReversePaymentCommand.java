package myfinances.domain.loan.commands;

public record ReversePaymentCommand(
    LoanCommandEnvelope envelope,
    String targetPaymentEventId,
    String reason,
    String note
) implements LoanCommand {}
