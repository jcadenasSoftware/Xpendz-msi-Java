package myfinances.infrastructure.loan.projection.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.myfinaces.db.SqliteDatabase;
import java.nio.file.Path;
import java.util.List;
import myfinances.domain.loan.aggregate.LoanCommandResult;
import myfinances.domain.loan.commands.AddPrincipalCommand;
import myfinances.domain.loan.commands.CloseLoanCommand;
import myfinances.domain.loan.commands.CreateLoanCommand;
import myfinances.domain.loan.commands.LoanCommandEnvelope;
import myfinances.domain.loan.commands.LoanCommandType;
import myfinances.domain.loan.commands.RegisterPaymentCommand;
import myfinances.domain.loan.journal.LoanType;
import myfinances.domain.loan.projection.LoanPaymentProjection;
import myfinances.domain.loan.projection.LoanProjectionQueryRepository;
import myfinances.domain.loan.projection.LoanProjector;
import myfinances.domain.loan.projection.LoanSummaryFilter;
import myfinances.domain.loan.projection.LoanSummaryProjection;
import myfinances.domain.loan.projection.SortBy;
import myfinances.infrastructure.loan.admin.JdbcLoanAdminStateRepository;
import myfinances.domain.loan.reducer.DefaultLoanReducer;
import myfinances.domain.loan.service.DefaultLoanAggregateService;
import myfinances.domain.loan.service.LoanAggregateService;
import myfinances.domain.loan.snapshot.LoanStatus;
import myfinances.infrastructure.loan.jdbc.JdbcLoanAggregateExecutor;
import myfinances.infrastructure.loan.jdbc.JdbcLoanRepositoryAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoanProjectorIntegrationTest {
    private static final String OWNER_ID = "owner-1";
    private static final String LOAN_ID = "loan-1";

    @TempDir
    Path temporaryDirectory;
    private SqliteDatabase database;
    private JdbcLoanRepositoryAdapter repository;
    private LoanAggregateService service;
    private LoanProjector projector;
    private LoanProjectionQueryRepository query;
    private int operationSequence;

    @BeforeEach
    void setUp() {
        operationSequence = 1;
        database = new SqliteDatabase(temporaryDirectory.resolve("projector.db"));
        repository = new JdbcLoanRepositoryAdapter(database);
        DefaultLoanReducer reducer = new DefaultLoanReducer();
        service = new JdbcLoanAggregateExecutor(repository, new DefaultLoanAggregateService(repository, reducer));
        projector = new DefaultLoanProjector(database);
        query = new JdbcLoanProjectionQueryRepository(database);
    }

    @Test
    void projectsCompleteLoanLifecycleAndRebuildsFromJournal() {
        LoanCommandResult created = service.process(create(nextOperationId()));
        projector.project(created);

        LoanSummaryProjection createdSummary = query.getSummaryProjection(OWNER_ID, LOAN_ID);
        assertNotNull(createdSummary);
        assertEquals(100_000, createdSummary.principalCents());
        assertEquals(0, createdSummary.totalPaidCents());
        assertEquals(LoanStatus.OPEN, createdSummary.status());

        LoanCommandResult payment = service.process(payment(nextOperationId(), createdSummary.journalFingerprint(), 40_000));
        projector.project(payment);

        LoanSummaryProjection paidSummary = query.getSummaryProjection(OWNER_ID, LOAN_ID);
        assertEquals(40_000, paidSummary.totalPaidCents());
        assertEquals(60_000, paidSummary.pendingCents());

        JdbcLoanAdminStateRepository adminRepo = new JdbcLoanAdminStateRepository(database);
        adminRepo.archive(OWNER_ID, LOAN_ID, "device-1");
        assertEquals(
            List.of(),
            query.listActiveSummaries(
                OWNER_ID,
                new LoanSummaryFilter(LoanType.LENT, null, SortBy.PENDING_CENTS, false, null)
            )
        );

        List<LoanPaymentProjection> payments = query.getPaymentProjections(OWNER_ID, LOAN_ID);
        assertEquals(1, payments.size());
        assertEquals(40_000, payments.getFirst().amountCents());

        LoanCommandResult topup = service.process(topup(nextOperationId(), paidSummary.journalFingerprint(), 50_000));
        projector.project(topup);

        LoanCommandResult finalPayment = service.process(payment(nextOperationId(), topup.currentSnapshot().journalFingerprint(), 110_000));
        projector.project(finalPayment);

        LoanCommandResult closed = service.process(close(nextOperationId(), finalPayment.currentSnapshot().journalFingerprint()));
        projector.project(closed);

        LoanSummaryProjection closedSummary = query.getSummaryProjection(OWNER_ID, LOAN_ID);
        assertEquals(LoanStatus.CLOSED, closedSummary.status());
        assertEquals(0, closedSummary.pendingCents());
        assertEquals(2, query.getPaymentProjections(OWNER_ID, LOAN_ID).size());

        projector.rebuild(OWNER_ID, LOAN_ID, repository, new DefaultLoanReducer());
        LoanSummaryProjection rebuiltSummary = query.getSummaryProjection(OWNER_ID, LOAN_ID);
        assertEquals(closedSummary, rebuiltSummary);
    }

    @Test
    void rebuildFromEmptyJournalLeavesNoProjections() {
        projector.rebuild(OWNER_ID, LOAN_ID, repository, new DefaultLoanReducer());

        assertEquals(List.of(), query.getPaymentProjections(OWNER_ID, LOAN_ID));
        assertEquals(null, query.getSummaryProjection(OWNER_ID, LOAN_ID));
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

    private AddPrincipalCommand topup(String operationId, String fingerprint, long amount) {
        return new AddPrincipalCommand(
            envelope(LoanCommandType.ADD_PRINCIPAL, operationId, fingerprint, 4_000 + operationSequence),
            amount,
            "A1",
            "tx-" + operationId,
            "Capital"
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
