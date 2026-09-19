package myfinances.application.loan;

import java.util.UUID;
import myfinances.domain.loan.commands.AddPrincipalCommand;
import myfinances.domain.loan.commands.AdjustPrincipalCommand;
import myfinances.domain.loan.commands.CloseLoanCommand;
import myfinances.domain.loan.commands.CreateLoanCommand;
import myfinances.domain.loan.commands.FieldChange;
import myfinances.domain.loan.commands.LoanCommandEnvelope;
import myfinances.domain.loan.commands.LoanCommandType;
import myfinances.domain.loan.commands.RegisterPaymentCommand;
import myfinances.domain.loan.commands.ReversePaymentCommand;
import myfinances.domain.loan.commands.UpdateMetadataCommand;
import myfinances.domain.loan.journal.LoanType;

public final class LoanCommandFactory {

    public CreateLoanCommand createLoan(
        String ownerId,
        LoanType loanType,
        String counterpartyName,
        String currency,
        String defaultAccountId,
        long initialPrincipalCents,
        long occurredAt,
        String transactionId,
        String notes
    ) {
        String loanId = UUID.randomUUID().toString();
        LoanCommandEnvelope envelope = buildEnvelope(
            LoanCommandType.CREATE_LOAN,
            ownerId,
            loanId,
            null,
            occurredAt
        );
        return new CreateLoanCommand(
            envelope,
            loanType,
            initialPrincipalCents,
            counterpartyName,
            currency,
            defaultAccountId,
            transactionId,
            notes
        );
    }

    public RegisterPaymentCommand registerPayment(
        String ownerId,
        String loanId,
        String expectedJournalFingerprint,
        long amountCents,
        String accountId,
        String transactionId,
        String note
    ) {
        LoanCommandEnvelope envelope = buildEnvelope(
            LoanCommandType.REGISTER_PAYMENT,
            ownerId,
            loanId,
            expectedJournalFingerprint,
            System.currentTimeMillis() / 1000
        );
        return new RegisterPaymentCommand(
            envelope,
            amountCents,
            accountId,
            transactionId,
            note
        );
    }

    public ReversePaymentCommand reversePayment(
        String ownerId,
        String loanId,
        String paymentEventId,
        String expectedJournalFingerprint,
        String reason,
        String note
    ) {
        LoanCommandEnvelope envelope = buildEnvelope(
            LoanCommandType.REVERSE_PAYMENT,
            ownerId,
            loanId,
            expectedJournalFingerprint,
            System.currentTimeMillis() / 1000
        );
        return new ReversePaymentCommand(
            envelope,
            paymentEventId,
            reason,
            note
        );
    }

    public UpdateMetadataCommand updateLoanMetadata(
        String ownerId,
        String loanId,
        String expectedJournalFingerprint,
        FieldChange<String> counterpartyName,
        FieldChange<String> defaultAccountId,
        FieldChange<String> notes
    ) {
        LoanCommandEnvelope envelope = buildEnvelope(
            LoanCommandType.UPDATE_METADATA,
            ownerId,
            loanId,
            expectedJournalFingerprint,
            System.currentTimeMillis() / 1000
        );
        return new UpdateMetadataCommand(
            envelope,
            counterpartyName,
            defaultAccountId,
            notes
        );
    }

    public AdjustPrincipalCommand adjustPrincipal(
        String ownerId,
        String loanId,
        String expectedJournalFingerprint,
        long deltaCents,
        String reason,
        String accountId,
        String transactionId,
        String note
    ) {
        LoanCommandEnvelope envelope = buildEnvelope(
            LoanCommandType.ADJUST_PRINCIPAL,
            ownerId,
            loanId,
            expectedJournalFingerprint,
            System.currentTimeMillis() / 1000
        );
        return new AdjustPrincipalCommand(
            envelope,
            deltaCents,
            reason,
            accountId,
            transactionId,
            note
        );
    }

    /**
     * @deprecated Misleading API: administrative archiving uses LoanAdminStateRepository.
     */
    @Deprecated
    public CloseLoanCommand archiveLoan(
        String ownerId,
        String loanId,
        String expectedJournalFingerprint,
        String reason,
        String note
    ) {
        LoanCommandEnvelope envelope = buildEnvelope(
            LoanCommandType.CLOSE_LOAN,
            ownerId,
            loanId,
            expectedJournalFingerprint,
            System.currentTimeMillis() / 1000
        );
        return new CloseLoanCommand(
            envelope,
            reason,
            note
        );
    }

    public AddPrincipalCommand addPrincipal(
        String ownerId,
        String loanId,
        String expectedJournalFingerprint,
        long amountCents,
        String accountId,
        String transactionId,
        String note
    ) {
        LoanCommandEnvelope envelope = buildEnvelope(
            LoanCommandType.ADD_PRINCIPAL,
            ownerId,
            loanId,
            expectedJournalFingerprint,
            System.currentTimeMillis() / 1000
        );
        return new AddPrincipalCommand(
            envelope,
            amountCents,
            accountId,
            transactionId,
            note
        );
    }

    private LoanCommandEnvelope buildEnvelope(
        LoanCommandType commandType,
        String ownerId,
        String loanId,
        String expectedJournalFingerprint,
        long occurredAt
    ) {
        String operationId = UUID.randomUUID().toString();
        return new LoanCommandEnvelope(
            commandType,
            operationId,
            loanId,
            ownerId,
            expectedJournalFingerprint,
            occurredAt,
            ownerId,
            ownerId
        );
    }
}
