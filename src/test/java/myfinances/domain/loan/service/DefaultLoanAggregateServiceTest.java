package myfinances.domain.loan.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collection;
import java.util.List;
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
import myfinances.domain.loan.diagnostics.LoanDiagnostic;
import myfinances.domain.loan.diagnostics.LoanDiagnosticCode;
import myfinances.domain.loan.journal.LoanEventPayload;
import myfinances.domain.loan.journal.LoanEventType;
import myfinances.domain.loan.journal.LoanJournalEntry;
import myfinances.domain.loan.journal.LoanMovement;
import myfinances.domain.loan.journal.LoanType;
import myfinances.domain.loan.projection.LoanProjectionChangeType;
import myfinances.domain.loan.reducer.DefaultLoanReducer;
import myfinances.domain.loan.reducer.LoanReducer;
import myfinances.domain.loan.reducer.LoanReductionResult;
import myfinances.domain.loan.reducer.ReductionResultType;
import myfinances.domain.loan.repository.FakeLoanRepository;
import myfinances.domain.loan.service.error.BusinessRuleViolation;
import myfinances.domain.loan.service.error.InvariantViolation;
import myfinances.domain.loan.service.error.LoanAggregateErrorCode;
import myfinances.domain.loan.service.error.ValidationError;
import myfinances.domain.loan.snapshot.LoanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultLoanAggregateServiceTest {
    private static final String OWNER_ID = "owner-1";
    private static final String LOAN_ID = "loan-1";

    private FakeLoanRepository repository;
    private DefaultLoanAggregateService service;
    private int operationSequence;

    @BeforeEach
    void setUp() {
        repository = new FakeLoanRepository();
        service = new DefaultLoanAggregateService(repository, new DefaultLoanReducer());
        operationSequence = 1;
    }

    @Test
    void c1CreatesLoanWithExactlyOneCreationEvent() {
        LoanCommandResult result = service.process(createLoan(nextOperationId()));

        assertEquals(Outcome.APPLIED, result.outcome());
        assertEquals(LoanEventType.CREATION, result.event().eventType());
        assertEquals(result.operationId(), result.event().eventId());
        assertEquals(result.operationId(), result.event().operationId());
        assertEquals(result.event().occurredAt(), result.event().recordedAt());
        assertNull(result.previousSnapshot());
        assertEquals(100_000, result.currentSnapshot().principalCents());
        assertEquals(1, repository.appendCount());
        assertProjectionTypes(result, LoanProjectionChangeType.REBUILD_LOAN_SNAPSHOT);
    }

    @Test
    void c2RegistersPartialPaymentAndAddsConceptualProjection() {
        createInitialLoan();
        String operationId = nextOperationId();
        LoanCommandResult result = service.process(payment(operationId, fingerprint(), 25_000));

        assertEquals(Outcome.APPLIED, result.outcome());
        assertEquals(LoanEventType.PAYMENT, result.event().eventType());
        assertEquals(25_000, result.currentSnapshot().totalPaidCents());
        assertEquals(75_000, result.currentSnapshot().pendingCents());
        assertProjectionTypes(
            result,
            LoanProjectionChangeType.ADD_PAYMENT_PROJECTION,
            LoanProjectionChangeType.REBUILD_LOAN_SNAPSHOT
        );
        assertEquals(operationId, result.projectionChanges().get(0).sourceEventId());
    }

    @Test
    void c3ReplaySkipsFingerprintAndReturnsCurrentSnapshotWithoutAppending() {
        createInitialLoan();
        String staleFingerprint = fingerprint();
        String operationId = nextOperationId();
        RegisterPaymentCommand original = payment(operationId, staleFingerprint, 20_000);
        service.process(original);
        service.process(topup(nextOperationId(), fingerprint(), 10_000));
        int beforeReplay = repository.appendCount();

        LoanCommandResult replay = service.process(original);

        assertEquals(Outcome.REPLAYED, replay.outcome());
        assertEquals(beforeReplay, repository.appendCount());
        assertEquals(90_000, replay.currentSnapshot().pendingCents());
        assertEquals(replay.currentSnapshot(), replay.previousSnapshot());
        assertProjectionTypes(replay, LoanProjectionChangeType.NONE);
    }

    @Test
    void c4RejectsDifferentCommandUsingExistingOperationId() {
        createInitialLoan();
        String operationId = nextOperationId();
        service.process(payment(operationId, fingerprint(), 20_000));
        int eventCount = repository.appendCount();

        InvariantViolation error = assertThrows(
            InvariantViolation.class,
            () -> service.process(payment(operationId, fingerprint(), 30_000))
        );

        assertEquals(LoanAggregateErrorCode.OPERATION_CONFLICT, error.code());
        assertEquals(eventCount, repository.appendCount());
    }

    @Test
    void c5ExactPaymentClosesLoan() {
        createInitialLoan();
        LoanCommandResult result = service.process(payment(nextOperationId(), fingerprint(), 100_000));

        assertEquals(0, result.currentSnapshot().pendingCents());
        assertEquals(LoanStatus.CLOSED, result.currentSnapshot().status());
    }

    @Test
    void c6RejectsPaymentWhenLoanHasNoPendingBalance() {
        createInitialLoan();
        service.process(payment(nextOperationId(), fingerprint(), 100_000));
        int eventCount = repository.appendCount();

        BusinessRuleViolation error = assertThrows(
            BusinessRuleViolation.class,
            () -> service.process(payment(nextOperationId(), fingerprint(), 1_000))
        );

        assertEquals(LoanAggregateErrorCode.LOAN_HAS_NO_PENDING_BALANCE, error.code());
        assertEquals(eventCount, repository.appendCount());
    }

    @Test
    void c7AddingPrincipalReopensClosedLoan() {
        createInitialLoan();
        service.process(payment(nextOperationId(), fingerprint(), 100_000));

        LoanCommandResult result = service.process(topup(nextOperationId(), fingerprint(), 20_000));

        assertEquals(LoanEventType.TOPUP, result.event().eventType());
        assertEquals(20_000, result.currentSnapshot().pendingCents());
        assertEquals(LoanStatus.OPEN, result.currentSnapshot().status());
    }

    @Test
    void c8AdjustsPrincipalWithOneAdjustmentEvent() {
        createInitialLoan();
        LoanCommandResult result = service.process(adjustment(nextOperationId(), fingerprint(), -10_000));

        assertEquals(LoanEventType.ADJUSTMENT, result.event().eventType());
        assertEquals(90_000, result.currentSnapshot().principalCents());
        assertEquals(2, repository.appendCount());
    }

    @Test
    void c9RejectsAdjustmentBelowTotalPaid() {
        createInitialLoan();
        service.process(payment(nextOperationId(), fingerprint(), 80_000));
        int eventCount = repository.appendCount();

        BusinessRuleViolation error = assertThrows(
            BusinessRuleViolation.class,
            () -> service.process(adjustment(nextOperationId(), fingerprint(), -30_000))
        );

        assertEquals(LoanAggregateErrorCode.PRINCIPAL_BELOW_TOTAL_PAID, error.code());
        assertEquals(eventCount, repository.appendCount());
    }

    @Test
    void c10UpdatesMetadataWithoutChangingFinancialState() {
        createInitialLoan();
        LoanCommandResult result = service.process(metadata(
            nextOperationId(),
            fingerprint(),
            new FieldChange<>(true, "Ana Pérez"),
            new FieldChange<>(true, "A2"),
            new FieldChange<>(true, "Acuerdo actualizado")
        ));

        assertEquals(LoanEventType.METADATA_CHANGED, result.event().eventType());
        assertEquals("Ana Pérez", result.currentSnapshot().counterpartyName());
        assertEquals("A2", result.currentSnapshot().defaultAccountId());
        assertEquals("Acuerdo actualizado", result.currentSnapshot().notes());
        assertEquals(100_000, result.currentSnapshot().principalCents());
    }

    @Test
    void c11RejectsMetadataNoOp() {
        createInitialLoan();
        int eventCount = repository.appendCount();

        BusinessRuleViolation error = assertThrows(
            BusinessRuleViolation.class,
            () -> service.process(metadata(
                nextOperationId(),
                fingerprint(),
                new FieldChange<>(true, "Ana"),
                new FieldChange<>(false, null),
                new FieldChange<>(false, null)
            ))
        );

        assertEquals(LoanAggregateErrorCode.NO_EFFECTIVE_CHANGE, error.code());
        assertEquals(eventCount, repository.appendCount());
    }

    @Test
    void c12ReversesEffectivePaymentAndRemovesConceptualProjection() {
        createInitialLoan();
        String paymentOperation = nextOperationId();
        service.process(payment(paymentOperation, fingerprint(), 40_000));

        LoanCommandResult result = service.process(reversal(
            nextOperationId(), fingerprint(), paymentOperation
        ));

        assertEquals(LoanEventType.REVERSAL, result.event().eventType());
        assertEquals(0, result.currentSnapshot().totalPaidCents());
        assertEquals(100_000, result.currentSnapshot().pendingCents());
        assertProjectionTypes(
            result,
            LoanProjectionChangeType.REMOVE_PAYMENT_PROJECTION,
            LoanProjectionChangeType.REBUILD_LOAN_SNAPSHOT
        );
        assertEquals(paymentOperation, result.projectionChanges().get(0).sourceEventId());
    }

    @Test
    void c13AddsExplicitCloseMarkerOnlyAfterFinancialClosure() {
        createInitialLoan();
        service.process(payment(nextOperationId(), fingerprint(), 100_000));
        LoanCommandResult result = service.process(close(nextOperationId(), fingerprint()));

        assertEquals(LoanEventType.CLOSE, result.event().eventType());
        assertEquals(LoanStatus.CLOSED, result.currentSnapshot().status());
        assertEquals(0, result.currentSnapshot().pendingCents());
    }

    @Test
    void c14RejectsSecondConcurrentCommandWithStaleFingerprint() {
        createInitialLoan();
        String sharedFingerprint = fingerprint();
        RegisterPaymentCommand first = payment(nextOperationId(), sharedFingerprint, 30_000);
        RegisterPaymentCommand second = payment(nextOperationId(), sharedFingerprint, 25_000);
        service.process(first);
        int eventCount = repository.appendCount();

        BusinessRuleViolation error = assertThrows(
            BusinessRuleViolation.class,
            () -> service.process(second)
        );

        assertEquals(LoanAggregateErrorCode.STALE_AGGREGATE_VERSION, error.code());
        assertEquals(eventCount, repository.appendCount());
    }

    @Test
    void c15SameConcurrentOperationConvergesToOneEvent() {
        createInitialLoan();
        RegisterPaymentCommand command = payment(nextOperationId(), fingerprint(), 10_000);

        LoanCommandResult applied = service.process(command);
        LoanCommandResult replayed = service.process(command);

        assertEquals(Outcome.APPLIED, applied.outcome());
        assertEquals(Outcome.REPLAYED, replayed.outcome());
        assertEquals(2, repository.appendCount());
        assertEquals(10_000, replayed.currentSnapshot().totalPaidCents());
    }

    @Test
    void validatesEnvelopeBeforeRepositoryMutation() {
        CreateLoanCommand command = createLoan("not-a-uuid");

        ValidationError error = assertThrows(ValidationError.class, () -> service.process(command));

        assertEquals(LoanAggregateErrorCode.INVALID_OPERATION_ID, error.code());
        assertEquals(0, repository.appendCount());
    }

    @Test
    void requiresFingerprintForNewCommandsButNotReplay() {
        createInitialLoan();
        int eventCount = repository.appendCount();

        ValidationError error = assertThrows(
            ValidationError.class,
            () -> service.process(payment(nextOperationId(), null, 10_000))
        );

        assertEquals(LoanAggregateErrorCode.EXPECTED_FINGERPRINT_REQUIRED, error.code());
        assertEquals(eventCount, repository.appendCount());
    }

    @Test
    void rejectsCandidateJournalWhenReducerDoesNotReturnValid() {
        LoanReducer rejectingReducer = new LoanReducer() {
            private final LoanReducer delegate = new DefaultLoanReducer();

            @Override
            public LoanReductionResult reduce(Collection<LoanJournalEntry> events) {
                return delegate.reduce(events);
            }

            @Override
            public LoanReductionResult reduceCanonical(Collection<LoanMovement> events) {
                if (!events.isEmpty()) {
                    return new LoanReductionResult(
                        ReductionResultType.INVALID,
                        null,
                        List.copyOf(events),
                        List.of(new LoanDiagnostic(LoanDiagnosticCode.INVALID_EVENT_PAYLOAD, null, null)),
                        List.of(),
                        List.of(),
                        List.of()
                    );
                }
                return delegate.reduceCanonical(events);
            }
        };
        DefaultLoanAggregateService rejectingService = new DefaultLoanAggregateService(repository, rejectingReducer);

        InvariantViolation error = assertThrows(
            InvariantViolation.class,
            () -> rejectingService.process(createLoan(nextOperationId()))
        );

        assertEquals(LoanAggregateErrorCode.CANDIDATE_JOURNAL_INVALID, error.code());
        assertEquals(0, repository.appendCount());
    }

    @Test
    void rejectsPaymentAbovePendingWithoutAppending() {
        createInitialLoan();
        int eventCount = repository.appendCount();

        BusinessRuleViolation error = assertThrows(
            BusinessRuleViolation.class,
            () -> service.process(payment(nextOperationId(), fingerprint(), 100_001))
        );

        assertEquals(LoanAggregateErrorCode.PAYMENT_EXCEEDS_PENDING, error.code());
        assertEquals(eventCount, repository.appendCount());
    }

    @Test
    void rejectsCloseWhilePendingWithoutAppending() {
        createInitialLoan();
        int eventCount = repository.appendCount();

        BusinessRuleViolation error = assertThrows(
            BusinessRuleViolation.class,
            () -> service.process(close(nextOperationId(), fingerprint()))
        );

        assertEquals(LoanAggregateErrorCode.LOAN_HAS_PENDING_BALANCE, error.code());
        assertEquals(eventCount, repository.appendCount());
    }

    @Test
    void preservesArithmeticOverflowClassificationFromCandidateReducer() {
        service.process(new CreateLoanCommand(
            envelope(LoanCommandType.CREATE_LOAN, nextOperationId(), null, 1_000),
            LoanType.LENT,
            Long.MAX_VALUE,
            "Ana",
            "USD",
            null,
            null,
            null
        ));
        int eventCount = repository.appendCount();

        BusinessRuleViolation error = assertThrows(
            BusinessRuleViolation.class,
            () -> service.process(topup(nextOperationId(), fingerprint(), 1))
        );

        assertEquals(LoanAggregateErrorCode.ARITHMETIC_OVERFLOW, error.code());
        assertEquals(eventCount, repository.appendCount());
    }

    @Test
    void rejectsAlreadyReversedPayment() {
        createInitialLoan();
        String paymentOperation = nextOperationId();
        service.process(payment(paymentOperation, fingerprint(), 20_000));
        service.process(reversal(nextOperationId(), fingerprint(), paymentOperation));
        int eventCount = repository.appendCount();

        BusinessRuleViolation error = assertThrows(
            BusinessRuleViolation.class,
            () -> service.process(reversal(nextOperationId(), fingerprint(), paymentOperation))
        );

        assertEquals(LoanAggregateErrorCode.PAYMENT_ALREADY_REVERSED, error.code());
        assertEquals(eventCount, repository.appendCount());
    }

    @Test
    void rejectsCrossLoanReversalTarget() {
        createInitialLoan();
        LoanMovement crossLoanPayment = new LoanMovement(
            "cross-event",
            "cross-operation",
            "loan-2",
            OWNER_ID,
            LoanEventType.PAYMENT,
            1,
            10_000L,
            null,
            null,
            null,
            2_000,
            2_000,
            null,
            null,
            new LoanEventPayload.PaymentPayload(null, null)
        );
        repository.appendEvent(crossLoanPayment);
        int eventCount = repository.appendCount();

        BusinessRuleViolation error = assertThrows(
            BusinessRuleViolation.class,
            () -> service.process(reversal(nextOperationId(), fingerprint(), "cross-event"))
        );

        assertEquals(LoanAggregateErrorCode.CROSS_LOAN_TARGET, error.code());
        assertEquals(eventCount, repository.appendCount());
    }

    @Test
    void rejectsNonPaymentReversalTarget() {
        LoanCommandResult creation = createInitialLoan();
        int eventCount = repository.appendCount();

        BusinessRuleViolation error = assertThrows(
            BusinessRuleViolation.class,
            () -> service.process(reversal(nextOperationId(), fingerprint(), creation.event().eventId()))
        );

        assertEquals(LoanAggregateErrorCode.TARGET_NOT_PAYMENT, error.code());
        assertEquals(eventCount, repository.appendCount());
    }

    @Test
    void rejectsUuidVersionOutsideNativeContract() {
        ValidationError error = assertThrows(
            ValidationError.class,
            () -> service.process(createLoan("00000000-0000-1000-8000-000000000001"))
        );

        assertEquals(LoanAggregateErrorCode.INVALID_OPERATION_ID, error.code());
        assertEquals(0, repository.appendCount());
    }

    private LoanCommandResult createInitialLoan() {
        return service.process(createLoan(nextOperationId()));
    }

    private String fingerprint() {
        return repository.loadSnapshot(OWNER_ID, LOAN_ID).orElseThrow().journalFingerprint();
    }

    private CreateLoanCommand createLoan(String operationId) {
        return new CreateLoanCommand(
            envelope(LoanCommandType.CREATE_LOAN, operationId, null, 1_000),
            LoanType.LENT,
            100_000,
            "Ana",
            "usd",
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

    private AddPrincipalCommand topup(String operationId, String fingerprint, long amount) {
        return new AddPrincipalCommand(
            envelope(LoanCommandType.ADD_PRINCIPAL, operationId, fingerprint, 3_000 + operationSequence),
            amount,
            "A1",
            "tx-" + operationId,
            "Capital"
        );
    }

    private AdjustPrincipalCommand adjustment(String operationId, String fingerprint, long delta) {
        return new AdjustPrincipalCommand(
            envelope(LoanCommandType.ADJUST_PRINCIPAL, operationId, fingerprint, 4_000 + operationSequence),
            delta,
            "Corrección",
            "A1",
            "tx-" + operationId,
            null
        );
    }

    private UpdateMetadataCommand metadata(
        String operationId,
        String fingerprint,
        FieldChange<String> counterparty,
        FieldChange<String> account,
        FieldChange<String> notes
    ) {
        return new UpdateMetadataCommand(
            envelope(LoanCommandType.UPDATE_METADATA, operationId, fingerprint, 5_000 + operationSequence),
            counterparty,
            account,
            notes
        );
    }

    private ReversePaymentCommand reversal(
        String operationId,
        String fingerprint,
        String targetEventId
    ) {
        return new ReversePaymentCommand(
            envelope(LoanCommandType.REVERSE_PAYMENT, operationId, fingerprint, 6_000 + operationSequence),
            targetEventId,
            "Error",
            null
        );
    }

    private CloseLoanCommand close(String operationId, String fingerprint) {
        return new CloseLoanCommand(
            envelope(LoanCommandType.CLOSE_LOAN, operationId, fingerprint, 7_000 + operationSequence),
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

    private static void assertProjectionTypes(
        LoanCommandResult result,
        LoanProjectionChangeType... expected
    ) {
        assertEquals(
            List.of(expected),
            result.projectionChanges().stream().map(change -> change.type()).toList()
        );
    }
}
