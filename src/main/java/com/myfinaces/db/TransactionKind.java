package com.myfinaces.db;

public enum TransactionKind {
    INCOME,
    EXPENSE,
    LOAN_LENT_OUT,
    LOAN_BORROWED_IN,
    LOAN_REPAYMENT_PRINCIPAL_IN,
    LOAN_REPAYMENT_PRINCIPAL_OUT;

    public static boolean affectsCashIn(TransactionKind k) {
        return k == INCOME || k == LOAN_BORROWED_IN || k == LOAN_REPAYMENT_PRINCIPAL_IN;
    }

    public static boolean affectsCashOut(TransactionKind k) {
        return k == EXPENSE || k == LOAN_LENT_OUT || k == LOAN_REPAYMENT_PRINCIPAL_OUT;
    }
}
