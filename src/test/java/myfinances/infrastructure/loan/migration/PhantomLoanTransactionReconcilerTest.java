package myfinances.infrastructure.loan.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myfinaces.auth.AuthSession;
import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.AppSchema;
import com.myfinaces.db.SqliteDatabase;
import com.myfinaces.db.TransactionRepository;
import com.myfinaces.sync.FirestoreSyncService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.UUID;
import myfinances.application.loan.LoanApplicationService;
import myfinances.domain.loan.aggregate.Outcome;
import myfinances.domain.loan.commands.CreateLoanCommand;
import myfinances.domain.loan.commands.LoanCommandEnvelope;
import myfinances.domain.loan.commands.LoanCommandType;
import myfinances.domain.loan.journal.LoanType;
import myfinances.infrastructure.loan.di.LoanApplicationServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sprint 7J.3 — limpieza determinística de transacciones fantasma creadas por
 * materialización de ADJUSTMENT sintéticos (pre-7J.2).
 */
class PhantomLoanTransactionReconcilerTest {
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
    private AuthSession session;
    private FirestoreSyncService sync;

    @BeforeEach
    void setUp() throws Exception {
        database = new SqliteDatabase(tempDir.resolve("phantom.db"));
        AppSchema.init(database);
        seedSupportRows();
        service = new LoanApplicationServiceFactory(database).loanApplicationService();
        txRepo = new TransactionRepository(database);
        accountRepo = new AccountRepository(database);
        session = new AuthSession(OWNER_ID, "t@t", "T", "bad-token", "", 0L);
        sync = new FirestoreSyncService("test-project");
    }

    /** Caso 1+2: fantasma histórico → se elimina, saldo corregido, 2ª ejecución sin cambios. */
    @Test
    void deletesHistoricalPhantomAndIsIdempotent() throws Exception {
        seedLinkedLoan();
        long occurredAt = OCCURRED_AT + 20;
        String synthEventId = syntheticAdjustmentId(occurredAt);
        insertJournalEvent(synthEventId, "ADJUSTMENT", 50_000L, occurredAt, null, "Ajuste incremental remoto");
        String phantomTxId = phantomTransactionId(synthEventId);
        insertTransaction(phantomTxId, "LOAN_LENT_CORRECTION_OUT", 50_000L, occurredAt);

        long before = accountRepo.computeBalanceCents(OWNER_ID, ACCOUNT_ID);
        assertEquals(-300_000L, before); // -250k creación -50k fantasma

        PhantomLoanTransactionReconciler.CleanupReport report =
            PhantomLoanTransactionReconciler.reconcile(database, sync, session);
        assertEquals(1, report.syntheticAdjustmentsScanned());
        assertEquals(1, report.phantomsDeleted());
        assertTrue(report.errors().isEmpty());
        assertNull(txRepo.getForSyncByIdOrNull(OWNER_ID, phantomTxId));
        assertEquals(-250_000L, accountRepo.computeBalanceCents(OWNER_ID, ACCOUNT_ID));

        PhantomLoanTransactionReconciler.CleanupReport second =
            PhantomLoanTransactionReconciler.reconcile(database, sync, session);
        assertEquals(0, second.phantomsDeleted());
        assertEquals(-250_000L, accountRepo.computeBalanceCents(OWNER_ID, ACCOUNT_ID));
    }

    /** Caso 3+4+5: CREATION/TOPUP/PAYMENT legítimos nunca se eliminan. */
    @Test
    void neverDeletesLegitFinancialTransactions() throws Exception {
        seedLinkedLoan();
        insertJournalEvent("evt-topup", "TOPUP", 100_000L, OCCURRED_AT + 10, "tx-topup", null);
        insertJournalEvent("evt-pay", "PAYMENT", 40_000L, OCCURRED_AT + 11, "tx-pay", null);
        insertTransaction("tx-topup", "LOAN_LENT_TOPUP", 100_000L, OCCURRED_AT + 10);
        insertTransaction("tx-pay", "LOAN_REPAYMENT_PRINCIPAL_IN", 40_000L, OCCURRED_AT + 11);

        // Incluso con un ajuste sintético+fantasma presente, solo éste se elimina.
        String synthEventId = syntheticAdjustmentId(OCCURRED_AT + 20);
        insertJournalEvent(synthEventId, "ADJUSTMENT", 50_000L, OCCURRED_AT + 20, null, "Ajuste incremental remoto");
        insertTransaction(phantomTransactionId(synthEventId), "LOAN_LENT_CORRECTION_OUT", 50_000L, OCCURRED_AT + 20);

        PhantomLoanTransactionReconciler.reconcile(database, sync, session);

        assertNotNull(txRepo.getForSyncByIdOrNull(OWNER_ID, "tx-creation"));
        assertNotNull(txRepo.getForSyncByIdOrNull(OWNER_ID, "tx-topup"));
        assertNotNull(txRepo.getForSyncByIdOrNull(OWNER_ID, "tx-pay"));
        assertNull(txRepo.getForSyncByIdOrNull(OWNER_ID, phantomTransactionId(synthEventId)));
    }

