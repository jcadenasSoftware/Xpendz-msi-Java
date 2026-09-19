package myfinances.domain.loan.service.error;

import java.util.List;
import myfinances.domain.loan.diagnostics.LoanDiagnostic;

public sealed class LoanAggregateException extends RuntimeException permits
    ValidationError,
    BusinessRuleViolation,
    InvariantViolation,
    UnexpectedFailure {

    private final LoanAggregateErrorCode code;
    private final List<LoanDiagnostic> diagnostics;

    LoanAggregateException(
        LoanAggregateErrorCode code,
        List<LoanDiagnostic> diagnostics,
        Throwable cause
    ) {
        super(code.name(), cause);
        this.code = code;
        this.diagnostics = List.copyOf(diagnostics);
    }

    public LoanAggregateErrorCode code() {
        return code;
    }

    public List<LoanDiagnostic> diagnostics() {
        return diagnostics;
    }
}
