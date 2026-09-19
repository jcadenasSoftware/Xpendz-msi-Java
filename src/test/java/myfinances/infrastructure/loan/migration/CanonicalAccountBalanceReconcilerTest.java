package myfinances.infrastructure.loan.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myfinaces.db.AccountRepository;
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
import myfinances.infrastructure.loan.di.LoanApplicationServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CanonicalAccountBalanceReconcilerTest {

    private static final String OWNER_ID = "owner-1";
    private static final String LOAN_ID = "loan-1";
    private static final String ACCOUNT_ID = "account-1";
    private static final String ACCOUNT_ID_2 = "account-2";

    @TempDir
    Path temporaryDirectory;

    private SqliteDatabase database;
    private LoanApplicationService service;
    private AccountRepository accountRepo;
    private TransactionRepository txRepo;

    @BeforeEach
    void setUp() throws Exception {
        database = new SqliteDatabase(temporaryDirectory.resolve("reconcile.db"));
        AppSchema.init(database);
        seedSupportRows();
        service = new LoanApplicationServiceFactory(database).loanApplicationService();
        accountRepo = new AccountRepository(database);
        txRepo = new TransactionRepository(database);
    }

    @Test
    void keepsConsistentStateWithoutChanges() throws Exception {
        seedCanonicalLoanWithPayment("tx-create", "tx-pay");
        seedTransaction("tx-create", "LOAN_LENT_OUT", 100_000L, ACCOUNT_ID, 1_000L);
        seedTransaction("tx-pay", "LOAN_REPAYMENT_PRINCIPAL_IN", 40_000L, ACCOUNT_ID, 2_000L);
        seedTransaction("tx-income", "INCOME", 200_000L, ACCOUNT_ID, 500L);

        long before = accountRepo.computeBalanceCents(OWNER_ID, ACCOUNT_ID);
        CanonicalAccountBalanceReconciler.ReconciliationReport report =
            CanonicalAccountBalanceReconciler.run(database, OWNER_ID);

        assertFalse(report.hasChanges());
        assertEquals(2, report.expectedArtifacts());
        assertEquals(2, report.actualArtifacts());
        assertEquals(0, report.transactionsCreated());
        assertEquals(0, report.transactionsDeleted());
        assertTrue(report.accounts().isEmpty());
        assertTrue(report.errors().isEmpty());
        assertEquals(before, accountRepo.computeBalanceCents(OWNER_ID, ACCOUNT_ID));
        assertEquals(3, countRows("transactions"));
    }

    @Test
    void deletesDuplicatedHistoricalTransaction() throws Exception {
        seedCanonicalLoanWithPayment("tx-create", "tx-pay");
        seedTransaction("tx-create", "LOAN_LENT_OUT", 100_000L, ACCOUNT_ID, 1_000L);
        seedTransaction("tx-pay", "LOAN_REPAYMENT_PRINCIPAL_IN", 40_000L, ACCOUNT_ID, 2_000L);
        // Duplicado histórico del bug: mismo efecto financiero, otro id, sin
        // referencia canónica ni de transporte.
        seedTransaction("tx-dup", "LOAN_REPAYMENT_PRINCIPAL_IN", 40_000L, ACCOUNT_ID, 2_050L);

        long corrupted = accountRepo.computeBalanceCents(OWNER_ID, ACCOUNT_ID);
        CanonicalAccountBalanceReconciler.ReconciliationReport report =
            CanonicalAccountBalanceReconciler.run(database, OWNER_ID);

        assertTrue(report.hasChanges());
        assertEquals(0, report.transactionsCreated());
        assertEquals(1, report.transactionsDeleted());
        assertNull(txRepo.getForSyncByIdOrNull(OWNER_ID, "tx-dup"));
        assertNotNull(txRepo.getForSyncByIdOrNull(OWNER_ID, "tx-pay"));
        assertEquals(corrupted - 40_000L, accountRepo.computeBalanceCents(OWNER_ID, ACCOUNT_ID));
        assertEquals(1, report.accounts().size());
        assertEquals(40_000L, report.accounts().get(0).driftCents());
    }

    @Test
    void materializesMissingHistoricalTransaction() throws Exception {
        seedCanonicalLoanWithPayment("tx-create", "tx-pay");
        seedTransaction("tx-create", "LOAN_LENT_OUT", 100_000L, ACCOUNT_ID, 1_000L);
        // El pago canónico existe en el journal pero su transacción nunca
        // llegó a esta base (caso 7H).
        assertNull(txRepo.getForSyncByIdOrNull(OWNER_ID, "tx-pay"));

        CanonicalAccountBalanceReconciler.ReconciliationReport report =
            CanonicalAccountBalanceReconciler.run(database, OWNER_ID);

        assertTrue(report.hasChanges());
        assertEquals(1, report.transactionsCreated());
        assertEquals(0, report.transactionsDeleted());
        TransactionRepository.TransactionSyncRow restored =
            txRepo.getForSyncByIdOrNull(OWNER_ID, "tx-pay");
        assertNotNull(restored);
        assertEquals("LOAN_REPAYMENT_PRINCIPAL_IN", restored.kind());
        assertEquals(40_000L, restored.amountCents());
        assertEquals(ACCOUNT_ID, restored.accountId());
        assertEquals(2_000L, restored.occurredAtEpochSec());
        assertEquals(-40_000L, report.accounts().get(0).driftCents());
    }

    @Test
    void secondRunProducesZeroChanges() throws Exception {
        seedCanonicalLoanWithPayment("tx-create", "tx-pay");
        seedTransaction("tx-create", "LOAN_LENT_OUT", 100_000L, ACCOUNT_ID, 1_000L);
        // Faltante: el evento PAYMENT no tiene transacción equivalente.
        // Sobrante: duplicado con monto distinto que ningún evento reclama.
        seedTransaction("tx-dup", "LOAN_REPAYMENT_PRINCIPAL_IN", 10_000L, ACCOUNT_ID, 2_050L);

        CanonicalAccountBalanceReconciler.ReconciliationReport first =
            CanonicalAccountBalanceReconciler.run(database, OWNER_ID);
        assertTrue(first.hasChanges());
        assertEquals(1, first.transactionsCreated());
        assertEquals(1, first.transactionsDeleted());

        CanonicalAccountBalanceReconciler.ReconciliationReport second =
            CanonicalAccountBalanceReconciler.run(database, OWNER_ID);
        assertFalse(second.hasChanges());
        assertEquals(0, second.transactionsCreated());
        assertEquals(0, second.transactionsDeleted());
        assertTrue(second.accounts().isEmpty());
        assertTrue(second.errors().isEmpty());
    }

    @Test
    void reconcilesMultipleAccountsIndependently() throws Exception {
        seedCanonicalLoanWithPayment("tx-create", "tx-pay");
        seedTransaction("tx-create", "LOAN_LENT_OUT", 100_000L, ACCOUNT_ID, 1_000L);
        seedTransaction("tx-pay", "LOAN_REPAYMENT_PRINCIPAL_IN", 40_000L, ACCOUNT_ID, 2_000L);

        seedCanonicalLoan("loan-2", "tx-create-2");
        seedTransaction("tx-create-2", "LOAN_LENT_OUT", 60_000L, ACCOUNT_ID_2, 1_000L);
        seedTransaction("tx-orphan", "LOAN_LENT_OUT", 60_000L, ACCOUNT_ID_2, 1_500L);

        long balance1Before = accountRepo.computeBalanceCents(OWNER_ID, ACCOUNT_ID);
        CanonicalAccountBalanceReconciler.ReconciliationReport report =
            CanonicalAccountBalanceReconciler.run(database, OWNER_ID);

        assertEquals(2, report.accountsScanned());
        assertEquals(1, report.transactionsDeleted());
        assertNull(txRepo.getForSyncByIdOrNull(OWNER_ID, "tx-orphan"));
        assertEquals(balance1Before, accountRepo.computeBalanceCents(OWNER_ID, ACCOUNT_ID));
        assertEquals(1, report.accounts().size());
        assertEquals(ACCOUNT_ID_2, report.accounts().get(0).accountId());
    }

    @Test
    void keepsSurplusTransactionProtectedByTransport() throws Exception {
        seedCanonicalLoanWithPayment("tx-create", "tx-pay");
        seedTransaction("tx-create", "LOAN_LENT_OUT", 100_000L, ACCOUNT_ID, 1_000L);
        seedTransaction("tx-pay", "LOAN_REPAYMENT_PRINCIPAL_IN", 40_000L, ACCOUNT_ID, 2_000L);
        // Pago remoto aún no adoptado por el journal: su transacción está
        // referenciada por el transporte y no debe eliminarse.
        seedTransaction("tx-pending", "LOAN_REPAYMENT_PRINCIPAL_IN", 25_000L, ACCOUNT_ID, 3_000L);
        seedLegacyLoanRow();
        seedPayment("pay-remote", 25_000L, ACCOUNT_ID, "tx-pending", 3_000L, 3_000L);

        CanonicalAccountBalanceReconciler.ReconciliationReport report =
            CanonicalAccountBalanceReconciler.run(database, OWNER_ID);

        assertEquals(0, report.transactionsDeleted());
        assertEquals(1, report.transactionsKeptProtected());
        assertNotNull(txRepo.getForSyncByIdOrNull(OWNER_ID, "tx-pending"));
        assertFalse(report.warnings().isEmpty());
    }

    @Test
    void doesNotTouchNonLoanTransactions() throws Exception {
        seedCanonicalLoanWithPayment("tx-create", "tx-pay");
        seedTransaction("tx-create", "LOAN_LENT_OUT", 100_000L, ACCOUNT_ID, 1_000L);
        seedTransaction("tx-pay", "LOAN_REPAYMENT_PRINCIPAL_IN", 40_000L, ACCOUNT_ID, 2_000L);
        seedTransaction("tx-expense", "EXPENSE", 9_000L, ACCOUNT_ID, 1_500L);
        seedTransaction("tx-expense-dup", "EXPENSE", 9_000L, ACCOUNT_ID, 1_600L);

        CanonicalAccountBalanceReconciler.run(database, OWNER_ID);

        assertNotNull(txRepo.getForSyncByIdOrNull(OWNER_ID, "tx-expense"));
        assertNotNull(txRepo.getForSyncByIdOrNull(OWNER_ID, "tx-expense-dup"));
    }

    private void seedCanonicalLoanWithPayment(String creationTxId, String paymentTxId) throws Exception {
        LoanCommandResult created = seedCanonicalLoan(LOAN_ID, creationTxId);

        LoanCommandResult paid = service.process(new RegisterPaymentCommand(
            new LoanCommandEnvelope(
                LoanCommandType.REGISTER_PAYMENT,
                UUID_s(),
                LOAN_ID,
                OWNER_ID,
                created.currentSnapshot().journalFingerprint(),
                2_000L,
                OWNER_ID,
                OWNER_ID
            ),
            40_000L,
            ACCOUNT_ID,
            paymentTxId,
            null
        ));
        assertEquals(Outcome.APPLIED, paid.outcome());
    }

    private LoanCommandResult seedCanonicalLoan(String loanId, String creationTxId) throws Exception {
        long principal = "loan-2".equals(loanId) ? 60_000L : 100_000L;
        String accountId = "loan-2".equals(loanId) ? ACCOUNT_ID_2 : ACCOUNT_ID;
        LoanCommandResult created = service.process(new CreateLoanCommand(
            new LoanCommandEnvelope(
                LoanCommandType.CREATE_LOAN,
                UUID_s(),
                loanId,
                OWNER_ID,
                null,
                1_000L,
                OWNER_ID,
                OWNER_ID
            ),
            LoanType.LENT,
            principal,
            "Counterparty",
            "COP",
            accountId,
            creationTxId,
            null
        ));
        assertEquals(Outcome.APPLIED, created.outcome());
        return created;
    }

    private void seedSupportRows() throws Exception {
        try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                "INSERT INTO users (uid, email, created_at_epoch_sec, updated_at_epoch_sec) VALUES " +
                "('" + OWNER_ID + "', 'test@example.com', 0, 0) " +
                "ON CONFLICT(uid) DO NOTHING"
            );
            statement.executeUpdate(
                "INSERT INTO accounts (id, user_uid, name, type, currency, created_at_epoch_sec, updated_at_epoch_sec) VALUES " +
                "('" + ACCOUNT_ID + "', '" + OWNER_ID + "', 'Cuenta', 'CASH', 'COP', 0, 0)"
            );
            statement.executeUpdate(
                "INSERT INTO accounts (id, user_uid, name, type, currency, created_at_epoch_sec, updated_at_epoch_sec) VALUES " +
                "('" + ACCOUNT_ID_2 + "', '" + OWNER_ID + "', 'Cuenta 2', 'CASH', 'COP', 0, 0)"
            );
            statement.executeUpdate(
                "INSERT INTO categories (id, user_uid, name, parent_id, kind, created_at_epoch_sec, updated_at_epoch_sec) VALUES " +
                "('cat-1', '" + OWNER_ID + "', 'General', NULL, NULL, 0, 0)"
            );
        }
    }

    private void seedTransaction(String id, String kind, long amount, String accountId, long occurredAt) throws Exception {
        String sql =
            "INSERT INTO transactions (id, user_uid, account_id, category_id, kind, amount_cents, " +
            "occurred_at_epoch_sec, note, created_at_epoch_sec, updated_at_epoch_sec, pending_sync) " +
            "VALUES (?, ?, ?, 'cat-1', ?, ?, ?, NULL, ?, ?, 0)";
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, OWNER_ID);
            ps.setString(3, accountId);
            ps.setString(4, kind);
            ps.setLong(5, amount);
            ps.setLong(6, occurredAt);
            ps.setLong(7, occurredAt);
            ps.setLong(8, occurredAt);
            ps.executeUpdate();
        }
    }

    private void seedLegacyLoanRow() throws Exception {
        String sql =
            "INSERT INTO loans (id, user_uid, type, counterparty_name, principal_cents, currency, status, notes, " +
            "occurred_at_epoch_sec, account_id, created_at_epoch_sec, updated_at_epoch_sec, updated_by, pending_sync) " +
            "VALUES (?, ?, 'LENT', 'Counterparty', 100000, 'COP', 'OPEN', NULL, 1000, ?, 1000, 1000, NULL, 0)";
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, LOAN_ID);
            ps.setString(2, OWNER_ID);
            ps.setString(3, ACCOUNT_ID);
            ps.executeUpdate();
        }
    }

    private void seedPayment(String id, long principal, String accountId, String txId, long occurredAt, long createdAt) throws Exception {
        String sql =
            "INSERT INTO loan_payments (id, loan_id, user_uid, account_id, principal_cents, occurred_at_epoch_sec, " +
            "linked_transaction_id, note, created_at_epoch_sec, updated_at_epoch_sec, updated_by, pending_sync) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, 0)";
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, LOAN_ID);
            ps.setString(3, OWNER_ID);
            ps.setString(4, accountId);
            ps.setLong(5, principal);
            ps.setLong(6, occurredAt);
            ps.setString(7, txId);
            ps.setLong(8, createdAt);
            ps.setLong(9, createdAt);
            ps.executeUpdate();
        }
    }

    private int countRows(String table) throws Exception {
        try (Connection connection = database.openConnection();
             PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
             java.sql.ResultSet result = ps.executeQuery()) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private String UUID_s() {
        return UUID.randomUUID().toString();
    }
}
