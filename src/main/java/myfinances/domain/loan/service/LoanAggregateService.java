package myfinances.domain.loan.service;

import myfinances.domain.loan.aggregate.LoanCommandResult;
import myfinances.domain.loan.commands.LoanCommand;

public interface LoanAggregateService {
    LoanCommandResult process(LoanCommand command);
}
