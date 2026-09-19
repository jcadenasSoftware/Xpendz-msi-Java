package myfinances.application.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import myfinances.domain.loan.aggregate.LoanCommandResult;
import myfinances.domain.loan.aggregate.Outcome;
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
import myfinances.domain.loan.projection.FakeLoanProjectionQueryRepository;
import myfinances.domain.loan.projection.FakeLoanProjector;
import myfinances.domain.loan.reducer.DefaultLoanReducer;
import myfinances.domain.loan.repository.FakeLoanRepository;
import myfinances.domain.loan.service.DefaultLoanAggregateService;
import myfinances.domain.loan.service.LoanAggregateService;
import myfinances.domain.loan.service.error.BusinessRuleViolation;
import myfinances.domain.loan.service.error.InvariantViolation;
import myfinances.domain.loan.service.error.ValidationError;
import myfinances.domain.loan.snapshot.LoanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoanApplicationServiceTest {
    private static final String OWNER_ID = "owner-1";
    private static final String LOAN_ID = "loan-1";

    private FakeLoanRepository repository;
    private RecordingLoanAggregateService aggregate;
    private FakeLoanProjector fakeProjector;
    private RecordingLoanProjector projector;
    private FakeLoanProjectionQueryRepository query;
    private LoanApplicationService service;
    private int operationSequence;

    @BeforeEach
    void setUp() {
        operationSequence = 1;
        repository = new FakeLoanRepository();
        DefaultLoanAggregateService delegate = new DefaultLoanAggregateService(repository, new DefaultLoanReducer());
        aggregate = new RecordingLoanAggregateService(delegate);
        fakeProjector = new FakeLoanProjector();
        projector = new RecordingLoanProjector(fakeProjector);
        query = new FakeLoanProjectionQueryRepository(fakeProjector);
        service = new LoanApplicationService(aggregate, projector);
    }

    @Test
    void createLoanIsAppliedAndProjected() {
        LoanCommandResult result = service.process(create(nextOperationId()));

        assertEquals(Outcome.APPLIED, result.outcome());
        assertEquals(1, aggregate.callCount());
        assertEquals(1, projector.projectCallCount());
        assertNotNull(query.getSummaryProjection(OWNER_ID, LOAN_ID));
    }

    @Test
    void registerPaymentIsAppliedAndProjected() {
        service.process(create(nextOperationId()));
        operationSequence++;
        LoanCommandResult result = service.process(payment(nextOperationId(), query.getSummaryProjection(OWNER_ID, LOAN_ID).journalFingerprint(), 40_000));

        assertEquals(Outcome.APPLIED, result.outcome());
        assertEquals(2, aggregate.callCount());
        assertEquals(2, projector.projectCallCount());
        assertEquals(1, query.getPaymentProjections(OWNER_ID, LOAN_ID).size());
    }

    @Test
    void addPrincipalIsAppliedAndProjected() {
        service.process(create(nextOperationId()));
        operationSequence++;
        LoanCommandResult result = service.process(topup(nextOperationId(), query.getSummaryProjection(OWNER_ID, LOAN_ID).journalFingerprint(), 30_000));

        assertEquals(Outcome.APPLIED, result.outcome());
        assertEquals(2, aggregate.callCount());
        assertEquals(2, projector.projectCallCount());
        assertEquals(130_000, query.getSummaryProjection(OWNER_ID, LOAN_ID).principalCents());
    }

    @Test
    void adjustPrincipalIsAppliedAndProjected() {
        service.process(create(nextOperationId()));
        operationSequence++;
        LoanCommandResult result = service.process(adjustment(nextOperationId(), query.getSummaryProjection(OWNER_ID, LOAN_ID).journalFingerprint(), -20_000));

        assertEquals(Outcome.APPLIED, result.outcome());
        assertEquals(2, aggregate.callCount());
        assertEquals(2, projector.projectCallCount());
        assertEquals(80_000, query.getSummaryProjection(OWNER_ID, LOAN_ID).principalCents());
    }

    @Test
    void updateMetadataIsAppliedAndProjected() {
        service.process(create(nextOperationId()));
        operationSequence++;
        LoanCommandResult result = service.process(metadata(nextOperationId(), query.getSummaryProjection(OWNER_ID, LOAN_ID).journalFingerprint()));

        assertEquals(Outcome.APPLIED, result.outcome());
        assertEquals(2, aggregate.callCount());
        assertEquals(2, projector.projectCallCount());
        assertEquals("Ana Pérez", query.getSummaryProjection(OWNER_ID, LOAN_ID).counterparty());
    }

    @Test
    void reversePaymentIsAppliedAndProjected() {
        LoanCommandResult created = service.process(create(nextOperationId()));
        operationSequence++;
        String paymentOp = nextOperationId();
        LoanCommandResult paid = service.process(payment(paymentOp, query.getSummaryProjection(OWNER_ID, LOAN_ID).journalFingerprint(), 40_000));
        operationSequence++;
        LoanCommandResult result = service.process(reversal(nextOperationId(), paid.currentSnapshot().journalFingerprint(), paid.event().eventId()));

        assertEquals(Outcome.APPLIED, result.outcome());
        assertEquals(3, aggregate.callCount());
        assertEquals(3, projector.projectCallCount());
        assertEquals(0, query.getPaymentProjections(OWNER_ID, LOAN_ID).size());
    }

    @Test
    void closeLoanIsAppliedAndProjected() {
        service.process(create(nextOperationId()));
        operationSequence++;
        LoanCommandResult paid = service.process(payment(nextOperationId(), query.getSummaryProjection(OWNER_ID, LOAN_ID).journalFingerprint(), 100_000));
        operationSequence++;
        LoanCommandResult result = service.process(close(nextOperationId(), paid.currentSnapshot().journalFingerprint()));

        assertEquals(Outcome.APPLIED, result.outcome());
        assertEquals(3, aggregate.callCount());
        assertEquals(3, projector.projectCallCount());
        assertEquals(LoanStatus.CLOSED, query.getSummaryProjection(OWNER_ID, LOAN_ID).status());
    }

    @Test
    void replayDoesNotProjectAgain() {
        CreateLoanCommand command = create(nextOperationId());
        LoanCommandResult first = service.process(command);

        assertEquals(Outcome.APPLIED, first.outcome());
        assertEquals(1, aggregate.callCount());
        assertEquals(1, projector.projectCallCount());

        LoanCommandResult second = service.process(command);

        assertEquals(Outcome.REPLAYED, second.outcome());
        assertEquals(2, aggregate.callCount());
        assertEquals(1, projector.projectCallCount());
        assertEquals(first.currentSnapshot(), second.currentSnapshot());
    }

    @Test
    void validationErrorDoesNotProject() {
        int beforeProject = projector.projectCallCount();
        assertThrows(ValidationError.class, () -> service.process(createInvalidPrincipal()));

        assertEquals(1, aggregate.callCount());
        assertEquals(beforeProject, projector.projectCallCount());
    }

    @Test
    void businessRuleViolationDoesNotProject() {
        int beforeProject = projector.projectCallCount();
        assertThrows(BusinessRuleViolation.class, () -> service.process(paymentOnNonExistingLoan()));

        assertEquals(1, aggregate.callCount());
        assertEquals(beforeProject, projector.projectCallCount());
    }

    @Test
    void invariantViolationDoesNotProject() {
        CreateLoanCommand first = create("00000000-0000-4000-8000-000000000001");
        service.process(first);

        int beforeProject = projector.projectCallCount();
        CreateLoanCommand second = new CreateLoanCommand(
            first.envelope(),
            LoanType.BORROWED,
            200_000,
            "Bob",
            "EUR",
            "A2",
            "tx-create-2",
            "Otro"
        );

        assertThrows(InvariantViolation.class, () -> service.process(second));

        assertEquals(2, aggregate.callCount());
        assertEquals(beforeProject, projector.projectCallCount());
    }

    private CreateLoanCommand create(String operationId) {
        return new CreateLoanCommand(
            envelope(LoanCommandType.CREATE_LOAN, operationId, null, 1_000),
            LoanType.LENT,
            100_000,
            "Ana",
            "USD",
            "A1",
            "tx-create",
            "Inicial"
        );
    }

    private CreateLoanCommand createInvalidPrincipal() {
        return new CreateLoanCommand(
            envelope(LoanCommandType.CREATE_LOAN, nextOperationId(), null, 1_000),
            LoanType.LENT,
            0,
            "Ana",
            "USD",
            "A1",
            "tx-create",
            "Inicial"
        );
    }

    private RegisterPaymentCommand payment(String operationId, String fingerprint, long amount) {
        return new RegisterPaymentCommand(
            envelope(LoanCommandType.REGISTER_PAYMENT, operationId, fingerprint, 2_000 + operationSequence),
            amount,
            "A1",
            "tx-" + operationId,
            "Pago"
        );
    }

    private RegisterPaymentCommand paymentOnNonExistingLoan() {
        return new RegisterPaymentCommand(
            envelope(LoanCommandType.REGISTER_PAYMENT, nextOperationId(), null, 2_000),
            10_000,
            "A1",
            "tx-pay",
            "Pago"
        );
    }

    private AddPrincipalCommand topup(String operationId, String fingerprint, long amount) {
        return new AddPrincipalCommand(
            envelope(LoanCommandType.ADD_PRINCIPAL, operationId, fingerprint, 4_000 + operationSequence),
            amount,
            "A1",
            "tx-" + operationId,
            "Capital"
        );
    }

    private AdjustPrincipalCommand adjustment(String operationId, String fingerprint, long delta) {
        return new AdjustPrincipalCommand(
            envelope(LoanCommandType.ADJUST_PRINCIPAL, operationId, fingerprint, 5_000 + operationSequence),
            delta,
            "Corrección",
            "A1",
            "tx-" + operationId,
            null
        );
    }

    private UpdateMetadataCommand metadata(String operationId, String fingerprint) {
        return new UpdateMetadataCommand(
            envelope(LoanCommandType.UPDATE_METADATA, operationId, fingerprint, 6_000 + operationSequence),
            new FieldChange<>(true, "Ana Pérez"),
            new FieldChange<>(false, null),
            new FieldChange<>(false, null)
        );
    }

    private ReversePaymentCommand reversal(String operationId, String fingerprint, String target) {
        return new ReversePaymentCommand(
            envelope(LoanCommandType.REVERSE_PAYMENT, operationId, fingerprint, 3_000 + operationSequence),
            target,
            "Error",
            null
        );
    }

    private CloseLoanCommand close(String operationId, String fingerprint) {
        return new CloseLoanCommand(
            envelope(LoanCommandType.CLOSE_LOAN, operationId, fingerprint, 8_000 + operationSequence),
            "Confirmado",
            null
        );
    }

    private LoanCommandEnvelope envelope(
        LoanCommandType type,
        String operationId,
        String fingerprint,
        long occurredAt
    ) {
        return new LoanCommandEnvelope(
            type,
            operationId,
            LOAN_ID,
            OWNER_ID,
            fingerprint,
            occurredAt,
            "actor-1",
            "device-1"
        );
    }

    private String nextOperationId() {
        return "00000000-0000-4000-8000-%012d".formatted(operationSequence++);
    }
}
