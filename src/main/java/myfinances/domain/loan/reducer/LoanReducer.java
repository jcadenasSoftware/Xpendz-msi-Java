package myfinances.domain.loan.reducer;

import java.util.Collection;
import myfinances.domain.loan.journal.LoanJournalEntry;

import myfinances.domain.loan.journal.LoanMovement;

public interface LoanReducer {
    LoanReductionResult reduce(Collection<LoanJournalEntry> events);

    LoanReductionResult reduceCanonical(Collection<LoanMovement> events);
}
