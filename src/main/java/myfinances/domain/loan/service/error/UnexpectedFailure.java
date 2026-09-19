package myfinances.domain.loan.service.error;

import java.util.List;

public final class UnexpectedFailure extends LoanAggregateException {
    public UnexpectedFailure(Throwable cause) {
        super(LoanAggregateErrorCode.UNEXPECTED_FAILURE, List.of(), cause);
    }
}