    /** Caso 6: ADJUSTMENT real histórico (event_id no sintético) y su tx materializada sobreviven. */
    @Test
    void neverDeletesRealAdjustmentTransactions() throws Exception {
        seedLinkedLoan();
        String realEventId = "evt-adj-real";
        insertJournalEvent(realEventId, "ADJUSTMENT", 30_000L, OCCURRED_AT + 10, null,
            "Corrección de préstamo otorgado a: Ana Pérez");
        String realTxId = phantomTransactionId(realEventId); // mismo esquema que el backfill
        insertTransaction(realTxId, "LOAN_LENT_CORRECTION_OUT", 30_000L, OCCURRED_AT + 10);

        PhantomLoanTransactionReconciler.CleanupReport report =
            PhantomLoanTransactionReconciler.reconcile(database, sync, session);
        assertEquals(0, report.phantomsDeleted());
        assertNotNull(txRepo.getForSyncByIdOrNull(OWNER_ID, realTxId));
    }

    /** Caso 7: si el doc remoto sobrevive y el pull resucita la fila, el siguiente sync la vuelve a eliminar. */
    @Test
    void resurrectedPhantomIsDeletedAgainOnNextSync() throws Exception {
        seedLinkedLoan();
        long occurredAt = OCCURRED_AT + 20;
        String synthEventId = syntheticAdjustmentId(occurredAt);
        insertJournalEvent(synthEventId, "ADJUSTMENT", 50_000L, occurredAt, null, "Ajuste incremental remoto");
        String phantomTxId = phantomTransactionId(synthEventId);
        insertTransaction(phantomTxId, "LOAN_LENT_CORRECTION_OUT", 50_000L, occurredAt);

        assertEquals(1, PhantomLoanTransactionReconciler.reconcile(database, sync, session).phantomsDeleted());

        // El remoto aún existía: el siguiente pull reinserta la fila local.
        insertTransaction(phantomTxId, "LOAN_LENT_CORRECTION_OUT", 50_000L, occurredAt);
        PhantomLoanTransactionReconciler.CleanupReport retry =
            PhantomLoanTransactionReconciler.reconcile(database, sync, session);
        assertEquals(1, retry.phantomsDeleted());
        assertNull(txRepo.getForSyncByIdOrNull(OWNER_ID, phantomTxId));
        assertEquals(-250_000L, accountRepo.computeBalanceCents(OWNER_ID, ACCOUNT_ID));
    }

    /** Caso 8: base limpia → 0 cambios. */
    @Test
    void cleanBaseProducesZeroChanges() throws Exception {
        seedLinkedLoan();
        PhantomLoanTransactionReconciler.CleanupReport report =
            PhantomLoanTransactionReconciler.reconcile(database, sync, session);
        assertEquals(0, report.syntheticAdjustmentsScanned());
        assertEquals(0, report.phantomsDeleted());
        assertTrue(report.errors().isEmpty());
        assertEquals(-250_000L, accountRepo.computeBalanceCents(OWNER_ID, ACCOUNT_ID));
    }

    /** Una tx que casualmente coincida en firma pero esté referenciada por otro evento no se toca. */
    @Test
    void skipsTransactionReferencedByAnotherJournalEvent() throws Exception {
        seedLinkedLoan();
        long occurredAt = OCCURRED_AT + 20;
        String synthEventId = syntheticAdjustmentId(occurredAt);
        insertJournalEvent(synthEventId, "ADJUSTMENT", 50_000L, occurredAt, null, "Ajuste incremental remoto");
        String sharedTxId = phantomTransactionId(synthEventId);
        insertTransaction(sharedTxId, "LOAN_LENT_CORRECTION_OUT", 50_000L, occurredAt);
        // Otro evento (corrección real) referencia la misma transacción.
        insertJournalEvent("evt-real-ref", "ADJUSTMENT", 50_000L, occurredAt, sharedTxId, "Corrección real");

        PhantomLoanTransactionReconciler.CleanupReport report =
            PhantomLoanTransactionReconciler.reconcile(database, sync, session);
        assertEquals(0, report.phantomsDeleted());
        assertNotNull(txRepo.getForSyncByIdOrNull(OWNER_ID, sharedTxId));
    }

