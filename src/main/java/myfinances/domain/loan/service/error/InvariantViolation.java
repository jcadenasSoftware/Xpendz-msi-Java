package myfinances.domain.loan.service.error;

import java.util.List;
import myfinances.domain.loan.diagnostics.LoanDiagnostic;

public final class InvariantViolation extends LoanAggregateException {
    public InvariantViolation(LoanAggregateErrorCode code) {
        this(code, List.of());
    }

    public InvariantViolation(LoanAggregateErrorCode code, List<LoanDiagnostic> diagnostics) {
        super(code, diagnostics, null);
    }
}
