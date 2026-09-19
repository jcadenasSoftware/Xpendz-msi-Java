package myfinances.infrastructure.loan.model;

public record LoanSnapshotRecord(
    String loanId,
    String ownerId,
    String loanType,
    String counterpartyName,
    String currency,
    String defaultAccountId,
    String notes,
    long principalCents,
    long totalPaidCents,
    long netBalanceCents,
    long pendingCents,
    long overpaidCents,
    String status,
    Long closedAt,
    long lastActivityAt,
    int journalEventCount,
    String journalFingerprint,
    int reducerVersion
) {}
