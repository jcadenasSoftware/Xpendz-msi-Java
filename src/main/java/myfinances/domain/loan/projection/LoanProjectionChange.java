package myfinances.domain.loan.projection;

public record LoanProjectionChange(
    LoanProjectionChangeType type,
    String sourceEventId
) {}
