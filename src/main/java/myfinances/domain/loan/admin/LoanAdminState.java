package myfinances.domain.loan.admin;

public record LoanAdminState(
    String loanId,
    String ownerId,
    boolean archived,
    Long archivedAtEpochSec,
    long updatedAtEpochSec,
    String updatedBy
) {}
