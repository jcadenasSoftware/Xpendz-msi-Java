package myfinances.domain.loan.commands;

public record UpdateMetadataCommand(
    LoanCommandEnvelope envelope,
    FieldChange<String> counterpartyName,
    FieldChange<String> defaultAccountId,
    FieldChange<String> notes
) implements LoanCommand {}
