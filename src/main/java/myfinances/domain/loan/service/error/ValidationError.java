package myfinances.domain.loan.service.error;

import java.util.List;

public final class ValidationError extends LoanAggregateException {
    public ValidationError(LoanAggregateErrorCode code) {
        super(code, List.of(), null);
    }
}
