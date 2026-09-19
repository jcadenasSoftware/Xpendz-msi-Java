package myfinances.infrastructure.loan.projection.model;

public record LoanSummaryProjectionRecord(
    String loanId,
    String ownerId,
    String counterparty,
    String loanType,
    String currency,
    String defaultAccountId,
    String notes,
    long principalCents,
    long totalPaidCents,
    long pendingCents,
    long overpaidCents,
    int paymentCount,
    Long lastPaymentAt,
    int progressPercent,
    String status,
    Long closedAt,
    long lastActivity,
    String journalFingerprint
) {}
