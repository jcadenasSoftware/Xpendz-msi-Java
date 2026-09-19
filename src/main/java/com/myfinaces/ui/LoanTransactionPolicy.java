package com.myfinaces.ui;

import java.util.Set;

public final class LoanTransactionPolicy {

    private static final Set<String> LOAN_KINDS = Set.of(
        "LOAN_LENT_OUT",
        "LOAN_BORROWED_IN",
        "LOAN_LENT_TOPUP",
        "LOAN_BORROWED_TOPUP",
        "LOAN_LENT_CORRECTION",
        "LOAN_BORROWED_CORRECTION",
        "LOAN_LENT_CORRECTION_IN",
        "LOAN_LENT_CORRECTION_OUT",
        "LOAN_BORROWED_CORRECTION_IN",
        "LOAN_BORROWED_CORRECTION_OUT",
        "LOAN_REPAYMENT_PRINCIPAL_IN",
        "LOAN_REPAYMENT_PRINCIPAL_OUT"
    );

    private LoanTransactionPolicy() {}

    public static boolean isLoanKind(String kind) {
        return kind != null && LOAN_KINDS.contains(kind.trim().toUpperCase());
    }

    public static String protectedMessage() {
        return "Esta transacción pertenece a un préstamo. Modifícala desde el módulo Préstamos.";
    }
}
