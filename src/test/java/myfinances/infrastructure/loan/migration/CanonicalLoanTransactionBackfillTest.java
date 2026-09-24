package myfinances.infrastructure.loan.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.AppSchema;
import com.myfinaces.db.CategoryRepository;
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
import myfinances.domain.loan.journal.LoanType;
import myfinances.infrastructure.loan.di.LoanApplicationServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CanonicalLoanTransactionBackfillTest {
    private static final String OWNER_ID = "owner-1";
    private static final String ACCOUNT_ID = "account-1";
    private static final String LOAN_ID = "loan-1";
    private static final long OCCURRED_AT = 1_700_000_000L;

    @TempDir
    Path tempDir;

    private SqliteDatabase database;
    private LoanApplicationService service;
    private TransactionRepository txRepo;
    private AccountRepository accountRepo;
    private CategoryRepository categoryRepo;

    @BeforeEach
    void setUp() throws Exception {
        database = new SqliteDatabase(tempDir.resolve("loan-backfill.db"));
        AppSchema.init(database);
        seedSupportRows();
        service = new LoanApplicationServiceFactory(database).loanApplicationService();
        txRepo = new TransactionRepository(database);
        accountRepo = new AccountRepository(database);
        categoryRepo = new CategoryRepository(database);
    }

    @Test
    void orphanCreationEventNeverMaterializesTransaction() throws Exception {
        LoanCommandResult created = service.process(createLoanCommand(null, LoanType.LENT, 250_000L, "Ana Pérez", "COP", "Prestamo histórico"));
        assertEquals(Outcome.APPLIED, created.outcome());
        assertEquals(0L, accountRepo.computeBalanceCents(OWNER_ID, ACCOUNT_ID));

        CanonicalLoanTransactionBackfill.BackfillReport first = CanonicalLoanTransactionBackfill.run(database);
        assertEquals(1, first.loansScanned());
        assertEquals(1, first.loansAlreadyLinked());
        assertEquals(0, first.loansRepaired());
        assertEquals(0, first.transactionsCreated());
        assertEquals(0, first.orphansCovered());
        assertEquals(1, first.orphansUnresolved());
        assertTrue(first.errors().isEmpty());

        String deterministicTxId = CanonicalLoanEventIds.deterministicTransactionId(created.event().eventId());
        assertNull(txRepo.getForSyncByIdOrNull(OWNER_ID, deterministicTxId));
        assertEquals(0L, accountRepo.computeBalanceCents(OWNER_ID, ACCOUNT_ID));

        CanonicalLoanTransactionBackfill.BackfillReport second = CanonicalLoanTransactionBackfill.run(database);
        assertEquals(0, second.transactionsCreated());
        assertEquals(1, second.orphansUnresolved());
        assertTrue(second.errors().isEmpty());
        assertNull(txRepo.getForSyncByIdOrNull(OWNER_ID, deterministicTxId));
    }

    @Test
    void restoresMissingTransactionByKnownTransactionId() throws Exception {
        service.process(createLoanCommand("tx-known", LoanType.LENT, 250_000L, "Ana Pérez", "COP", "Prestamo histórico"));
        assertNull(txRepo.getForSyncByIdOrNull(OWNER_ID, "tx-known"));

        CanonicalLoanTransactionBackfill.BackfillReport first = CanonicalLoanTransactionBackfill.run(database);
        assertEquals(1, first.transactionsCreated());
        assertEquals(0, first.orphansUnresolved());
        assertTrue(first.errors().isEmpty());

        TransactionRepository.TransactionSyncRow tx = txRepo.getForSyncByIdOrNull(OWNER_ID, "tx-known");
        assertNotNull(tx);
        assertEquals(ACCOUNT_ID, tx.accountId());
        assertEquals("system-loan-" + OWNER_ID, tx.categoryId());
        assertEquals("LOAN_LENT_OUT", tx.kind());
        assertEquals(250_000L, tx.amountCents());
        assertEquals(OCCURRED_AT, tx.occurredAtEpochSec());
        assertEquals(-250_000L, accountRepo.computeBalanceCents(OWNER_ID, ACCOUNT_ID));

        CanonicalLoanTransactionBackfill.BackfillReport second = CanonicalLoanTransactionBackfill.run(database);
        assertEquals(0, second.transactionsCreated());
        assertTrue(second.errors().isEmpty());
    }

    @Test
    void respectsExistingTransactionIdWithoutCreatingDuplicates() throws Exception {
        LoanCommandResult created = service.process(createLoanCommand("tx-existing", LoanType.BORROWED, 100_000L, "Juan", "COP", "Préstamo recibido"));
        assertEquals(Outcome.APPLIED, created.outcome());

        String transactionId = "tx-existing";
        txRepo.createWithId(
            transactionId,
            OWNER_ID,
            ACCOUNT_ID,
            ensureLoanCategory(),
            "LOAN_BORROWED_IN",
            100_000L,
            OCCURRED_AT,
            "Dinero recibido de: Juan"
        );

        CanonicalLoanTransactionBackfill.BackfillReport report = CanonicalLoanTransactionBackfill.run(database);
        assertEquals(1, report.loansScanned());
        assertEquals(1, report.loansAlreadyLinked());
        assertEquals(0, report.loansRepaired());
        assertEquals(0, report.transactionsCreated());
        assertTrue(report.errors().isEmpty());
        assertNotNull(txRepo.getForSyncByIdOrNull(OWNER_ID, transactionId));
        assertEquals(100_000L, accountRepo.computeBalanceCents(OWNER_ID, ACCOUNT_ID));
    }

    @Test
    void reportsFailuresForMalformedJournalData() throws Exception {
        LoanCommandResult created = service.process(createLoanCommand(null, LoanType.LENT, 75_000L, "Pedro", "COP", "Préstamo roto"));
        assertEquals(Outcome.APPLIED, created.outcome());

        try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE loan_journal_v1 SET account_id = NULL WHERE owner_id = '" + OWNER_ID + "' AND loan_id = '" + LOAN_ID + "' AND event_type = 'CREATION'");
            statement.executeUpdate("UPDATE loan_snapshots_v1 SET default_account_id = NULL WHERE owner_id = '" + OWNER_ID + "' AND loan_id = '" + LOAN_ID + "'");
        }

        CanonicalLoanTransactionBackfill.BackfillReport report = CanonicalLoanTransactionBackfill.run(database);
        assertEquals(1, report.loansScanned());
        assertEquals(0, report.loansAlreadyLinked());
        assertEquals(0, report.loansRepaired());
        assertEquals(0, report.transactionsCreated());
        assertEquals(1, report.errors().size());
        assertTrue(report.errors().get(0).contains("evento sin cuenta asociada"));
        assertNull(txRepo.getForSyncByIdOrNull(OWNER_ID,
            CanonicalLoanEventIds.deterministicTransactionId(created.event().eventId())));
    }

    @Test
    void orphanTopupEventNeverMaterializesTransaction() throws Exception {
        service.process(createLoanCommand("tx-creation", LoanType.LENT, 250_000L, "Ana Pérez", "COP", null));
        txRepo.createWithId("tx-creation", OWNER_ID, ACCOUNT_ID, ensureLoanCategory(),
            "LOAN_LENT_OUT", 250_000L, OCCURRED_AT, "Préstamo otorgado a: Ana Pérez");
        insertJournalEvent("evt-topup-1", "TOPUP", 100_000L, OCCURRED_AT + 10, null, null);

        CanonicalLoanTransactionBackfill.BackfillReport report = CanonicalLoanTransactionBackfill.run(database);
        assertEquals(0, report.transactionsCreated());
        assertEquals(1, report.orphansUnresolved());
        assertNull(txRepo.getForSyncByIdOrNull(OWNER_ID,
            CanonicalLoanEventIds.deterministicTransactionId("evt-topup-1")));
        assertEquals(-250_000L, accountRepo.computeBalanceCents(OWNER_ID, ACCOUNT_ID));
    }

    @Test
    void orphanPaymentEventNeverMaterializesTransaction() throws Exception {
        service.process(createLoanCommand("tx-creation", LoanType.LENT, 250_000L, "Ana Pérez", "COP", null));
        txRepo.createWithId("tx-creation", OWNER_ID, ACCOUNT_ID, ensureLoanCategory(),
            "LOAN_LENT_OUT", 250_000L, OCCURRED_AT, "Préstamo otorgado a: Ana Pérez");
        insertJournalEvent("evt-pay-1", "PAYMENT", 50_000L, OCCURRED_AT + 10, null, null);

        CanonicalLoanTransactionBackfill.BackfillReport report = CanonicalLoanTransactionBackfill.run(database);
        assertEquals(0, report.transactionsCreated());
        assertEquals(1, report.orphansUnresolved());
        assertNull(txRepo.getForSyncByIdOrNull(OWNER_ID,
            CanonicalLoanEventIds.deterministicTransactionId("evt-pay-1")));
    }

    @Test
    void orphanCoveredByExistingUnreferencedTransaction() throws Exception {
        service.process(createLoanCommand("tx-creation", LoanType.LENT, 250_000L, "Ana Pérez", "COP", null));
        txRepo.createWithId("tx-creation", OWNER_ID, ACCOUNT_ID, ensureLoanCategory(),
            "LOAN_LENT_OUT", 250_000L, OCCURRED_AT, "Préstamo otorgado a: Ana Pérez");
        insertJournalEvent("evt-topup-1", "TOPUP", 100_000L, OCCURRED_AT + 10, null, null);
        txRepo.createWithId("tx-real-topup", OWNER_ID, ACCOUNT_ID, "system-loan-" + OWNER_ID,
            "LOAN_LENT_TOPUP", 100_000L, OCCURRED_AT + 10, "Aumento de préstamo otorgado a: Ana Pérez");

        CanonicalLoanTransactionBackfill.BackfillReport report = CanonicalLoanTransactionBackfill.run(database);
        assertEquals(0, report.transactionsCreated());
        assertEquals(1, report.orphansCovered());
        assertEquals(0, report.orphansUnresolved());
        assertNull(txRepo.getForSyncByIdOrNull(OWNER_ID,
            CanonicalLoanEventIds.deterministicTransactionId("evt-topup-1")));
        assertEquals(-350_000L, accountRepo.computeBalanceCents(OWNER_ID, ACCOUNT_ID));
    }

    @Test
    void orphanAdjustmentEventNeverMaterializesTransaction() throws Exception {
        service.process(createLoanCommand("tx-creation", LoanType.LENT, 250_000L, "Ana Pérez", "COP", null));
        txRepo.createWithId("tx-creation", OWNER_ID, ACCOUNT_ID, ensureLoanCategory(),
            "LOAN_LENT_OUT", 250_000L, OCCURRED_AT, "Préstamo otorgado a: Ana Pérez");
        insertJournalEvent("evt-adj-real", "ADJUSTMENT", 30_000L, OCCURRED_AT + 10, null,
            "Corrección de préstamo otorgado a: Ana Pérez");

        CanonicalLoanTransactionBackfill.BackfillReport report = CanonicalLoanTransactionBackfill.run(database);
        assertEquals(0, report.transactionsCreated());
        assertEquals(1, report.orphansUnresolved());
        assertNull(txRepo.getForSyncByIdOrNull(OWNER_ID,
            CanonicalLoanEventIds.deterministicTransactionId("evt-adj-real")));
    }

    @Test
    void skipsSyntheticAdjustmentWithoutFinancialFlow() throws Exception {
        service.process(createLoanCommand("tx-creation", LoanType.LENT, 250_000L, "Ana Pérez", "COP", null));
        txRepo.createWithId("tx-creation", OWNER_ID, ACCOUNT_ID, ensureLoanCategory(),
            "LOAN_LENT_OUT", 250_000L, OCCURRED_AT, "Préstamo otorgado a: Ana Pérez");
        long occurredAt = OCCURRED_AT + 20;
        String syntheticId = CanonicalLoanEventIds.deterministic(
            LOAN_ID, "ADJUSTMENT", "synth:adjust:" + LOAN_ID, occurredAt);
        insertJournalEvent(syntheticId, "ADJUSTMENT", 50_000L, occurredAt, null,
            "Ajuste incremental remoto");
        long beforeBalance = accountRepo.computeBalanceCents(OWNER_ID, ACCOUNT_ID);

        CanonicalLoanTransactionBackfill.BackfillReport report = CanonicalLoanTransactionBackfill.run(database);
        assertEquals(0, report.transactionsCreated());
        assertEquals(1, report.orphansCovered());
        assertEquals(0, report.orphansUnresolved());
        assertTrue(report.errors().isEmpty());
        assertNull(txRepo.getForSyncByIdOrNull(OWNER_ID, CanonicalLoanEventIds.deterministicTransactionId(syntheticId)));
        assertEquals(beforeBalance, accountRepo.computeBalanceCents(OWNER_ID, ACCOUNT_ID));

        CanonicalLoanTransactionBackfill.BackfillReport second = CanonicalLoanTransactionBackfill.run(database);
        assertEquals(0, second.transactionsCreated());
        assertTrue(second.errors().isEmpty());
    }

    @Test
    void backfillDoesNotMutateJournal() throws Exception {
        service.process(createLoanCommand(null, LoanType.LENT, 250_000L, "Ana Pérez", "COP", null));
        String before = journalDump();
        CanonicalLoanTransactionBackfill.run(database);
        CanonicalLoanTransactionBackfill.run(database);
        assertEquals(before, journalDump());
    }

    private String journalDump() throws Exception {
        try (Connection connection = database.openConnection(); Statement statement = connection.createStatement();
             java.sql.ResultSet rs = statement.executeQuery(
                 "SELECT * FROM loan_journal_v1 ORDER BY event_id")) {
            StringBuilder sb = new StringBuilder();
            int cols = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                for (int i = 1; i <= cols; i++) {
                    sb.append(rs.getString(i)).append('|');
                }
                sb.append('\n');
            }
            return sb.toString();
        }
    }

    private void insertJournalEvent(String eventId, String eventType, Long amountCents,
                                    long occurredAt, String transactionId, String note) throws Exception {
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO loan_journal_v1 (event_id, operation_id, loan_id, owner_id, event_type, event_schema_version, " +
            "amount_cents, account_id, transaction_id, note, occurred_at, recorded_at, actor_id, origin_id, payload_reason, " +
            "metadata_counterparty_present, metadata_account_present, metadata_notes_present) " +
            "VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0)")) {
            ps.setString(1, eventId);
            ps.setString(2, eventId);
            ps.setString(3, LOAN_ID);
            ps.setString(4, OWNER_ID);
            ps.setString(5, eventType);
            if (amountCents == null) {
                ps.setNull(6, java.sql.Types.INTEGER);
            } else {
                ps.setLong(6, amountCents);
            }
            ps.setString(7, ACCOUNT_ID);
            ps.setString(8, transactionId);
            ps.setString(9, note);
            ps.setLong(10, occurredAt);
            ps.setLong(11, occurredAt);
            ps.setString(12, OWNER_ID);
            ps.setString(13, OWNER_ID);
            ps.setString(14, note);
            ps.executeUpdate();
        }
    }

    private String ensureLoanCategory() throws Exception {
        String categoryId = "system-loan-" + OWNER_ID;
        try (Connection connection = database.openConnection(); PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO categories (id, user_uid, name, parent_id, kind, created_at_epoch_sec, updated_at_epoch_sec) VALUES (?, ?, ?, NULL, NULL, ?, ?)"
        )) {
            long now = System.currentTimeMillis() / 1000L;
            statement.setString(1, categoryId);
            statement.setString(2, OWNER_ID);
            statement.setString(3, "Préstamos");
            statement.setLong(4, now);
            statement.setLong(5, now);
            statement.executeUpdate();
        }
        return categoryId;
    }

    private CreateLoanCommand createLoanCommand(String transactionId, LoanType loanType, long principalCents, String counterparty, String currency, String notes) {
        LoanCommandEnvelope envelope = new LoanCommandEnvelope(
            LoanCommandType.CREATE_LOAN,
            UUID.randomUUID().toString(),
            LOAN_ID,
            OWNER_ID,
            null,
            OCCURRED_AT,
            OWNER_ID,
            OWNER_ID
        );
        return new CreateLoanCommand(
            envelope,
            loanType,
            principalCents,
            counterparty,
            currency,
            ACCOUNT_ID,
            transactionId,
            notes
        );
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
        }
    }
}
