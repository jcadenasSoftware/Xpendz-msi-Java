package myfinances.domain.loan.projection;

public enum LoanProjectionChangeType {
    NONE,
    ADD_PAYMENT_PROJECTION,
    REMOVE_PAYMENT_PROJECTION,
    REBUILD_LOAN_SNAPSHOT
}
