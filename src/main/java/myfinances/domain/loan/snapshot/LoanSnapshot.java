package myfinances.domain.loan.snapshot;

import myfinances.domain.loan.journal.LoanType;

public record LoanSnapshot(
    String loanId,
    String ownerId,
    LoanType loanType,
    String counterpartyName,
    String currency,
    String defaultAccountId,
    String notes,
    long principalCents,
    long totalPaidCents,
    long netBalanceCents,
    long pendingCents,
    long overpaidCents,
    LoanStatus status,
    Long closedAt,
    long lastActivityAt,
    int journalEventCount,
    String journalFingerprint,
    int reducerVersion
) {}
