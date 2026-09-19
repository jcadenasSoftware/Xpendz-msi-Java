package myfinances.application.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import myfinances.domain.loan.commands.AddPrincipalCommand;
import myfinances.domain.loan.commands.AdjustPrincipalCommand;
import myfinances.domain.loan.commands.CreateLoanCommand;
import myfinances.domain.loan.commands.LoanCommandType;
import myfinances.domain.loan.commands.RegisterPaymentCommand;
import myfinances.domain.loan.commands.ReversePaymentCommand;
import myfinances.domain.loan.journal.LoanType;
import org.junit.jupiter.api.Test;

class LoanCommandFactoryTest {

    private final LoanCommandFactory factory = new LoanCommandFactory();

    @Test
    void createLoanBuildsCanonicalCommand() {
        CreateLoanCommand command = factory.createLoan(
            "owner-1",
            LoanType.LENT,
            "Ana",
            "USD",
            "A1",
            100_000,
            1_000,
            "tx-1",
            "Inicial"
        );

        assertEquals(LoanCommandType.CREATE_LOAN, command.envelope().commandType());
        assertEquals(LoanType.LENT, command.loanType());
        assertEquals("Ana", command.counterpartyName());
        assertEquals("USD", command.currency());
        assertEquals("A1", command.defaultAccountId());
        assertEquals(100_000, command.initialPrincipalCents());
        assertEquals("Inicial", command.notes());
        assertEquals("tx-1", command.transactionId());

        assertNotNull(command.envelope().operationId());
        assertNotNull(command.envelope().loanId());
        assertTrue(
            command.envelope().operationId().matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
            ),
            "operationId debe ser un UUID v4 canónico"
        );
        assertTrue(
            command.envelope().loanId().matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
            ),
            "loanId debe ser un UUID v4 canónico"
        );
        assertEquals("owner-1", command.envelope().actorId());
        assertEquals("owner-1", command.envelope().originId());
        assertEquals("owner-1", command.envelope().ownerId());
        assertNull(command.envelope().expectedJournalFingerprint());
        assertEquals(1_000, command.envelope().occurredAt());
    }

    @Test
    void registerPaymentBuildsCanonicalCommand() {
        RegisterPaymentCommand command = factory.registerPayment(
            "owner-1",
            "loan-1",
            "fingerprint-1",
            50_000,
            "A1",
            null,
            "Pago parcial"
        );

        assertEquals(LoanCommandType.REGISTER_PAYMENT, command.envelope().commandType());
        assertEquals("loan-1", command.envelope().loanId());
        assertEquals(50_000, command.amountCents());
        assertEquals("A1", command.accountId());
        assertNull(command.transactionId());
        assertEquals("Pago parcial", command.note());

        assertNotNull(command.envelope().operationId());
        assertTrue(
            command.envelope().operationId().matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
            ),
            "operationId debe ser un UUID v4 canónico"
        );
        assertEquals("owner-1", command.envelope().actorId());
        assertEquals("owner-1", command.envelope().originId());
        assertEquals("fingerprint-1", command.envelope().expectedJournalFingerprint());
        assertTrue(command.envelope().occurredAt() > 0);
    }

    @Test
    void addPrincipalBuildsCanonicalCommandWithTransactionId() {
        AddPrincipalCommand command = factory.addPrincipal(
            "owner-1",
            "loan-1",
            "fingerprint-1",
            50_000,
            "A1",
            "tx-add-1",
            "Ajuste"
        );

        assertEquals(LoanCommandType.ADD_PRINCIPAL, command.envelope().commandType());
        assertEquals(50_000, command.amountCents());
        assertEquals("A1", command.accountId());
        assertEquals("tx-add-1", command.transactionId());
        assertEquals("Ajuste", command.note());
    }

    @Test
    void adjustPrincipalBuildsCanonicalCommandWithTransactionId() {
        AdjustPrincipalCommand command = factory.adjustPrincipal(
            "owner-1",
            "loan-1",
            "fingerprint-1",
            -25_000,
            "Corrección",
            "A1",
            "tx-adjust-1",
            "Nota"
        );

        assertEquals(LoanCommandType.ADJUST_PRINCIPAL, command.envelope().commandType());
        assertEquals(-25_000, command.deltaCents());
        assertEquals("Corrección", command.reason());
        assertEquals("A1", command.accountId());
        assertEquals("tx-adjust-1", command.transactionId());
        assertEquals("Nota", command.note());
    }

    @Test
    void reversePaymentBuildsCanonicalCommand() {
        ReversePaymentCommand command = factory.reversePayment(
            "owner-1",
            "loan-1",
            "event-1",
            "fingerprint-1",
            "Devolución",
            "Nota"
        );

        assertEquals(LoanCommandType.REVERSE_PAYMENT, command.envelope().commandType());
        assertEquals("loan-1", command.envelope().loanId());
        assertEquals("event-1", command.targetPaymentEventId());
        assertEquals("Devolución", command.reason());
        assertEquals("Nota", command.note());

        assertNotNull(command.envelope().operationId());
        assertTrue(
            command.envelope().operationId().matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
            ),
            "operationId debe ser un UUID v4 canónico"
        );
        assertEquals("owner-1", command.envelope().actorId());
        assertEquals("owner-1", command.envelope().originId());
        assertEquals("fingerprint-1", command.envelope().expectedJournalFingerprint());
        assertTrue(command.envelope().occurredAt() > 0);
    }
}
