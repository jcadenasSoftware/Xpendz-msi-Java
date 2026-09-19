package myfinances.infrastructure.loan.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myfinaces.auth.AuthSession;
import com.myfinaces.db.AppSchema;
import com.myfinaces.db.LoanMovementRepository;
import com.myfinaces.db.LoanPaymentRepository;
import com.myfinaces.db.SqliteDatabase;
import com.myfinaces.sync.FirestoreSyncService;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import myfinances.application.loan.LoanApplicationService;
import myfinances.application.loan.LoanCommandFactory;
import myfinances.domain.loan.aggregate.Outcome;
import myfinances.domain.loan.projection.LoanProjectionQueryRepository;
import myfinances.domain.loan.projection.LoanSummaryProjection;
import myfinances.domain.loan.service.error.LoanAggregateErrorCode;
import myfinances.domain.loan.service.error.LoanAggregateException;
import myfinances.infrastructure.loan.di.LoanApplicationServiceFactory;
import myfinances.infrastructure.loan.replay.HistoricalLoanReplayTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sprint 7A — convergencia de reversión de pagos.
 * Cubre: identidad compartida docId↔eventId (mismo UUID en Android y Desktop),
 * limpieza completa de artefactos de transporte tras revertir, idempotencia
 * del reconciliador y no resurrección del pago en replay histórico.
 */
class ReversalConvergenceTest {
    private static final String OWNER_ID = "owner-1";
    private static final String LOAN_ID = "loan-1";
    private static final String ACCOUNT_ID = "account-1";
    private static final String REMOTE_DEVICE = "android-device";

    @TempDir
    Path temporaryDirectory;

    private SqliteDatabase database;
    private LoanApplicationService service;
    private LoanProjectionQueryRepository queryRepository;
    private LoanCommandFactory commandFactory;

    @BeforeEach
    void setUp() throws Exception {
        database = new SqliteDatabase(temporaryDirectory.resolve("reversal.db"));
        AppSchema.init(database);
        seedSupportRows();
        LoanApplicationServiceFactory factory = new LoanApplicationServiceFactory(database);
        service = factory.loanApplicationService();
        queryRepository = factory.projectionQueryRepository();
        commandFactory = new LoanCommandFactory();
    }

    /**
     * El docId remoto (UUID del dispositivo origen) se adopta como
     * eventId/operationId del journal local: identidad compartida que permite
     * eliminar el documento correcto al revertir desde cualquier dispositivo.
     */
    @Test
    void transportedPaymentDocIdBecomesCanonicalEventId() throws Exception {
        String docId = "11111111-2222-4333-8444-555555555555";
        seedLoan(100_000L, "OPEN", REMOTE_DEVICE);
        seedTransaction("tx-pay-1", "LOAN_REPAYMENT_PRINCIPAL_IN", 40_000L, 2_000L);
        seedPayment(docId, 40_000L, "tx-pay-1", 2_000L);
        LegacyLoanMigration.migrate(database, service);

        List<String> paymentEventIds = journalEventIdsByType("PAYMENT");
        assertEquals(List.of(docId), paymentEventIds);
        assertEquals(1, queryRepository.getSummaryProjection(OWNER_ID, LOAN_ID).paymentCount());
    }

    /** Congela el algoritmo compartido: Android y Desktop deben producir este UUID. */
    @Test
    void sharedDeterministicIdMatchesGoldenValue() {
        String id = CanonicalLoanEventIds.deterministic(LOAN_ID, "PAYMENT", "pay:pay-legacy-1", 2_000L);
        assertEquals("f8ba489a-bdf6-4404-9c51-dfe9e1658ecd", id);
        assertEquals(4, UUID.fromString(id).version());
        assertEquals("11111111-2222-4333-8444-555555555555",
            CanonicalLoanEventIds.forTransportPayment(LOAN_ID, "11111111-2222-4333-8444-555555555555", 2_000L));
        assertEquals(id, CanonicalLoanEventIds.forTransportPayment(LOAN_ID, "pay-legacy-1", 2_000L));
    }

