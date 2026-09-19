package myfinances.domain.loan.commands;

public sealed interface LoanCommand permits
    CreateLoanCommand,
    RegisterPaymentCommand,
    AddPrincipalCommand,
    AdjustPrincipalCommand,
    UpdateMetadataCommand,
    ReversePaymentCommand,
    CloseLoanCommand {

    LoanCommandEnvelope envelope();
}
