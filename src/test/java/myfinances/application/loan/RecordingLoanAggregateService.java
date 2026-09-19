package myfinances.application.loan;

import myfinances.domain.loan.aggregate.LoanCommandResult;
import myfinances.domain.loan.commands.LoanCommand;
import myfinances.domain.loan.service.LoanAggregateService;

final class RecordingLoanAggregateService implements LoanAggregateService {
    private final LoanAggregateService delegate;
    private int callCount;

    RecordingLoanAggregateService(LoanAggregateService delegate) {
        this.delegate = delegate;
    }

    @Override
    public LoanCommandResult process(LoanCommand command) {
        callCount++;
        return delegate.process(command);
    }

    int callCount() {
        return callCount;
    }
}
