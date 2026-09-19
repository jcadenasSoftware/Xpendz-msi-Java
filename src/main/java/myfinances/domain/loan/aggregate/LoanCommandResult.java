package myfinances.domain.loan.aggregate;

import java.util.List;
import myfinances.domain.loan.commands.LoanCommandType;
import myfinances.domain.loan.diagnostics.LoanDiagnostic;
import myfinances.domain.loan.journal.LoanMovement;
import myfinances.domain.loan.projection.LoanProjectionChange;
import myfinances.domain.loan.snapshot.LoanSnapshot;

public record LoanCommandResult(
    Outcome outcome,
    String operationId,
    LoanCommandType commandType,
    LoanMovement event,
    LoanSnapshot previousSnapshot,
    LoanSnapshot currentSnapshot,
    List<LoanProjectionChange> projectionChanges,
    List<LoanDiagnostic> diagnostics
) {}