    /**
     * Revertir elimina el pago, el movimiento y la transacción de transporte;
     * el journal conserva PAYMENT + REVERSAL (historial intacto).
     */
    @Test
    void reversalRemovesAllTransportArtifactsAndPreservesJournal() throws Exception {
        seedLoan(100_000L, "OPEN", REMOTE_DEVICE);
        seedTransaction("tx-pay-1", "LOAN_REPAYMENT_PRINCIPAL_IN", 40_000L, 2_000L);
        seedPayment("pay-1", 40_000L, "tx-pay-1", 2_000L);
        seedMovement("mov-pay-1", "PAYMENT_IN", 40_000L, "tx-pay-1", 2_000L);
        LegacyLoanMigration.migrate(database, service);
        assertEquals(1, queryRepository.getSummaryProjection(OWNER_ID, LOAN_ID).paymentCount());

        String paymentEventId = journalEventIdsByType("PAYMENT").get(0);
        LoanSummaryProjection summary = queryRepository.getSummaryProjection(OWNER_ID, LOAN_ID);
        var command = commandFactory.reversePayment(
            OWNER_ID, LOAN_ID, paymentEventId, summary.journalFingerprint(), "Error", null);
        assertEquals(Outcome.APPLIED, service.process(command).outcome());

        AuthSession session = new AuthSession(OWNER_ID, "t@t", "T", "bad-token", "", 0L);
        FirestoreSyncService sync = new FirestoreSyncService("test-project");
        ReversedLoanPaymentReconciler.reconcile(database, sync, session);

        assertEquals(0, countRows("loan_payments"));
        assertEquals(0, countRowsWhere("loan_movements", "movement_type != 'CREATION'"));
        // La transacción LOAN_REPAYMENT_* se elimina; la LOAN_LENT_OUT de
        // creación (backfill canónico) permanece — no pertenece al pago.
        assertEquals(0, countRowsWhere("transactions", "kind = 'LOAN_REPAYMENT_PRINCIPAL_IN'"));

        LoanSummaryProjection after = queryRepository.getSummaryProjection(OWNER_ID, LOAN_ID);
        assertEquals(0, after.paymentCount());
        assertEquals(0L, after.totalPaidCents());
        assertEquals(100_000L, after.pendingCents());
        assertEquals(List.of("CREATION", "PAYMENT", "REVERSAL"), journalEventTypes());
    }

    /**
     * Tras la limpieza, ni la migración ni un replay forzado reconstruyen el
     * pago revertido. Reconciliaciones repetidas son idempotentes.
     */
    @Test
    void replayDoesNotResurrectReversedPayment() throws Exception {
        String docId = "99999999-8888-4777-8666-555555555555";
        seedLoan(100_000L, "OPEN", REMOTE_DEVICE);
        seedTransaction("tx-pay-1", "LOAN_REPAYMENT_PRINCIPAL_IN", 40_000L, 2_000L);
        seedPayment(docId, 40_000L, "tx-pay-1", 2_000L);
        LegacyLoanMigration.migrate(database, service);

        LoanSummaryProjection summary = queryRepository.getSummaryProjection(OWNER_ID, LOAN_ID);
        service.process(commandFactory.reversePayment(
            OWNER_ID, LOAN_ID, docId, summary.journalFingerprint(), "Error", null));

        AuthSession session = new AuthSession(OWNER_ID, "t@t", "T", "bad-token", "", 0L);
        FirestoreSyncService sync = new FirestoreSyncService("test-project");
        ReversedLoanPaymentReconciler.reconcile(database, sync, session);
        ReversedLoanPaymentReconciler.reconcile(database, sync, session);
        LegacyLoanMigration.migrate(database, service);
        LegacyLoanMigration.migrate(database, service);

        HistoricalLoanReplayTool.ReplayResult replay =
            new HistoricalLoanReplayTool(database).replay(OWNER_ID, LOAN_ID);
        assertTrue(replay.success());

        LoanSummaryProjection after = queryRepository.getSummaryProjection(OWNER_ID, LOAN_ID);
        assertNotNull(after);
        assertEquals(0, after.paymentCount());
        assertEquals(0L, after.totalPaidCents());
        assertEquals(100_000L, after.pendingCents());
        assertTrue(journalEventIdsByType("PAYMENT").isEmpty());
    }

