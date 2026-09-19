package myfinances.domain.loan.reducer;

import java.util.List;
import myfinances.domain.loan.diagnostics.LoanDiagnostic;
import myfinances.domain.loan.journal.LoanMovement;
import myfinances.domain.loan.snapshot.LoanSnapshot;

public record LoanReductionResult(
    ReductionResultType type,
    LoanSnapshot snapshot,
    List<LoanMovement> normalizedJournal,
    List<LoanDiagnostic> diagnostics,
    List<LoanMovement> effectiveEvents,
    List<LoanMovement> revertedEvents,
    List<LoanMovement> discardedDuplicates
) {}
