package myfinances.infrastructure.loan.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.myfinaces.db.AppSchema;
import com.myfinaces.db.SqliteDatabase;
import com.myfinaces.sync.DeviceId;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import myfinances.application.loan.LoanApplicationService;
import myfinances.domain.loan.projection.LoanProjectionQueryRepository;
import myfinances.domain.loan.projection.LoanSummaryProjection;
import myfinances.infrastructure.loan.di.LoanApplicationServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cobertura de la re-ingesta incremental: cuando la fila de transporte
 * {@code loans} diverge del estado canónico local, la migración debe
 * reconstruir el journal vía {@code HistoricalLoanReplayTool} y dejar
 * journal/snapshot/projection convergentes con el estado remoto.
 */
class LegacyLoanMigrationIncrementalTest {
    private static final String OWNER_ID = "owner-1";
    private static final String LOAN_ID = "loan-1";
    private static final String ACCOUNT_ID = "account-1";
    private static final String REMOTE_DEVICE = "android-device";

    @TempDir
    Path temporaryDirectory;

    private SqliteDatabase database;
    private LoanApplicationService service;
    private LoanProjectionQueryRepository queryRepository;

    @BeforeEach
    void setUp() throws Exception {
        database = new SqliteDatabase(temporaryDirectory.resolve("incremental.db"));
        AppSchema.init(database);
        seedSupportRows();
        LoanApplicationServiceFactory factory = new LoanApplicationServiceFactory(database);
        service = factory.loanApplicationService();
        queryRepository = factory.projectionQueryRepository();
    }

    @Test
    void principalChangeTriggersReplayAndConverges() throws Exception {
        seedLoan(100_000L, "OPEN", REMOTE_DEVICE);
        LegacyLoanMigration.migrate(database, service);

        LoanSummaryProjection summary = queryRepository.getSummaryProjection(OWNER_ID, LOAN_ID);
        assertNotNull(summary);
        assertEquals(100_000L, summary.principalCents());
        assertEquals(100_000L, summary.pendingCents());
        assertEquals(1, countTransactions("LOAN_LENT_OUT"));

        updateLoanRow(150_000L, "Counterparty", null);
        LegacyLoanMigration.migrate(database, service);

        summary = queryRepository.getSummaryProjection(OWNER_ID, LOAN_ID);
        assertNotNull(summary);
        assertEquals(150_000L, summary.principalCents());
        assertEquals(150_000L, summary.pendingCents());

        // El evento de creación replicado conserva el vínculo a la transacción
        // ya existente; el backfill no debe duplicarla.
        assertEquals(1, countTransactions("LOAN_LENT_OUT"));
        String creationTxId = journalCreationTransactionId();
        assertNotNull(creationTxId);
        assertEquals(creationTxId, singleTransactionId("LOAN_LENT_OUT"));
    }

    @Test
    void unchangedLoanIsNotReplayed() throws Exception {
        seedLoan(100_000L, "OPEN", REMOTE_DEVICE);
        LegacyLoanMigration.migrate(database, service);
        List<String> before = journalEventIds();

        LegacyLoanMigration.migrate(database, service);

        assertEquals(before, journalEventIds());
    }

    @Test
    void metadataChangeTriggersReplay() throws Exception {
        seedLoan(100_000L, "OPEN", REMOTE_DEVICE);
        LegacyLoanMigration.migrate(database, service);

        updateLoanRow(100_000L, "Nuevo Nombre", "nota remota");
        LegacyLoanMigration.migrate(database, service);

        LoanSummaryProjection summary = queryRepository.getSummaryProjection(OWNER_ID, LOAN_ID);
        assertNotNull(summary);
        assertEquals("Nuevo Nombre", summary.counterparty());
    }

    @Test
    void newRemotePaymentConverges() throws Exception {
        seedLoan(100_000L, "OPEN", REMOTE_DEVICE);
        LegacyLoanMigration.migrate(database, service);

        seedTransaction("tx-pay-1", "LOAN_REPAYMENT_PRINCIPAL_IN", 40_000L, 2_000L);
        seedPayment("pay-1", 40_000L, "tx-pay-1", 2_000L);
        LegacyLoanMigration.migrate(database, service);

        LoanSummaryProjection summary = queryRepository.getSummaryProjection(OWNER_ID, LOAN_ID);
        assertNotNull(summary);
        assertEquals(1, summary.paymentCount());
        assertEquals(40_000L, summary.totalPaidCents());
        assertEquals(60_000L, summary.pendingCents());
    }

