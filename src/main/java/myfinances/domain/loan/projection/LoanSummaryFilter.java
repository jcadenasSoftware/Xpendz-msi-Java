package myfinances.domain.loan.projection;

import myfinances.domain.loan.journal.LoanType;
import myfinances.domain.loan.snapshot.LoanStatus;

public record LoanSummaryFilter(
    LoanType loanType,
    LoanStatus status,
    SortBy sortBy,
    boolean ascending,
    Integer limit
) {}