    /**
     * Sprint 7H — aunque la UI/guardia impidan la doble ejecución, la capa de
     * aplicación también rechaza un segundo ReversePaymentCommand sobre el
     * mismo pago: el journal conserva una única REVERSAL y el pending no se
     * restaura dos veces.
     */
    @Test
    void secondReverseCommandForSamePaymentIsRejected() throws Exception {
        seedLoan(100_000L, "OPEN", REMOTE_DEVICE);
        seedTransaction("tx-pay-1", "LOAN_REPAYMENT_PRINCIPAL_IN", 40_000L, 2_000L);
        seedPayment("pay-1", 40_000L, "tx-pay-1", 2_000L);
        LegacyLoanMigration.migrate(database, service);

        String paymentEventId = journalEventIdsByType("PAYMENT").get(0);
        LoanSummaryProjection summary = queryRepository.getSummaryProjection(OWNER_ID, LOAN_ID);
        var first = commandFactory.reversePayment(
            OWNER_ID, LOAN_ID, paymentEventId, summary.journalFingerprint(), "Error", null);
        assertEquals(Outcome.APPLIED, service.process(first).outcome());

        LoanSummaryProjection refreshed = queryRepository.getSummaryProjection(OWNER_ID, LOAN_ID);
        var second = commandFactory.reversePayment(
            OWNER_ID, LOAN_ID, paymentEventId, refreshed.journalFingerprint(), "Error", null);
        LoanAggregateException ex = assertThrows(LoanAggregateException.class, () -> service.process(second));
        assertEquals(LoanAggregateErrorCode.PAYMENT_ALREADY_REVERSED, ex.code());

        assertEquals(List.of("CREATION", "PAYMENT", "REVERSAL"), journalEventTypes());
        assertEquals(100_000L, queryRepository.getSummaryProjection(OWNER_ID, LOAN_ID).pendingCents());
    }

