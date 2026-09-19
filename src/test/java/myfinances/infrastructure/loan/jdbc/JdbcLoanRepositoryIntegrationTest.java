package myfinances.infrastructure.loan.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myfinaces.db.SqliteDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
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
import myfinances.domain.loan.journal.LoanMovement;
import myfinances.domain.loan.journal.LoanType;
import myfinances.domain.loan.reducer.DefaultLoanReducer;
import myfinances.domain.loan.reducer.LoanReductionResult;
import myfinances.domain.loan.service.DefaultLoanAggregateService;
import myfinances.domain.loan.service.LoanAggregateService;
import myfinances.domain.loan.service.error.UnexpectedFailure;
import myfinances.domain.loan.snapshot.LoanSnapshot;
import myfinances.domain.loan.snapshot.LoanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcLoanRepositoryIntegrationTest {
    private static final String OWNER_ID = "owner-1";
    private static final String LOAN_ID = "loan-1";

    @TempDir
    Path temporaryDirectory;

    private SqliteDatabase database;
    private JdbcLoanRepositoryAdapter repository;
    private LoanAggregateService executor;
    private int operationSequence;

    @BeforeEach
    void setUp() {
        database = new SqliteDatabase(temporaryDirectory.resolve("loan.db"));
        repository = new JdbcLoanRepositoryAdapter(database);
        executor = executor(repository);
        operationSequence = 1;
    }

    @Test
    void persistsCompleteAggregateFlowAndReplaysAfterRestart() {
        LoanCommandResult created = executor.process(create(nextOperationId()));
        RegisterPaymentCommand originalPayment = payment(nextOperationId(), created.currentSnapshot().journalFingerprint(), 40_000);
        LoanCommandResult paid = executor.process(originalPayment);

        JdbcLoanRepositoryAdapter restartedRepository = new JdbcLoanRepositoryAdapter(database);
        LoanAggregateService restartedExecutor = executor(restartedRepository);
        LoanCommandResult replay = restartedExecutor.process(originalPayment);

        assertEquals(Outcome.REPLAYED, replay.outcome());
        assertEquals(2, restartedRepository.getJournal(OWNER_ID, LOAN_ID).size());
        assertEquals(paid.currentSnapshot(), replay.currentSnapshot());

        LoanCommandResult reversed = restartedExecutor.process(reversal(
            nextOperationId(), fingerprint(restartedRepository), originalPayment.envelope().operationId()
        ));
        LoanCommandResult toppedUp = restartedExecutor.process(topup(
            nextOperationId(), reversed.currentSnapshot().journalFingerprint(), 20_000
        ));
        LoanCommandResult adjusted = restartedExecutor.process(adjustment(
            nextOperationId(), toppedUp.currentSnapshot().journalFingerprint(), -20_000
        ));
        LoanCommandResult metadata = restartedExecutor.process(metadata(
            nextOperationId(), adjusted.currentSnapshot().journalFingerprint()
        ));
        LoanCommandResult finalPayment = restartedExecutor.process(payment(
            nextOperationId(), metadata.currentSnapshot().journalFingerprint(), 100_000
        ));
        LoanCommandResult closed = restartedExecutor.process(close(
            nextOperationId(), finalPayment.currentSnapshot().journalFingerprint()
        ));

        List<LoanMovement> journal = restartedRepository.getJournal(OWNER_ID, LOAN_ID);
        LoanSnapshot persistedSnapshot = restartedRepository.loadSnapshot(OWNER_ID, LOAN_ID).orElseThrow();
        LoanReductionResult rebuilt = new DefaultLoanReducer().reduceCanonical(journal);

        assertEquals(8, journal.size());
        assertTrue(journal.stream().allMatch(event -> event.eventId().equals(event.operationId())));
        assertEquals(closed.currentSnapshot(), persistedSnapshot);
        assertEquals(persistedSnapshot, rebuilt.snapshot());
        assertEquals(persistedSnapshot.journalFingerprint(), rebuilt.snapshot().journalFingerprint());
        assertEquals(LoanStatus.CLOSED, persistedSnapshot.status());
        assertEquals("Ana Pérez", persistedSnapshot.counterpartyName());
        assertEquals("A2", persistedSnapshot.defaultAccountId());
        assertEquals("Acuerdo actualizado", persistedSnapshot.notes());
    }

    @Test
    void rollsBackEventWhenSnapshotPersistenceFails() throws Exception {
        LoanCommandResult created = executor.process(create(nextOperationId()));
        try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                "CREATE TRIGGER reject_loan_snapshot BEFORE INSERT ON loan_snapshots_v1 " +
                    "BEGIN SELECT RAISE(ABORT, 'snapshot failure'); END"
            );
        }

        assertThrows(
            UnexpectedFailure.class,
            () -> executor.process(topup(nextOperationId(), created.currentSnapshot().journalFingerprint(), 10_000))
        );

        assertEquals(1, repository.getJournal(OWNER_ID, LOAN_ID).size());
        assertEquals(created.currentSnapshot(), repository.loadSnapshot(OWNER_ID, LOAN_ID).orElseThrow());
    }

    @Test
    void mapperRoundTripPreservesEveryPersistedEventField() {
        LoanCommandResult created = executor.process(create(nextOperationId()));
        LoanMovement original = created.event();
        LoanMovement restored = repository.findByEventId(OWNER_ID, original.eventId()).orElseThrow();

        assertEquals(original, restored);
        assertEquals(created.currentSnapshot(), repository.loadSnapshot(OWNER_ID, LOAN_ID).orElseThrow());
        assertEquals(original, repository.findByOperationId(OWNER_ID, original.operationId()).orElseThrow());
    }

    private LoanAggregateService executor(JdbcLoanRepositoryAdapter adapter) {
        return new JdbcLoanAggregateExecutor(
            adapter,
            new DefaultLoanAggregateService(adapter, new DefaultLoanReducer())
        );
    }

    private static String fingerprint(JdbcLoanRepositoryAdapter adapter) {
        return adapter.loadSnapshot(OWNER_ID, LOAN_ID).orElseThrow().journalFingerprint();
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

    private RegisterPaymentCommand payment(String operationId, String fingerprint, long amount) {
        return new RegisterPaymentCommand(
            envelope(LoanCommandType.REGISTER_PAYMENT, operationId, fingerprint, 2_000 + operationSequence),
            amount,
            "A1",
            "tx-" + operationId,
            "Pago"
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
            new FieldChange<>(true, "A2"),
            new FieldChange<>(true, "Acuerdo actualizado")
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
