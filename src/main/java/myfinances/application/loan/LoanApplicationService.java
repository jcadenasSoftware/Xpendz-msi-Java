package myfinances.application.loan;

import myfinances.domain.loan.aggregate.LoanCommandResult;
import myfinances.domain.loan.aggregate.Outcome;
import myfinances.domain.loan.commands.LoanCommand;
import myfinances.domain.loan.projection.LoanProjector;
import myfinances.domain.loan.service.LoanAggregateService;

public final class LoanApplicationService {
    private final LoanAggregateService aggregate;
    private final LoanProjector projector;

    public LoanApplicationService(LoanAggregateService aggregate, LoanProjector projector) {
        this.aggregate = aggregate;
        this.projector = projector;
    }

    public LoanCommandResult process(LoanCommand command) {
        LoanCommandResult result = aggregate.process(command);
        if (result.outcome() == Outcome.APPLIED) {
            projector.project(result);
        }
        return result;
    }
}
