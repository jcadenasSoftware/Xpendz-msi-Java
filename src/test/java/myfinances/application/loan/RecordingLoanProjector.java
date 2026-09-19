package myfinances.application.loan;

import myfinances.domain.loan.aggregate.LoanCommandResult;
import myfinances.domain.loan.projection.LoanProjector;
import myfinances.domain.loan.reducer.LoanReducer;
import myfinances.domain.loan.repository.LoanRepository;

final class RecordingLoanProjector implements LoanProjector {
    private final LoanProjector delegate;
    private int projectCallCount;
    private int rebuildCallCount;

    RecordingLoanProjector(LoanProjector delegate) {
        this.delegate = delegate;
    }

    @Override
    public void project(LoanCommandResult result) {
        projectCallCount++;
        delegate.project(result);
    }

    @Override
    public void rebuild(String ownerId, String loanId, LoanRepository repository, LoanReducer reducer) {
        rebuildCallCount++;
        delegate.rebuild(ownerId, loanId, repository, reducer);
    }

    int projectCallCount() {
        return projectCallCount;
    }

    int rebuildCallCount() {
        return rebuildCallCount;
    }
}
