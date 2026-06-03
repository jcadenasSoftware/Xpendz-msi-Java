package com.myfinaces.db;

public enum TransactionKind {
    INCOME,
    EXPENSE,
    LOAN_LENT_OUT,
    LOAN_BORROWED_IN,
    LOAN_LENT_TOPUP,
    LOAN_BORROWED_TOPUP,
    LOAN_LENT_CORRECTION,
    LOAN_BORROWED_CORRECTION,
    LOAN_LENT_CORRECTION_IN,
    LOAN_LENT_CORRECTION_OUT,
    LOAN_BORROWED_CORRECTION_IN,
    LOAN_BORROWED_CORRECTION_OUT,
    LOAN_REPAYMENT_PRINCIPAL_IN,
    LOAN_REPAYMENT_PRINCIPAL_OUT;

    public static boolean affectsCashIn(TransactionKind k) {
        return k == INCOME
            || k == LOAN_BORROWED_IN
            || k == LOAN_BORROWED_TOPUP
            || k == LOAN_BORROWED_CORRECTION
            || k == LOAN_LENT_CORRECTION_IN
            || k == LOAN_BORROWED_CORRECTION_IN
            || k == LOAN_REPAYMENT_PRINCIPAL_IN;
    }

    public static boolean affectsCashOut(TransactionKind k) {
        return k == EXPENSE
            || k == LOAN_LENT_OUT
            || k == LOAN_LENT_TOPUP
            || k == LOAN_LENT_CORRECTION
            || k == LOAN_LENT_CORRECTION_OUT
            || k == LOAN_BORROWED_CORRECTION_OUT
            || k == LOAN_REPAYMENT_PRINCIPAL_OUT;
    }
}
