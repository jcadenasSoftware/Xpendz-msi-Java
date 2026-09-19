package com.myfinaces.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LoanTransactionPolicyTest {

    @Test
    void allLoanKindsAreProtected() {
        String[] loanKinds = {
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
        };
        for (String kind : loanKinds) {
            assertTrue(LoanTransactionPolicy.isLoanKind(kind), kind + " debe estar protegido");
        }
    }

    @Test
    void normalKindsAreNotProtected() {
        String[] kinds = { null, "", "INCOME", "EXPENSE", "TRANSFER" };
        for (String kind : kinds) {
            assertFalse(LoanTransactionPolicy.isLoanKind(kind), String.valueOf(kind) + " no debe estar protegido");
        }
    }

    @Test
    void protectedMessageIsClear() {
        assertTrue(LoanTransactionPolicy.protectedMessage().toLowerCase().contains("préstamo"));
    }
}