    // --- helpers ---

    private void seedLinkedLoan() throws Exception {
        var created = service.process(createLoanCommand("tx-creation", LoanType.LENT, 250_000L, "Ana Pérez", "COP", null));
        assertEquals(Outcome.APPLIED, created.outcome());
        insertTransaction("tx-creation", "LOAN_LENT_OUT", 250_000L, OCCURRED_AT);
    }

    private String syntheticAdjustmentId(long occurredAt) {
        return CanonicalLoanEventIds.deterministic(LOAN_ID, "ADJUSTMENT", "synth:adjust:" + LOAN_ID, occurredAt);
    }

    private String phantomTransactionId(String eventId) {
        return UUID.nameUUIDFromBytes(("canonical-loan-tx:" + eventId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private void insertTransaction(String id, String kind, long amountCents, long occurredAt) throws Exception {
        try (Connection c = database.openConnection(); PreparedStatement ps = c.prepareStatement(
            "INSERT INTO transactions (id, user_uid, account_id, category_id, kind, amount_cents, occurred_at_epoch_sec, " +
            "note, created_at_epoch_sec, updated_at_epoch_sec, pending_sync) VALUES (?, ?, ?, 'cat-1', ?, ?, ?, NULL, ?, ?, 0)")) {
            ps.setString(1, id);
            ps.setString(2, OWNER_ID);
            ps.setString(3, ACCOUNT_ID);
            ps.setString(4, kind);
            ps.setLong(5, amountCents);
            ps.setLong(6, occurredAt);
            ps.setLong(7, occurredAt);
            ps.setLong(8, occurredAt);
            ps.executeUpdate();
        }
    }

    private void insertJournalEvent(String eventId, String eventType, Long amountCents,
                                    long occurredAt, String transactionId, String note) throws Exception {
        try (Connection c = database.openConnection(); PreparedStatement ps = c.prepareStatement(
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
                ps.setNull(6, Types.INTEGER);
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

    private CreateLoanCommand createLoanCommand(String transactionId, LoanType loanType, long principalCents,
                                                String counterparty, String currency, String notes) {
        LoanCommandEnvelope envelope = new LoanCommandEnvelope(
            LoanCommandType.CREATE_LOAN, UUID.randomUUID().toString(), LOAN_ID, OWNER_ID, null, OCCURRED_AT, OWNER_ID, OWNER_ID);
        return new CreateLoanCommand(envelope, loanType, principalCents, counterparty, currency, ACCOUNT_ID, transactionId, notes);
    }

    private void seedSupportRows() throws Exception {
        try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                "INSERT INTO users (uid, email, created_at_epoch_sec, updated_at_epoch_sec) VALUES " +
                    "('owner-1', 'owner@example.com', 1700000000, 1700000000)");
            statement.executeUpdate(
                "INSERT INTO accounts (id, user_uid, name, type, currency, created_at_epoch_sec, updated_at_epoch_sec) VALUES " +
                    "('account-1', 'owner-1', 'Cuenta principal', 'CHECKING', 'COP', 1700000000, 1700000000)");
            statement.executeUpdate(
                "INSERT INTO categories (id, user_uid, name, parent_id, kind, created_at_epoch_sec, updated_at_epoch_sec) VALUES " +
                    "('cat-1', 'owner-1', 'Préstamos', NULL, NULL, 0, 0)");
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS loan_movements (" +
                "  id TEXT PRIMARY KEY, loan_id TEXT NOT NULL, user_uid TEXT NOT NULL, movement_type TEXT NOT NULL, " +
                "  amount_cents INTEGER NOT NULL, account_id TEXT NULL, linked_transaction_id TEXT NULL, note TEXT NULL, " +
                "  occurred_at_epoch_sec INTEGER NOT NULL, created_at_epoch_sec INTEGER NOT NULL, " +
                "  updated_at_epoch_sec INTEGER NOT NULL, updated_by TEXT NULL, pending_sync INTEGER NOT NULL DEFAULT 0)");
        }
    }
}
