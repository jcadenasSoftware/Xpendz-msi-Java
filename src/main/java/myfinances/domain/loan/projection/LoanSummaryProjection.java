package myfinances.domain.loan.projection;

import myfinances.domain.loan.journal.LoanType;
import myfinances.domain.loan.snapshot.LoanStatus;

public record LoanSummaryProjection(
    String loanId,
    String ownerId,
    String counterparty,
    LoanType loanType,
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
    LoanStatus status,
    Long closedAt,
    long lastActivity,
    String journalFingerprint
) {}
