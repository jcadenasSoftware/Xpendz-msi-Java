package myfinances.domain.loan.commands;

import myfinances.domain.loan.journal.LoanType;

public record CreateLoanCommand(
    LoanCommandEnvelope envelope,
    LoanType loanType,
    long initialPrincipalCents,
    String counterpartyName,
    String currency,
    String defaultAccountId,
    String transactionId,
    String notes
) implements LoanCommand {}