    private void seedSupportRows() throws Exception {
        try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                "INSERT INTO users (uid, email, created_at_epoch_sec, updated_at_epoch_sec) VALUES " +
                "('" + OWNER_ID + "', 'test@example.com', 0, 0) ON CONFLICT(uid) DO NOTHING");
            statement.executeUpdate(
                "INSERT INTO accounts (id, user_uid, name, type, currency, created_at_epoch_sec, updated_at_epoch_sec) VALUES " +
                "('" + ACCOUNT_ID + "', '" + OWNER_ID + "', 'Cuenta', 'CASH', 'COP', 0, 0)");
            statement.executeUpdate(
                "INSERT INTO categories (id, user_uid, name, parent_id, kind, created_at_epoch_sec, updated_at_epoch_sec) VALUES " +
                "('cat-1', '" + OWNER_ID + "', 'Préstamos', NULL, NULL, 0, 0)");
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
                ")");
        }
    }

    private void seedLoan(long principal, String status, String updatedBy) throws Exception {
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO loans (id, user_uid, type, counterparty_name, principal_cents, currency, status, notes, " +
            "occurred_at_epoch_sec, account_id, created_at_epoch_sec, updated_at_epoch_sec, updated_by, pending_sync) " +
            "VALUES (?, ?, 'LENT', 'Counterparty', ?, 'COP', ?, NULL, ?, ?, ?, ?, ?, 0)")) {
            ps.setString(1, LOAN_ID);
            ps.setString(2, OWNER_ID);
            ps.setLong(3, principal);
            ps.setString(4, status);
            ps.setLong(5, 1_000L);
            ps.setString(6, ACCOUNT_ID);
            ps.setLong(7, 1_000L);
            ps.setLong(8, 1_000L);
            ps.setString(9, updatedBy);
            ps.executeUpdate();
        }
    }

    private void seedPayment(String id, long principal, String txId, long occurredAt) throws Exception {
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO loan_payments (id, loan_id, user_uid, account_id, principal_cents, occurred_at_epoch_sec, " +
            "linked_transaction_id, note, created_at_epoch_sec, updated_at_epoch_sec, updated_by, pending_sync) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, 0)")) {
            ps.setString(1, id);
            ps.setString(2, LOAN_ID);
            ps.setString(3, OWNER_ID);
            ps.setString(4, ACCOUNT_ID);
            ps.setLong(5, principal);
            ps.setLong(6, occurredAt);
            ps.setString(7, txId);
            ps.setLong(8, occurredAt);
            ps.setLong(9, occurredAt);
            ps.setString(10, REMOTE_DEVICE);
            ps.executeUpdate();
        }
    }

    private void seedMovement(String id, String type, long amountCents, String txId, long occurredAt) throws Exception {
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO loan_movements (id, loan_id, user_uid, movement_type, amount_cents, account_id, " +
            "linked_transaction_id, note, occurred_at_epoch_sec, created_at_epoch_sec, updated_at_epoch_sec, updated_by, pending_sync) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, 0)")) {
            ps.setString(1, id);
            ps.setString(2, LOAN_ID);
            ps.setString(3, OWNER_ID);
            ps.setString(4, type);
            ps.setLong(5, amountCents);
            ps.setString(6, ACCOUNT_ID);
            ps.setString(7, txId);
            ps.setLong(8, occurredAt);
            ps.setLong(9, occurredAt);
            ps.setLong(10, occurredAt);
            ps.setString(11, REMOTE_DEVICE);
            ps.executeUpdate();
        }
    }

    private void seedTransaction(String id, String kind, long amountCents, long occurredAt) throws Exception {
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO transactions (id, user_uid, account_id, category_id, kind, amount_cents, occurred_at_epoch_sec, " +
            "note, created_at_epoch_sec, updated_at_epoch_sec, pending_sync) " +
            "VALUES (?, ?, ?, 'cat-1', ?, ?, ?, NULL, ?, ?, 0)")) {
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

    private List<String> journalEventIdsByType(String type) throws Exception {
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(
            "SELECT event_id FROM loan_journal_v1 WHERE owner_id = ? AND loan_id = ? AND event_type = ? " +
            "ORDER BY occurred_at, recorded_at, event_id")) {
            ps.setString(1, OWNER_ID);
            ps.setString(2, LOAN_ID);
            ps.setString(3, type);
            try (ResultSet result = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (result.next()) {
                    out.add(result.getString(1));
                }
                return out;
            }
        }
    }

    private List<String> journalEventTypes() throws Exception {
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(
            "SELECT event_type FROM loan_journal_v1 WHERE owner_id = ? AND loan_id = ? " +
            "ORDER BY occurred_at, recorded_at, event_id")) {
            ps.setString(1, OWNER_ID);
            ps.setString(2, LOAN_ID);
            try (ResultSet result = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (result.next()) {
                    out.add(result.getString(1));
                }
                return out;
            }
        }
    }

    private int countRows(String table) throws Exception {
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(
            "SELECT COUNT(*) FROM " + table + " WHERE user_uid = ?")) {
            ps.setString(1, OWNER_ID);
            try (ResultSet result = ps.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    private int countRowsWhere(String table, String where) throws Exception {
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(
            "SELECT COUNT(*) FROM " + table + " WHERE user_uid = ? AND " + where)) {
            ps.setString(1, OWNER_ID);
            try (ResultSet result = ps.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }
}
