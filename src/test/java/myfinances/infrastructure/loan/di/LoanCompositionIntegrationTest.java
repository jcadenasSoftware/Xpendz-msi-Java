package myfinances.infrastructure.loan.di;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myfinaces.db.SqliteDatabase;
import java.nio.file.Path;
import myfinances.application.loan.LoanApplicationService;
import myfinances.domain.loan.aggregate.LoanCommandResult;
import myfinances.domain.loan.aggregate.Outcome;
import myfinances.domain.loan.commands.CreateLoanCommand;
import myfinances.domain.loan.commands.LoanCommandEnvelope;
import myfinances.domain.loan.commands.LoanCommandType;
import myfinances.domain.loan.commands.RegisterPaymentCommand;
import myfinances.domain.loan.commands.ReversePaymentCommand;
import myfinances.domain.loan.journal.LoanType;
import java.util.List;
import myfinances.domain.loan.projection.LoanPaymentProjection;
import myfinances.domain.loan.projection.LoanProjectionQueryRepository;
import myfinances.domain.loan.projection.LoanSummaryFilter;
import myfinances.domain.loan.projection.LoanSummaryProjection;
import myfinances.domain.loan.projection.SortBy;
import myfinances.domain.loan.snapshot.LoanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoanCompositionIntegrationTest {
    private static final String OWNER_ID = "owner-1";
    private static final String LOAN_ID = "loan-1";

    @TempDir
    Path temporaryDirectory;
    private LoanApplicationService service;
    private LoanProjectionQueryRepository query;
    private int operationSequence;

    @BeforeEach
    void setUp() {
        operationSequence = 1;
        SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("loan.db"));
        LoanApplicationServiceFactory factory = new LoanApplicationServiceFactory(database);
        service = factory.loanApplicationService();
        query = factory.projectionQueryRepository();
    }

    @Test
    void fullyWiredServiceExecutesCreatePaymentReverseAndReplay() {
        LoanCommandResult created = service.process(create(nextOperationId()));
        assertEquals(Outcome.APPLIED, created.outcome());

        LoanSummaryProjection createdSummary = query.getSummaryProjection(OWNER_ID, LOAN_ID);
        assertNotNull(createdSummary);
        assertEquals(100_000, createdSummary.principalCents());
        assertEquals(LoanStatus.OPEN, createdSummary.status());
        assertEquals("A1", createdSummary.defaultAccountId());
        assertEquals("Inicial", createdSummary.notes());
        assertEquals(0, createdSummary.paymentCount());
        assertNull(createdSummary.lastPaymentAt());
        assertEquals(0, createdSummary.progressPercent());

        RegisterPaymentCommand paymentCommand = payment(nextOperationId(), createdSummary.journalFingerprint(), 40_000);
        LoanCommandResult paid = service.process(paymentCommand);
        assertEquals(Outcome.APPLIED, paid.outcome());

        LoanSummaryProjection paidSummary = query.getSummaryProjection(OWNER_ID, LOAN_ID);
        assertEquals(40_000, paidSummary.totalPaidCents());
        assertEquals(60_000, paidSummary.pendingCents());
        assertEquals(1, paidSummary.paymentCount());
        assertNotNull(paidSummary.lastPaymentAt());
        assertEquals(40, paidSummary.progressPercent());

        LoanPaymentProjection paymentProjection = query.getPaymentProjections(OWNER_ID, LOAN_ID).getFirst();
        assertEquals(40_000, paymentProjection.amountCents());

        LoanCommandResult replay = service.process(paymentCommand);
        assertEquals(Outcome.REPLAYED, replay.outcome());
        assertEquals(paid.currentSnapshot(), replay.currentSnapshot());
        assertEquals(1, query.getPaymentProjections(OWNER_ID, LOAN_ID).size());

        LoanCommandResult reversed = service.process(reverse(nextOperationId(), paidSummary.journalFingerprint(), paid.event().eventId()));
        assertEquals(Outcome.APPLIED, reversed.outcome());

        LoanSummaryProjection reversedSummary = query.getSummaryProjection(OWNER_ID, LOAN_ID);
        assertEquals(0, reversedSummary.totalPaidCents());
        assertEquals(100_000, reversedSummary.pendingCents());
        assertEquals(0, reversedSummary.paymentCount());
        assertNull(reversedSummary.lastPaymentAt());
        assertEquals(0, reversedSummary.progressPercent());
        assertTrue(query.getPaymentProjections(OWNER_ID, LOAN_ID).isEmpty());
    }

    @Test
    void canListSummariesForOwnerWithFilterAndSort() {
        LoanCommandResult created = service.process(create(nextOperationId()));
        assertEquals(Outcome.APPLIED, created.outcome());

        LoanSummaryProjection single = query.getSummaryProjection(OWNER_ID, LOAN_ID);
        assertNotNull(single);

        List<LoanSummaryProjection> all = query.listSummaries(OWNER_ID, new LoanSummaryFilter(null, null, SortBy.LAST_ACTIVITY, false, null));
        assertEquals(1, all.size());
        assertEquals(LOAN_ID, all.get(0).loanId());

        List<LoanSummaryProjection> byType = query.listSummaries(OWNER_ID, new LoanSummaryFilter(LoanType.LENT, null, SortBy.LAST_ACTIVITY, false, null));
        assertEquals(1, byType.size());

        List<LoanSummaryProjection> byStatus = query.listSummaries(OWNER_ID, new LoanSummaryFilter(null, LoanStatus.OPEN, SortBy.LAST_ACTIVITY, false, null));
        assertEquals(1, byStatus.size());

        List<LoanSummaryProjection> byTypeAndStatus = query.listSummaries(OWNER_ID, new LoanSummaryFilter(LoanType.BORROWED, LoanStatus.OPEN, SortBy.LAST_ACTIVITY, false, null));
        assertTrue(byTypeAndStatus.isEmpty());

        List<LoanSummaryProjection> limited = query.listSummaries(OWNER_ID, new LoanSummaryFilter(null, null, SortBy.LAST_ACTIVITY, false, 5));
        assertEquals(1, limited.size());

        List<LoanSummaryProjection> sorted = query.listSummaries(OWNER_ID, new LoanSummaryFilter(null, null, SortBy.PRINCIPAL_CENTS, true, null));
        assertEquals(1, sorted.size());
        assertEquals(single.principalCents(), sorted.get(0).principalCents());
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

    private ReversePaymentCommand reverse(String operationId, String fingerprint, String target) {
        return new ReversePaymentCommand(
            envelope(LoanCommandType.REVERSE_PAYMENT, operationId, fingerprint, 3_000 + operationSequence),
            target,
            "Error",
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
