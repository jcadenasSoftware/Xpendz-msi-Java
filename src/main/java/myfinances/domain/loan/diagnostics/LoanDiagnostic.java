package myfinances.domain.loan.diagnostics;

public record LoanDiagnostic(
    LoanDiagnosticCode code,
    String primaryEventId,
    String relatedEventId
) {
    public LoanDiagnosticCategory category() {
        return code.category();
    }
}
