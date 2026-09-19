package myfinances.domain.loan.projection;

import myfinances.domain.loan.aggregate.LoanCommandResult;
import myfinances.domain.loan.reducer.LoanReducer;
import myfinances.domain.loan.repository.LoanRepository;

public interface LoanProjector {
    void project(LoanCommandResult result);

    void rebuild(String ownerId, String loanId, LoanRepository repository, LoanReducer reducer);
}
