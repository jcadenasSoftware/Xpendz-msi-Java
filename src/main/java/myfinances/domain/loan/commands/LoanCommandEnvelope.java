package myfinances.domain.loan.commands;

public record LoanCommandEnvelope(
    LoanCommandType commandType,
    String operationId,
    String loanId,
    String ownerId,
    String expectedJournalFingerprint,
    long occurredAt,
    String actorId,
    String originId
) {}
