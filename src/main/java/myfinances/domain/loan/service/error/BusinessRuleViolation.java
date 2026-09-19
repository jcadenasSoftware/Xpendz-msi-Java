package myfinances.domain.loan.service.error;

import java.util.List;

public final class BusinessRuleViolation extends LoanAggregateException {
    public BusinessRuleViolation(LoanAggregateErrorCode code) {
        super(code, List.of(), null);
    }
}
