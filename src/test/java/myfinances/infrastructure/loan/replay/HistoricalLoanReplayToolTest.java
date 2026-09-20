package myfinances.infrastructure.loan.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myfinaces.db.AppSchema;
import com.myfinaces.db.SqliteDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.UUID;
import myfinances.application.loan.LoanApplicationService;
import myfinances.domain.loan.aggregate.Outcome;
import myfinances.domain.loan.commands.LoanCommandEnvelope;
import myfinances.domain.loan.commands.LoanCommandType;
import myfinances.domain.loan.commands.RegisterPaymentCommand;
import myfinances.domain.loan.journal.LoanType;
import myfinances.domain.loan.projection.LoanProjectionQueryRepository;
import myfinances.domain.loan.projection.LoanSummaryProjection;
import myfinances.domain.loan.snapshot.LoanStatus;
import myfinances.infrastructure.loan.di.LoanApplicationServiceFactory;
import myfinances.infrastructure.loan.migration.CanonicalLoanTransactionBackfill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoricalLoanReplayToolTest {
    private static final String OWNER_ID = "owner-1";
    private static final String LOAN_ID = "loan-1";
    private static final String ACCOUNT_ID = "account-1";

    @TempDir
    Path temporaryDirectory;

    private SqliteDatabase database;
    private LoanApplicationService service;
    private LoanProjectionQueryRepository queryRepository;
    private HistoricalLoanReplayTool tool;

    @BeforeEach
    void setUp() throws Exception {
        database = new SqliteDatabase(temporaryDirectory.resolve("replay.db"));
        AppSchema.init(database);
        seedSupportRows();
        service = new LoanApplicationServiceFactory(database).loanApplicationService();
        queryRepository = new LoanApplicationServiceFactory(database).projectionQueryRepository();
        tool = new HistoricalLoanReplayTool(database);
    }

    @Test
    void replaysSimpleLoanWithPayment() throws Exception {
        seedLoan(100_000L, "OPEN");
        seedMovement("mov-1", "CREATION", 100_000L, ACCOUNT_ID, null, 1_000L, null, 1_000L);
        seedMovement("mov-2", "PAYMENT_IN", 100_000L, ACCOUNT_ID, null, 2_000L, null, 2_000L);

        HistoricalLoanReplayTool.ReplayResult result = tool.replay(OWNER_ID, LOAN_ID);

        assertTrue(result.success(), result.errors().toString());
        assertEquals("SUCCESS", result.status());
        assertEquals(2, result.eventsApplied());

        LoanSummaryProjection summary = queryRepository.getSummaryProjection(OWNER_ID, LOAN_ID);
        assertNotNull(summary);
        assertEquals(100_000L, summary.principalCents());
        assertEquals(100_000L, summary.totalPaidCents());
        assertEquals(0L, summary.pendingCents());
        assertEquals(LoanStatus.CLOSED, summary.status());
        assertEquals(1, summary.paymentCount());
    }

    @Test
    void replaysLoanWithTopupAndPayments() throws Exception {
        seedLoan(150_000L, "OPEN");
        seedMovement("mov-1", "CREATION", 100_000L, ACCOUNT_ID, null, 1_000L, null, 1_000L);
        seedMovement("mov-2", "TOPUP", 50_000L, ACCOUNT_ID, null, 1_500L, null, 1_500L);
        seedMovement("mov-3", "PAYMENT_IN", 50_000L, ACCOUNT_ID, null, 2_000L, null, 2_000L);
        seedMovement("mov-4", "PAYMENT_IN", 100_000L, ACCOUNT_ID, null, 2_500L, null, 2_500L);

        HistoricalLoanReplayTool.ReplayResult result = tool.replay(OWNER_ID, LOAN_ID);

        assertTrue(result.success(), result.errors().toString());
        assertEquals(4, result.eventsApplied());

        LoanSummaryProjection summary = queryRepository.getSummaryProjection(OWNER_ID, LOAN_ID);
        assertNotNull(summary);
        assertEquals(150_000L, summary.principalCents());
        assertEquals(150_000L, summary.totalPaidCents());
        assertEquals(0L, summary.pendingCents());
        assertEquals(LoanStatus.CLOSED, summary.status());
        assertEquals(2, summary.paymentCount());
    }

    @Test
    void replaysLoanWithMultiplePayments() throws Exception {
        seedLoan(100_000L, "OPEN");
        seedMovement("mov-1", "CREATION", 100_000L, ACCOUNT_ID, null, 1_000L, null, 1_000L);
        seedPayment("pay-1", 40_000L, ACCOUNT_ID, null, 2_000L, 2_000L);
        seedPayment("pay-2", 60_000L, ACCOUNT_ID, null, 3_000L, 3_000L);

        HistoricalLoanReplayTool.ReplayResult result = tool.replay(OWNER_ID, LOAN_ID);

        assertTrue(result.success(), result.errors().toString());
        assertEquals(3, result.eventsApplied());

        LoanSummaryProjection summary = queryRepository.getSummaryProjection(OWNER_ID, LOAN_ID);
        assertNotNull(summary);
        assertEquals(100_000L, summary.principalCents());
        assertEquals(100_000L, summary.totalPaidCents());
        assertEquals(0L, summary.pendingCents());
        assertEquals(LoanStatus.CLOSED, summary.status());
        assertEquals(2, summary.paymentCount());
    }

    @Test
    void replayIsIdempotent() throws Exception {
        seedLoan(100_000L, "OPEN");
        seedMovement("mov-1", "CREATION", 100_000L, ACCOUNT_ID, null, 1_000L, null, 1_000L);
        seedPayment("pay-1", 100_000L, ACCOUNT_ID, null, 2_000L, 2_000L);

        HistoricalLoanReplayTool.ReplayResult first = tool.replay(OWNER_ID, LOAN_ID);
        assertTrue(first.success(), first.errors().toString());

        HistoricalLoanReplayTool.ReplayResult second = tool.replay(OWNER_ID, LOAN_ID);
        assertTrue(second.success(), second.errors().toString());

        LoanSummaryProjection summary = queryRepository.getSummaryProjection(OWNER_ID, LOAN_ID);
        assertNotNull(summary);
        assertEquals(1, summary.paymentCount());
        assertEquals(100_000L, summary.totalPaidCents());
        assertEquals(LoanStatus.CLOSED, summary.status());
    }

    @Test
    void replayRepairsEditedLegacyPaymentIntoClosedLoan() throws Exception {
        seedLoan(100_000L, "OPEN");
        seedMovement("mov-1", "CREATION", 100_000L, ACCOUNT_ID, null, 1_000L, null, 1_000L);
        seedPayment("pay-1", 40_000L, ACCOUNT_ID, null, 2_000L, 2_000L);

        HistoricalLoanReplayTool.ReplayResult initial = tool.replay(OWNER_ID, LOAN_ID);
        assertTrue(initial.success(), initial.errors().toString());
        assertNotNull(initial.summary());
        assertEquals(60_000L, initial.summary().pendingCents());
        assertEquals(LoanStatus.OPEN, initial.summary().status());

        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(
            "UPDATE loan_payments SET principal_cents = ?, updated_at_epoch_sec = ? WHERE id = ?"
        )) {
            ps.setLong(1, 100_000L);
            ps.setLong(2, 3_000L);
            ps.setString(3, "pay-1");
            ps.executeUpdate();
        }

        HistoricalLoanReplayTool.ReplayResult repaired = tool.replay(OWNER_ID, LOAN_ID);
        assertTrue(repaired.success(), repaired.errors().toString());
        assertNotNull(repaired.summary());
        assertEquals(100_000L, repaired.summary().totalPaidCents());
        assertEquals(0L, repaired.summary().pendingCents());
        assertEquals(LoanStatus.CLOSED, repaired.summary().status());
        assertEquals(1, repaired.summary().paymentCount());
    }

    @Test
    void previouslyReconciledLoanCanBeRebuilt() throws Exception {
        seedLoan(100_000L, "OPEN");
        seedMovement("mov-1", "CREATION", 100_000L, ACCOUNT_ID, null, 1_000L, null, 1_000L);
        seedMovement("mov-2", "PAYMENT_IN", 100_000L, ACCOUNT_ID, null, 2_000L, null, 2_000L);

        service.process(new myfinances.domain.loan.commands.CreateLoanCommand(
            new LoanCommandEnvelope(LoanCommandType.CREATE_LOAN, UUID_s(), LOAN_ID, OWNER_ID, null, 1_000L, OWNER_ID, OWNER_ID),
            LoanType.LENT,
            100_000L,
            "Counterparty",
            "COP",
            ACCOUNT_ID,
            null,
            null
        ));

        HistoricalLoanReplayTool.ReplayResult result = tool.replay(OWNER_ID, LOAN_ID);
        assertTrue(result.success(), result.errors().toString());
        assertEquals(LoanStatus.CLOSED, queryRepository.getSummaryProjection(OWNER_ID, LOAN_ID).status());
    }

    @Test
    void ignoresForeignLoanTransactionsOnSameAccount() throws Exception {
        // Otros préstamos en la misma cuenta dejaron transacciones LOAN_* no
        // referenciadas: no deben entrar al timeline de este préstamo.
        seedLoan(100_000L, "CLOSED");
        seedMovement("mov-1", "CREATION", 100_000L, ACCOUNT_ID, null, 1_000L, null, 1_000L);
        seedPayment("pay-1", 100_000L, ACCOUNT_ID, null, 2_000L, 2_000L);
        seedTransaction("tx-foreign-1", "LOAN_REPAYMENT_PRINCIPAL_IN", 500_000L, ACCOUNT_ID, 1_500L);
        seedTransaction("tx-foreign-2", "LOAN_LENT_OUT", 900_000L, ACCOUNT_ID, 900L);

        HistoricalLoanReplayTool.ReplayResult result = tool.replay(OWNER_ID, LOAN_ID);

        assertTrue(result.success(), () -> String.join(" | ", result.errors()) + " status=" + result.status());
        LoanSummaryProjection summary = queryRepository.getSummaryProjection(OWNER_ID, LOAN_ID);
        assertEquals(LoanStatus.CLOSED, summary.status());
        assertEquals(1, summary.paymentCount());
    }

    @Test
    void normalizesInferredCreationBeforeHistoricalPayment() throws Exception {
        seedLoan(100_000L, "CLOSED", 2_000L);
        seedPayment("pay-1", 100_000L, ACCOUNT_ID, "tx-pay-1", 1_000L, 1_000L);
        seedTransaction("tx-pay-1", "LOAN_REPAYMENT_PRINCIPAL_IN", 100_000L, ACCOUNT_ID, 1_000L);
        seedTransaction("tx-foreign-create", "LOAN_LENT_OUT", 50_000L, ACCOUNT_ID, 500L);

        HistoricalLoanReplayTool.ReplayResult result = tool.replay(OWNER_ID, LOAN_ID);

        assertTrue(result.success(), () -> result.status() + " " + result.errors());
        LoanSummaryProjection summary = queryRepository.getSummaryProjection(OWNER_ID, LOAN_ID);
        assertEquals(LoanStatus.CLOSED, summary.status());
        assertEquals(100_000L, summary.totalPaidCents());
        assertEquals(1, summary.paymentCount());
    }

    @Test
    void failsWhenPaymentOccursBeforeCreation() throws Exception {
        seedLoan(100_000L, "OPEN");
        seedMovement("mov-1", "PAYMENT_IN", 100_000L, ACCOUNT_ID, null, 500L, null, 500L);
        seedMovement("mov-2", "CREATION", 100_000L, ACCOUNT_ID, null, 1_000L, null, 1_000L);

        HistoricalLoanReplayTool.ReplayResult result = tool.replay(OWNER_ID, LOAN_ID);

        assertFalse(result.success());
        assertEquals("PAYMENT_BEFORE_CREATION", result.status());
    }

    @Test
    void failsWhenPaymentExceedsPending() throws Exception {
        seedLoan(100_000L, "OPEN");
        seedMovement("mov-1", "CREATION", 100_000L, ACCOUNT_ID, null, 1_000L, null, 1_000L);
        seedPayment("pay-1", 150_000L, ACCOUNT_ID, null, 2_000L, 2_000L);

        HistoricalLoanReplayTool.ReplayResult result = tool.replay(OWNER_ID, LOAN_ID);

        assertFalse(result.success());
        assertEquals("PAYMENT_EXCEEDS_PENDING", result.status());
    }

    @Test
    void replaysWhenPrincipalMismatch() throws Exception {
        seedLoan(120_000L, "OPEN");
        seedMovement("mov-1", "CREATION", 100_000L, ACCOUNT_ID, null, 1_000L, null, 1_000L);

        HistoricalLoanReplayTool.ReplayResult result = tool.replay(OWNER_ID, LOAN_ID);

        assertTrue(result.success(), result.errors().toString());
        assertEquals("SUCCESS", result.status());
        assertEquals(120_000L, queryRepository.getSummaryProjection(OWNER_ID, LOAN_ID).principalCents());
        assertNull(journalTransactionId("ADJUSTMENT"));
    }

    @Test
    void reusesUniqueRemoteAdjustmentTransactionWithoutBackfillDuplicate() throws Exception {
        seedLoan(120_000L, "OPEN", 1_000L, 1_000L, 2_000L);
        seedTransaction("tx-create", "LOAN_LENT_OUT", 100_000L, ACCOUNT_ID, 1_000L);
        seedMovement("mov-1", "CREATION", 100_000L, ACCOUNT_ID, "tx-create", 1_000L, null, 1_000L);
        seedTransaction("tx-adjust", "LOAN_LENT_CORRECTION_OUT", 20_000L, ACCOUNT_ID, 1_500L);

        HistoricalLoanReplayTool.ReplayResult result = tool.replay(OWNER_ID, LOAN_ID);

        assertTrue(result.success(), result.errors().toString());
        assertEquals("tx-adjust", journalTransactionId("ADJUSTMENT"));

        CanonicalLoanTransactionBackfill.BackfillReport report = CanonicalLoanTransactionBackfill.run(database);
        assertEquals(0, report.transactionsCreated());
        assertEquals(2, countRows("transactions"));
    }

    @Test
    void reusesTransactionLinkedToRemoteAdjustmentMovement() throws Exception {
        seedLoan(120_000L, "OPEN", 1_000L, 1_000L, 2_000L);
        seedTransaction("tx-create", "LOAN_LENT_OUT", 100_000L, ACCOUNT_ID, 1_000L);
        seedMovement("mov-1", "CREATION", 100_000L, ACCOUNT_ID, "tx-create", 1_000L, null, 1_000L);
        seedTransaction("tx-adjust", "LOAN_LENT_CORRECTION_OUT", 20_000L, ACCOUNT_ID, 1_500L);
        seedMovement("mov-adjust", "ADJUSTMENT", 20_000L, ACCOUNT_ID, "tx-adjust", 1_500L, null, 1_500L);

        HistoricalLoanReplayTool.ReplayResult result = tool.replay(OWNER_ID, LOAN_ID);

        assertTrue(result.success(), result.errors().toString());
        assertEquals("tx-adjust", journalTransactionId("ADJUSTMENT"));

        CanonicalLoanTransactionBackfill.BackfillReport report = CanonicalLoanTransactionBackfill.run(database);
        assertEquals(0, report.transactionsCreated());
        assertEquals(2, countRows("transactions"));
    }

    @Test
    void doesNotReuseTransactionClaimedByAnotherLoan() throws Exception {
        seedLoan(120_000L, "OPEN", 1_000L, 1_000L, 2_000L);
        seedTransaction("tx-create", "LOAN_LENT_OUT", 100_000L, ACCOUNT_ID, 1_000L);
        seedMovement("mov-1", "CREATION", 100_000L, ACCOUNT_ID, "tx-create", 1_000L, null, 1_000L);
        seedTransaction("tx-adjust", "LOAN_LENT_CORRECTION_OUT", 20_000L, ACCOUNT_ID, 1_500L);
        seedLoanForId("loan-2", 50_000L, "OPEN", 1_200L, 1_200L, 1_200L);
        seedMovementForLoan("loan-2", "mov-foreign", "TOPUP", 20_000L, ACCOUNT_ID, "tx-adjust", 1_500L, null, 1_500L);

        HistoricalLoanReplayTool.ReplayResult result = tool.replay(OWNER_ID, LOAN_ID);

        assertTrue(result.success(), result.errors().toString());
        assertNull(journalTransactionId("ADJUSTMENT"));
        assertEquals(2, countRows("transactions"));
    }

    @Test
    void reusesUniqueRemoteTopupTransactionWithoutBackfillDuplicate() throws Exception {
        seedLoan(150_000L, "OPEN", 1_000L, 1_000L, 2_000L);
        seedTransaction("tx-create", "LOAN_LENT_OUT", 100_000L, ACCOUNT_ID, 1_000L);
        seedMovement("mov-1", "CREATION", 100_000L, ACCOUNT_ID, "tx-create", 1_000L, null, 1_000L);
        seedTransaction("tx-topup", "LOAN_LENT_TOPUP", 50_000L, ACCOUNT_ID, 1_500L);

        HistoricalLoanReplayTool.ReplayResult result = tool.replay(OWNER_ID, LOAN_ID);

        assertTrue(result.success(), result.errors().toString());
        assertEquals("tx-topup", journalTransactionId("ADJUSTMENT"));

        CanonicalLoanTransactionBackfill.BackfillReport report = CanonicalLoanTransactionBackfill.run(database);
        assertEquals(0, report.transactionsCreated());
        assertEquals(2, countRows("transactions"));
    }

    @Test
    void doesNotReuseWhenPrincipalTransactionCandidatesAreAmbiguous() throws Exception {
        seedLoan(120_000L, "OPEN", 1_000L, 1_000L, 2_000L);
        seedTransaction("tx-create", "LOAN_LENT_OUT", 100_000L, ACCOUNT_ID, 1_000L);
        seedMovement("mov-1", "CREATION", 100_000L, ACCOUNT_ID, "tx-create", 1_000L, null, 1_000L);
        seedTransaction("tx-adjust-1", "LOAN_LENT_CORRECTION_OUT", 20_000L, ACCOUNT_ID, 1_500L);
        seedTransaction("tx-adjust-2", "LOAN_LENT_CORRECTION_OUT", 20_000L, ACCOUNT_ID, 1_600L);

        HistoricalLoanReplayTool.ReplayResult result = tool.replay(OWNER_ID, LOAN_ID);

        assertTrue(result.success(), result.errors().toString());
        assertNull(journalTransactionId("ADJUSTMENT"));
        assertEquals(3, countRows("transactions"));
    }

    @Test
    void secondReplayAfterReusingTransactionDoesNotCreateMoreTransactions() throws Exception {
        seedLoan(120_000L, "OPEN", 1_000L, 1_000L, 2_000L);
        seedTransaction("tx-create", "LOAN_LENT_OUT", 100_000L, ACCOUNT_ID, 1_000L);
        seedMovement("mov-1", "CREATION", 100_000L, ACCOUNT_ID, "tx-create", 1_000L, null, 1_000L);
        seedTransaction("tx-adjust", "LOAN_LENT_CORRECTION_OUT", 20_000L, ACCOUNT_ID, 1_500L);

        HistoricalLoanReplayTool.ReplayResult first = tool.replay(OWNER_ID, LOAN_ID);
        HistoricalLoanReplayTool.ReplayResult second = tool.replay(OWNER_ID, LOAN_ID);

        assertTrue(first.success(), first.errors().toString());
        assertTrue(second.success(), second.errors().toString());
        assertEquals(first.summary().journalFingerprint(), second.summary().journalFingerprint());
        assertEquals("tx-adjust", journalTransactionId("ADJUSTMENT"));
        assertEquals(2, countRows("loan_journal_v1"));
        assertEquals(2, countRows("transactions"));

        CanonicalLoanTransactionBackfill.BackfillReport report = CanonicalLoanTransactionBackfill.run(database);
        assertEquals(0, report.transactionsCreated());
        assertEquals(2, countRows("transactions"));
    }

    @Test
    void rollsBackWhenAggregateRejectsPayment() throws Exception {
        // Pre-create a canonical payment to verify the rollback preserves it.
        myfinances.domain.loan.aggregate.LoanCommandResult created = service.process(
            new myfinances.domain.loan.commands.CreateLoanCommand(
                new LoanCommandEnvelope(LoanCommandType.CREATE_LOAN, UUID_s(), LOAN_ID, OWNER_ID, null, 1_000L, OWNER_ID, OWNER_ID),
                LoanType.LENT,
                100_000L,
                "Counterparty",
                "COP",
                ACCOUNT_ID,
                null,
                null
            )
        );
        assertEquals(Outcome.APPLIED, created.outcome());

        service.process(new RegisterPaymentCommand(
            new LoanCommandEnvelope(LoanCommandType.REGISTER_PAYMENT, UUID_s(), LOAN_ID, OWNER_ID, created.currentSnapshot().journalFingerprint(), 2_000L, OWNER_ID, OWNER_ID),
            100_000L,
            ACCOUNT_ID,
            null,
            null
        ));

        LoanSummaryProjection before = queryRepository.getSummaryProjection(OWNER_ID, LOAN_ID);
        assertNotNull(before);
        assertEquals(1, before.paymentCount());
        assertEquals(LoanStatus.CLOSED, before.status());

        // Legacy history valid in plan but with an invalid payment amount.
        seedLoan(100_000L, "OPEN");
        seedMovement("mov-1", "CREATION", 100_000L, ACCOUNT_ID, null, 1_000L, null, 1_000L);
        seedPayment("pay-1", -200_000L, ACCOUNT_ID, null, 2_000L, 2_000L);

        HistoricalLoanReplayTool.ReplayResult result = tool.replay(OWNER_ID, LOAN_ID);

        assertFalse(result.success());
        assertEquals("REPLAY_ABORTED", result.status());

        LoanSummaryProjection after = queryRepository.getSummaryProjection(OWNER_ID, LOAN_ID);
        assertNotNull(after);
        assertEquals(before.principalCents(), after.principalCents());
        assertEquals(before.totalPaidCents(), after.totalPaidCents());
        assertEquals(before.paymentCount(), after.paymentCount());
        assertEquals(before.status(), after.status());
    }

    private String UUID_s() {
        return UUID.randomUUID().toString();
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
                "INSERT INTO categories (id, user_uid, name, parent_id, kind, created_at_epoch_sec, updated_at_epoch_sec) VALUES " +
                "('cat-1', '" + OWNER_ID + "', 'Préstamos', NULL, NULL, 0, 0)"
            );
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS loan_movements (" +
                "  id TEXT PRIMARY KEY," +
                "  loan_id TEXT NOT NULL," +
                "  user_uid TEXT NOT NULL," +
                "  movement_type TEXT NOT NULL," +
                "  amount_cents INTEGER NOT NULL," +
                "  account_id TEXT NULL," +
                "  linked_transaction_id TEXT NULL," +
                "  note TEXT NULL," +
                "  occurred_at_epoch_sec INTEGER NOT NULL," +
                "  created_at_epoch_sec INTEGER NOT NULL," +
                "  updated_at_epoch_sec INTEGER NOT NULL," +
                "  updated_by TEXT NULL," +
                "  pending_sync INTEGER NOT NULL DEFAULT 0" +
                ")"
            );
        }
    }

    private void seedLoan(long principal, String status) throws Exception {
        seedLoan(principal, status, 1_000L);
    }

    private void seedLoan(long principal, String status, long occurredAt) throws Exception {
        seedLoan(principal, status, occurredAt, occurredAt, occurredAt);
    }

    private void seedLoan(long principal, String status, long occurredAt, long createdAt, long updatedAt) throws Exception {
        seedLoanForId(LOAN_ID, principal, status, occurredAt, createdAt, updatedAt);
    }

    private void seedLoanForId(String loanId, long principal, String status, long occurredAt, long createdAt, long updatedAt) throws Exception {
        String sql =
            "INSERT INTO loans (id, user_uid, type, counterparty_name, principal_cents, currency, status, notes, " +
            "occurred_at_epoch_sec, account_id, created_at_epoch_sec, updated_at_epoch_sec, updated_by, pending_sync) " +
            "VALUES (?, ?, 'LENT', 'Counterparty', ?, 'COP', ?, NULL, ?, ?, ?, ?, NULL, 0)";
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, loanId);
            ps.setString(2, OWNER_ID);
            ps.setLong(3, principal);
            ps.setString(4, status);
            ps.setLong(5, occurredAt);
            ps.setString(6, ACCOUNT_ID);
            ps.setLong(7, createdAt);
            ps.setLong(8, updatedAt);
            ps.executeUpdate();
        }
    }

    private void seedMovement(String id, String movementType, long amount, String accountId, String txId, long occurredAt, String note, long createdAt) throws Exception {
        seedMovementForLoan(LOAN_ID, id, movementType, amount, accountId, txId, occurredAt, note, createdAt);
    }

    private void seedMovementForLoan(String loanId, String id, String movementType, long amount, String accountId, String txId, long occurredAt, String note, long createdAt) throws Exception {
        String sql =
            "INSERT INTO loan_movements (id, loan_id, user_uid, movement_type, amount_cents, account_id, " +
            "linked_transaction_id, note, occurred_at_epoch_sec, created_at_epoch_sec, updated_at_epoch_sec, updated_by, pending_sync) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 0)";
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, loanId);
            ps.setString(3, OWNER_ID);
            ps.setString(4, movementType);
            ps.setLong(5, amount);
            ps.setString(6, accountId);
            ps.setString(7, txId);
            ps.setString(8, note);
            ps.setLong(9, occurredAt);
            ps.setLong(10, createdAt);
            ps.setLong(11, createdAt);
            ps.executeUpdate();
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

    private String journalTransactionId(String eventType) throws Exception {
        String sql =
            "SELECT transaction_id FROM loan_journal_v1 " +
            "WHERE owner_id = ? AND loan_id = ? AND event_type = ? " +
            "ORDER BY occurred_at DESC, event_id DESC LIMIT 1";
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, OWNER_ID);
            ps.setString(2, LOAN_ID);
            ps.setString(3, eventType);
            try (java.sql.ResultSet result = ps.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private int countRows(String table) throws Exception {
        try (Connection connection = database.openConnection();
             PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
             java.sql.ResultSet result = ps.executeQuery()) {
            return result.next() ? result.getInt(1) : 0;
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
}