    @Test
    void removedPaymentConvergesWhenTransactionAlsoRemoved() throws Exception {
        seedLoan(100_000L, "OPEN", REMOTE_DEVICE);
        seedTransaction("tx-pay-1", "LOAN_REPAYMENT_PRINCIPAL_IN", 40_000L, 2_000L);
        seedPayment("pay-1", 40_000L, "tx-pay-1", 2_000L);
        LegacyLoanMigration.migrate(database, service);
        assertEquals(1, queryRepository.getSummaryProjection(OWNER_ID, LOAN_ID).paymentCount());

        deleteRow("loan_payments", "pay-1");
        deleteRow("transactions", "tx-pay-1");
        LegacyLoanMigration.migrate(database, service);

        LoanSummaryProjection summary = queryRepository.getSummaryProjection(OWNER_ID, LOAN_ID);
        assertNotNull(summary);
        assertEquals(0, summary.paymentCount());
        assertEquals(0L, summary.totalPaidCents());
        assertEquals(100_000L, summary.pendingCents());
    }

    @Test
    void selfEchoedRowIsNotReplayed() throws Exception {
        // Una fila cuyo updatedBy es este mismo dispositivo es el eco de un doc
        // publicado localmente: el journal local es la fuente de verdad.
        seedLoan(100_000L, "OPEN", DeviceId.get());
        LegacyLoanMigration.migrate(database, service);
        List<String> before = journalEventIds();

        updateLoanRow(999_000L, "Counterparty", null, DeviceId.get());
        LegacyLoanMigration.migrate(database, service);

        assertEquals(before, journalEventIds());
        assertEquals(100_000L, queryRepository.getSummaryProjection(OWNER_ID, LOAN_ID).principalCents());
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

    private void seedLoan(long principal, String status, String updatedBy) throws Exception {
        String sql =
            "INSERT INTO loans (id, user_uid, type, counterparty_name, principal_cents, currency, status, notes, " +
            "occurred_at_epoch_sec, account_id, created_at_epoch_sec, updated_at_epoch_sec, updated_by, pending_sync) " +
            "VALUES (?, ?, 'LENT', 'Counterparty', ?, 'COP', ?, NULL, ?, ?, ?, ?, ?, 0)";
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
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

    private void updateLoanRow(long principal, String counterparty, String notes) throws Exception {
        updateLoanRow(principal, counterparty, notes, REMOTE_DEVICE);
    }

    private void updateLoanRow(long principal, String counterparty, String notes, String updatedBy) throws Exception {
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(
            "UPDATE loans SET principal_cents = ?, counterparty_name = ?, notes = ?, updated_at_epoch_sec = ?, updated_by = ? WHERE user_uid = ? AND id = ?")) {
            ps.setLong(1, principal);
            ps.setString(2, counterparty);
            ps.setString(3, notes);
            ps.setLong(4, 9_999L);
            ps.setString(5, updatedBy);
            ps.setString(6, OWNER_ID);
            ps.setString(7, LOAN_ID);
            ps.executeUpdate();
        }
    }

    private void seedPayment(String id, long principal, String txId, long occurredAt) throws Exception {
        String sql =
            "INSERT INTO loan_payments (id, loan_id, user_uid, account_id, principal_cents, occurred_at_epoch_sec, " +
            "linked_transaction_id, note, created_at_epoch_sec, updated_at_epoch_sec, updated_by, pending_sync) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, 0)";
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
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

    private void seedTransaction(String id, String kind, long amountCents, long occurredAt) throws Exception {
        String sql =
            "INSERT INTO transactions (id, user_uid, account_id, category_id, kind, amount_cents, occurred_at_epoch_sec, " +
            "note, created_at_epoch_sec, updated_at_epoch_sec, pending_sync) " +
            "VALUES (?, ?, ?, 'cat-1', ?, ?, ?, NULL, ?, ?, 0)";
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
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

    private void deleteRow(String table, String id) throws Exception {
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(
            "DELETE FROM " + table + " WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    private List<String> journalEventIds() throws Exception {
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(
            "SELECT event_id FROM loan_journal_v1 WHERE owner_id = ? AND loan_id = ? ORDER BY occurred_at, recorded_at, event_id")) {
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

    private String journalCreationTransactionId() throws Exception {
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(
            "SELECT transaction_id FROM loan_journal_v1 WHERE owner_id = ? AND loan_id = ? AND event_type = 'CREATION'")) {
            ps.setString(1, OWNER_ID);
            ps.setString(2, LOAN_ID);
            try (ResultSet result = ps.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private int countTransactions(String kind) throws Exception {
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(
            "SELECT COUNT(*) FROM transactions WHERE user_uid = ? AND kind = ?")) {
            ps.setString(1, OWNER_ID);
            ps.setString(2, kind);
            try (ResultSet result = ps.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    private String singleTransactionId(String kind) throws Exception {
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(
            "SELECT id FROM transactions WHERE user_uid = ? AND kind = ?")) {
            ps.setString(1, OWNER_ID);
            ps.setString(2, kind);
            try (ResultSet result = ps.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }
}
