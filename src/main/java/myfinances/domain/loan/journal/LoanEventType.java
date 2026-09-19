package myfinances.domain.loan.journal;

public enum LoanEventType {
    CREATION,
    TOPUP,
    PAYMENT,
    ADJUSTMENT,
    METADATA_CHANGED,
    REVERSAL,
    CLOSE
}
