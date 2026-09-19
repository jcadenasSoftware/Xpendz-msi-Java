package myfinances.domain.loan.diagnostics;

public enum LoanDiagnosticCode {
    MISSING_CREATION(LoanDiagnosticCategory.INVALIDATING_ERROR),
    MULTIPLE_CREATIONS(LoanDiagnosticCategory.INVALIDATING_ERROR),
    EVENT_BEFORE_CREATION(LoanDiagnosticCategory.INVALIDATING_ERROR),
    MIXED_AGGREGATES(LoanDiagnosticCategory.INVALIDATING_ERROR),
    EVENT_ID_CONFLICT(LoanDiagnosticCategory.INVALIDATING_ERROR),
    OPERATION_CONFLICT(LoanDiagnosticCategory.INVALIDATING_ERROR),
    NON_POSITIVE_PRINCIPAL(LoanDiagnosticCategory.INVALIDATING_ERROR),
    NON_REVERSIBLE_TARGET(LoanDiagnosticCategory.INVALIDATING_ERROR),
    CROSS_LOAN_REVERSAL(LoanDiagnosticCategory.INVALIDATING_ERROR),
    MULTIPLE_REVERSALS(LoanDiagnosticCategory.INVALIDATING_ERROR),
    ARITHMETIC_OVERFLOW(LoanDiagnosticCategory.INVALIDATING_ERROR),
    INVALID_EVENT_PAYLOAD(LoanDiagnosticCategory.INVALIDATING_ERROR),
    UNRESOLVED_REVERSAL(LoanDiagnosticCategory.INCOMPLETE_STATE),
    UNSUPPORTED_EVENT_TYPE(LoanDiagnosticCategory.INCOMPLETE_STATE),
    UNSUPPORTED_EVENT_VERSION(LoanDiagnosticCategory.INCOMPLETE_STATE),
    OVERPAYMENT(LoanDiagnosticCategory.WARNING),
    PREMATURE_CLOSE(LoanDiagnosticCategory.WARNING),
    MISSING_LINKED_TRANSACTION(LoanDiagnosticCategory.WARNING),
    LEGACY_DIRECTION_MISMATCH(LoanDiagnosticCategory.WARNING),
    ORPHAN_PAYMENT_PROJECTION(LoanDiagnosticCategory.WARNING),
    PROJECTION_MISMATCH(LoanDiagnosticCategory.WARNING),
    AMBIGUOUS_LEGACY_MATCH(LoanDiagnosticCategory.WARNING);

    private final LoanDiagnosticCategory category;

    LoanDiagnosticCode(LoanDiagnosticCategory category) {
        this.category = category;
    }

    public LoanDiagnosticCategory category() {
        return category;
    }
}
