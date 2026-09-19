package myfinances.infrastructure.loan.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myfinaces.db.AppSchema;
import com.myfinaces.db.SqliteDatabase;
import com.myfinaces.db.TransactionRepository;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.UUID;
import myfinances.application.loan.LoanApplicationService;
import myfinances.domain.loan.aggregate.LoanCommandResult;
import myfinances.domain.loan.aggregate.Outcome;
import myfinances.domain.loan.commands.CreateLoanCommand;
import myfinances.domain.loan.commands.LoanCommandEnvelope;
import myfinances.domain.loan.commands.LoanCommandType;
import myfinances.domain.loan.commands.RegisterPaymentCommand;
import myfinances.domain.loan.journal.LoanType;
import myfinances.domain.loan.projection.LoanPaymentProjection;
import myfinances.domain.loan.projection.LoanProjectionQueryRepository;
import myfinances.domain.loan.projection.LoanSummaryProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoricalLoanPaymentReconcilerTest {
    private static final String OWNER_ID = "owner-1";
    private static final String LOAN_ID = "loan-1";
    private static final String ACCOUNT_ID = "account-1";
    private static final String CATEGORY_ID = "category-repayment";
    private static final String LOAN_ROW_ID = LOAN_ID;

    @TempDir
    Path temporaryDirectory;

    private SqliteDatabase database;
    private LoanApplicationService service;
    private LoanProjectionQueryRepository queryRepository;
    private TransactionRepository transactionRepository;
    private HistoricalLoanPaymentReconciler reconciler;

    @BeforeEach
    void setUp() throws Exception {
        database = new SqliteDatabase(temporaryDirectory.resolve("loan.db"));
        AppSchema.init(database);
        seedSupportRows();
        seedLegacyLoanRow();
        service = new myfinances.infrastructure.loan.di.LoanApplicationServiceFactory(database).loanApplicationService();
        queryRepository = new myfinances.infrastructure.loan.di.LoanApplicationServiceFactory(database).projectionQueryRepository();
        transactionRepository = new TransactionRepository(database);
        reconciler = new HistoricalLoanPaymentReconciler();
    }

    @Test
    void reconcilesMissingPaymentsAndIsIdempotent() throws Exception {
        LoanCommandResult created = service.process(createLoanCommand(1_700_000_000L));
        assertEquals(Outcome.APPLIED, created.outcome());
        LoanSummaryProjection summary = queryRepository.getSummaryProjection(OWNER_ID, LOAN_ID);
        assertTrue(summary != null);

        String txExisting = transactionRepository.create(
            OWNER_ID,
            ACCOUNT_ID,
            CATEGORY_ID,
            "LOAN_REPAYMENT_PRINCIPAL_IN",
            10_000L,
            1_700_000_100L,
            "Pago existente"
        );
        LoanCommandResult applied = service.process(registerPaymentCommand(
            created.currentSnapshot().journalFingerprint(),
            10_000L,
            ACCOUNT_ID,
            txExisting,
            1_700_000_100L,
            "Pago existente"
        ));
        assertEquals(Outcome.APPLIED, applied.outcome());

        insertLegacyPayment("payment-existing", txExisting, 10_000L, 1_700_000_100L, 1_700_000_100L, "Pago existente");

        String txRecoveredDirect = transactionRepository.create(
            OWNER_ID,
            ACCOUNT_ID,
            CATEGORY_ID,
            "LOAN_REPAYMENT_PRINCIPAL_IN",
            20_000L,
            1_700_000_200L,
            "Pago directo"
        );
        insertLegacyPayment("payment-direct", txRecoveredDirect, 20_000L, 1_700_000_200L, 1_700_000_200L, "Pago directo");

        String txRecoveredNull = transactionRepository.create(
            OWNER_ID,
            ACCOUNT_ID,
            CATEGORY_ID,
            "LOAN_REPAYMENT_PRINCIPAL_IN",
            15_000L,
            1_700_000_300L,
            "Pago resoluble"
        );
        insertLegacyPayment("payment-null", null, 15_000L, 1_700_000_300L, 1_700_000_300L, "Pago resoluble");

        HistoricalLoanPaymentReconciler.ReconciliationReport first = reconciler.reconcile(database, service);
        assertEquals(1, first.loansProcessed());
        assertEquals(3, first.paymentsProcessed());
        assertEquals(2, first.paymentsReconciled());
        assertEquals(1, first.paymentsAlreadyExisting());
        assertEquals(0, first.paymentsOmitted());
        assertTrue(first.errors().isEmpty());

        LoanSummaryProjection after = queryRepository.getSummaryProjection(OWNER_ID, LOAN_ID);
        assertTrue(after != null);
        assertEquals(45_000L, after.totalPaidCents());
        assertEquals(55_000L, after.pendingCents());
        assertEquals(3, after.paymentCount());
        assertEquals(3, queryRepository.getPaymentProjections(OWNER_ID, LOAN_ID).size());

        HistoricalLoanPaymentReconciler.ReconciliationReport second = reconciler.reconcile(database, service);
        assertEquals(1, second.loansProcessed());
        assertEquals(3, second.paymentsProcessed());
        assertEquals(0, second.paymentsReconciled());
        assertEquals(3, second.paymentsAlreadyExisting());
        assertEquals(0, second.paymentsOmitted());
        assertTrue(second.errors().isEmpty());
    }

    @Test
    void omitsAmbiguousPaymentsWithoutDuplicatingJournalEntries() throws Exception {
        LoanCommandResult created = service.process(createLoanCommand(1_710_000_000L));
        assertEquals(Outcome.APPLIED, created.outcome());

        transactionRepository.create(
            OWNER_ID,
            ACCOUNT_ID,
            CATEGORY_ID,
            "LOAN_REPAYMENT_PRINCIPAL_IN",
            5_000L,
            1_710_000_100L,
            "Candidato A"
        );
        transactionRepository.create(
            OWNER_ID,
            ACCOUNT_ID,
            CATEGORY_ID,
            "LOAN_REPAYMENT_PRINCIPAL_IN",
            5_000L,
            1_710_000_100L,
            "Candidato B"
        );
        insertLegacyPayment("payment-ambiguous", null, 5_000L, 1_710_000_100L, 1_710_000_100L, "Ambiguo");

        HistoricalLoanPaymentReconciler.ReconciliationReport report = reconciler.reconcile(database, service);
        assertEquals(1, report.loansProcessed());
        assertEquals(1, report.paymentsProcessed());
        assertEquals(0, report.paymentsReconciled());
        assertEquals(0, report.paymentsAlreadyExisting());
        assertEquals(1, report.paymentsOmitted());
        assertEquals(1, report.errors().size());
        assertEquals(0, queryRepository.getPaymentProjections(OWNER_ID, LOAN_ID).size());
    }

    private CreateLoanCommand createLoanCommand(long occurredAtEpochSec) {
        LoanCommandEnvelope envelope = new LoanCommandEnvelope(
            LoanCommandType.CREATE_LOAN,
            UUID.randomUUID().toString(),
            LOAN_ID,
            OWNER_ID,
            null,
            occurredAtEpochSec,
            OWNER_ID,
            OWNER_ID
        );
        return new CreateLoanCommand(
            envelope,
            LoanType.LENT,
            100_000L,
            "Ana Pérez",
            "COP",
            ACCOUNT_ID,
            null,
            "Histórico"
        );
    }

    private RegisterPaymentCommand registerPaymentCommand(
        String fingerprint,
        long amountCents,
        String accountId,
        String transactionId,
        long occurredAtEpochSec,
        String note
    ) {
        LoanCommandEnvelope envelope = new LoanCommandEnvelope(
            LoanCommandType.REGISTER_PAYMENT,
            UUID.randomUUID().toString(),
            LOAN_ID,
            OWNER_ID,
            fingerprint,
            occurredAtEpochSec,
            OWNER_ID,
            OWNER_ID
        );
        return new RegisterPaymentCommand(envelope, amountCents, accountId, transactionId, note);
    }

    private void seedSupportRows() throws Exception {
        try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                "INSERT INTO users (uid, email, created_at_epoch_sec, updated_at_epoch_sec) VALUES " +
                    "('owner-1', 'owner@example.com', 1700000000, 1700000000)"
            );
            statement.executeUpdate(
                "INSERT INTO accounts (id, user_uid, name, type, currency, created_at_epoch_sec, updated_at_epoch_sec) VALUES " +
                    "('account-1', 'owner-1', 'Cuenta principal', 'CHECKING', 'COP', 1700000000, 1700000000)"
            );
            statement.executeUpdate(
                "INSERT INTO categories (id, user_uid, name, parent_id, kind, created_at_epoch_sec, updated_at_epoch_sec) VALUES " +
                    "('category-repayment', 'owner-1', 'Reintegros', NULL, 'EXPENSE', 1700000000, 1700000000)"
            );
        }
    }

    private void seedLegacyLoanRow() throws Exception {
        try (Connection connection = database.openConnection(); PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO loans (id, user_uid, type, counterparty_name, account_id, principal_cents, currency, status, notes, occurred_at_epoch_sec, created_at_epoch_sec, updated_at_epoch_sec, updated_by, pending_sync) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)"
        )) {
            statement.setString(1, LOAN_ROW_ID);
            statement.setString(2, OWNER_ID);
            statement.setString(3, "LENT");
            statement.setString(4, "Ana Pérez");
            statement.setString(5, ACCOUNT_ID);
            statement.setLong(6, 100_000L);
            statement.setString(7, "COP");
            statement.setString(8, "OPEN");
            statement.setString(9, "Histórico");
            statement.setLong(10, 1_700_000_000L);
            statement.setLong(11, 1_700_000_000L);
            statement.setLong(12, 1_700_000_000L);
            statement.setString(13, OWNER_ID);
            statement.executeUpdate();
        }
    }

    private void insertLegacyPayment(
        String paymentId,
        String linkedTransactionId,
        long amountCents,
        long occurredAtEpochSec,
        long createdAtEpochSec,
        String note
    ) throws Exception {
        try (Connection connection = database.openConnection(); PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO loan_payments (id, loan_id, user_uid, account_id, principal_cents, occurred_at_epoch_sec, linked_transaction_id, note, created_at_epoch_sec, updated_at_epoch_sec, updated_by, pending_sync) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)"
        )) {
            statement.setString(1, paymentId);
            statement.setString(2, LOAN_ROW_ID);
            statement.setString(3, OWNER_ID);
            statement.setString(4, ACCOUNT_ID);
            statement.setLong(5, amountCents);
            statement.setLong(6, occurredAtEpochSec);
            if (linkedTransactionId == null) {
                statement.setObject(7, null);
            } else {
                statement.setString(7, linkedTransactionId);
            }
            statement.setString(8, note);
            statement.setLong(9, createdAtEpochSec);
            statement.setLong(10, createdAtEpochSec);
            statement.setString(11, OWNER_ID);
            statement.executeUpdate();
        }
    }
}
