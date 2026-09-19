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
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import myfinances.application.loan.LoanApplicationService;
import myfinances.infrastructure.loan.di.LoanApplicationServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sprint 7J.6 — limpieza de duplicados canónicos: transacciones materializadas
 * por el backfill para eventos cuya transacción legacy ya existía sin estar
 * enlazada. Solo se elimina la copia {@code canonical-loan-tx:{eventId}} cuando
 * el journal ya referencia la legacy equivalente.
 */
class CanonicalLoanDuplicateReconcilerTest {
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
        database = new SqliteDatabase(tempDir.resolve("dedup.db"));
        AppSchema.init(database);
        seedSupportRows();
        service = new LoanApplicationServiceFactory(database).loanApplicationService();
        txRepo = new TransactionRepository(database);
        accountRepo = new AccountRepository(database);
        session = new AuthSession(OWNER_ID, "t@t", "T", "bad-token", "", 0L);
        sync = new FirestoreSyncService("test-project");
    }

    /** Base con duplicado histórico: journal enlazado a legacy + copia canónica → solo cae la copia. */
    @Test
    void deletesCanonicalDuplicateAndRestoresBalance() throws Exception {
        seedLinkedLoan();
        String canonicalTxId = CanonicalLoanEventIds.deterministicTransactionId(creationEventId());
        insertTransaction(canonicalTxId, "LOAN_LENT_OUT", 100_000L, OCCURRED_AT);

        // Saldo con duplicado: -100k legacy -100k canónica.
        assertEquals(-200_000L, accountRepo.computeBalanceCents(OWNER_ID, ACCOUNT_ID));

        CanonicalLoanDuplicateReconciler.DedupReport report =
            CanonicalLoanDuplicateReconciler.reconcile(database, sync, session);
        assertEquals(1, report.duplicatesFound());
        assertEquals(1, report.duplicatesDeleted());
        assertEquals(0, report.conserved());
        assertTrue(report.errors().isEmpty());

        assertNull(txRepo.getForSyncByIdOrNull(OWNER_ID, canonicalTxId));
        assertNotNull(txRepo.getForSyncByIdOrNull(OWNER_ID, "tx-creation"));
        assertEquals(-100_000L, accountRepo.computeBalanceCents(OWNER_ID, ACCOUNT_ID));
    }

    /** El evento sigue sin enlace: la copia canónica se conserva (no hay legacy demostrada). */
    @Test
    void unlinkedEventKeepsItsMaterialization() throws Exception {
        seedLinkedLoan();
        insertJournalEvent("evt-topup-orphan", "TOPUP", 50_000L, OCCURRED_AT + 10, null);
        String canonicalTxId = CanonicalLoanEventIds.deterministicTransactionId("evt-topup-orphan");
        insertTransaction(canonicalTxId, "LOAN_LENT_TOPUP", 50_000L, OCCURRED_AT + 10);

        CanonicalLoanDuplicateReconciler.DedupReport report =
            CanonicalLoanDuplicateReconciler.reconcile(database, sync, session);
        assertEquals(0, report.duplicatesDeleted());
        assertNotNull(txRepo.getForSyncByIdOrNull(OWNER_ID, canonicalTxId));
    }

    /** Journal enlazado a la propia materialización determinística: nada que hacer. */
    @Test
    void selfLinkedCanonicalIsNotTouched() throws Exception {
        seedLinkedLoan();
        insertJournalEvent("evt-adj-self", "ADJUSTMENT", -20_000L, OCCURRED_AT + 30,
            CanonicalLoanEventIds.deterministicTransactionId("evt-adj-self"));
        insertTransaction(CanonicalLoanEventIds.deterministicTransactionId("evt-adj-self"),
            "LOAN_LENT_CORRECTION_IN", 20_000L, OCCURRED_AT + 30);

        CanonicalLoanDuplicateReconciler.DedupReport report =
            CanonicalLoanDuplicateReconciler.reconcile(database, sync, session);
        assertEquals(0, report.duplicatesFound());
        assertEquals(0, report.duplicatesDeleted());
        assertNotNull(txRepo.getForSyncByIdOrNull(OWNER_ID,
            CanonicalLoanEventIds.deterministicTransactionId("evt-adj-self")));
    }

    /** Duplicado referenciado por loan_payments: nunca se elimina. */
    @Test
    void duplicateReferencedByPaymentIsConserved() throws Exception {
        seedLinkedLoan();
        String canonicalTxId = CanonicalLoanEventIds.deterministicTransactionId(creationEventId());
        insertTransaction(canonicalTxId, "LOAN_LENT_OUT", 100_000L, OCCURRED_AT);
        seedPayment("pay-refs-canonical", 10_000L, canonicalTxId, OCCURRED_AT + 5);

        CanonicalLoanDuplicateReconciler.DedupReport report =
            CanonicalLoanDuplicateReconciler.reconcile(database, sync, session);
        assertEquals(1, report.duplicatesFound());
        assertEquals(0, report.duplicatesDeleted());
        assertEquals(1, report.conserved());
        assertNotNull(txRepo.getForSyncByIdOrNull(OWNER_ID, canonicalTxId));
    }

    /** Firma distinta (otro monto): no es el mismo flujo → se conserva. */
    @Test
    void mismatchedSignatureIsConserved() throws Exception {
        seedLinkedLoan();
        String canonicalTxId = CanonicalLoanEventIds.deterministicTransactionId(creationEventId());
        insertTransaction(canonicalTxId, "LOAN_LENT_OUT", 77_000L, OCCURRED_AT);

        CanonicalLoanDuplicateReconciler.DedupReport report =
            CanonicalLoanDuplicateReconciler.reconcile(database, sync, session);
        assertEquals(0, report.duplicatesDeleted());
        assertEquals(1, report.conserved());
        assertNotNull(txRepo.getForSyncByIdOrNull(OWNER_ID, canonicalTxId));
    }

    /**
     * Original en formato legacy antiguo (EXPENSE + categoría sistema + nota
     * "LOAN_LENT_OUT: <contraparte>" + occurred_at retrasado): el duplicado
     * canónico sí se elimina porque la equivalencia está demostrada.
     */
    @Test
    void deletesDuplicateOfLegacyExpenseFormattedOriginal() throws Exception {
        seedLoan();
        seedMovement("mov-create", "CREATION", 100_000L, null, OCCURRED_AT);
        // Original legacy antiguo: EXPENSE en categoría sistema, nota pseudo-kind,
        // occurred_at retrasado a medianoche, created_at = instante del evento.
        insertLegacyFormatTransaction("tx-legacy-expense", "EXPENSE", 100_000L,
            OCCURRED_AT - 3600, OCCURRED_AT, "LOAN_LENT_OUT: Counterparty");
        LegacyLoanMigration.migrate(database, service);
        String eventId = creationEventId();
        assertEquals("tx-legacy-expense", creationTransactionId());

        String canonicalTxId = CanonicalLoanEventIds.deterministicTransactionId(eventId);
        insertTransaction(canonicalTxId, "LOAN_LENT_OUT", 100_000L, OCCURRED_AT);

        CanonicalLoanDuplicateReconciler.DedupReport report =
            CanonicalLoanDuplicateReconciler.reconcile(database, sync, session);
        assertEquals(1, report.duplicatesDeleted());
        assertNull(txRepo.getForSyncByIdOrNull(OWNER_ID, canonicalTxId));
        assertNotNull(txRepo.getForSyncByIdOrNull(OWNER_ID, "tx-legacy-expense"));
        assertEquals(-100_000L, accountRepo.computeBalanceCents(OWNER_ID, ACCOUNT_ID));
    }

    private void insertLegacyFormatTransaction(String id, String kind, long amountCents,
            long occurredAt, long createdAt, String note) throws Exception {
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO transactions (id, user_uid, account_id, category_id, kind, amount_cents, occurred_at_epoch_sec, " +
            "note, created_at_epoch_sec, updated_at_epoch_sec, pending_sync) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)")) {
            ps.setString(1, id);
            ps.setString(2, OWNER_ID);
            ps.setString(3, ACCOUNT_ID);
            ps.setString(4, note != null && note.startsWith("LOAN_") ? "system-loan-" + OWNER_ID : "cat-1");
            ps.setString(5, kind);
            ps.setLong(6, amountCents);
            ps.setLong(7, occurredAt);
            ps.setString(8, note);
            ps.setLong(9, createdAt);
            ps.setLong(10, createdAt);
            ps.executeUpdate();
        }
    }

    /** Segunda ejecución: cero cambios. Resurrección por pull → se re-elimina (convergencia). */
    @Test
    void idempotentAndReconvergent() throws Exception {
        seedLinkedLoan();
        String canonicalTxId = CanonicalLoanEventIds.deterministicTransactionId(creationEventId());
        insertTransaction(canonicalTxId, "LOAN_LENT_OUT", 100_000L, OCCURRED_AT);

        CanonicalLoanDuplicateReconciler.reconcile(database, sync, session);
        CanonicalLoanDuplicateReconciler.DedupReport second =
            CanonicalLoanDuplicateReconciler.reconcile(database, sync, session);
        assertEquals(0, second.duplicatesDeleted());

        // Simula pull remoto-autoritativo que resucita el documento.
        insertTransaction(canonicalTxId, "LOAN_LENT_OUT", 100_000L, OCCURRED_AT);
        CanonicalLoanDuplicateReconciler.DedupReport third =
            CanonicalLoanDuplicateReconciler.reconcile(database, sync, session);
        assertEquals(1, third.duplicatesDeleted());
        assertNull(txRepo.getForSyncByIdOrNull(OWNER_ID, canonicalTxId));
        assertEquals(-100_000L, accountRepo.computeBalanceCents(OWNER_ID, ACCOUNT_ID));
    }

    /** Base limpia: cero cambios. */
    @Test
    void cleanBaseProducesZeroChanges() {
        CanonicalLoanDuplicateReconciler.DedupReport report =
            CanonicalLoanDuplicateReconciler.reconcile(database, sync, session);
        assertEquals(0, report.linkedEventsScanned());
        assertEquals(0, report.duplicatesDeleted());
        assertTrue(report.errors().isEmpty());
    }

    private String creationEventId() throws Exception {
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(
            "SELECT event_id FROM loan_journal_v1 WHERE owner_id = ? AND loan_id = ? AND event_type = 'CREATION'")) {
            ps.setString(1, OWNER_ID);
            ps.setString(2, LOAN_ID);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // Préstamo canónico migrado con su evento CREATION enlazado a tx-creation.
    private void seedLinkedLoan() throws Exception {
        seedLoan();
        seedMovement("mov-create", "CREATION", 100_000L, "tx-creation", OCCURRED_AT);
        insertTransaction("tx-creation", "LOAN_LENT_OUT", 100_000L, OCCURRED_AT);
        LegacyLoanMigration.migrate(database, service);
        assertEquals("tx-creation", creationTransactionId());
    }

    private String creationTransactionId() throws Exception {
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(
            "SELECT transaction_id FROM loan_journal_v1 WHERE owner_id = ? AND loan_id = ? AND event_type = 'CREATION'")) {
            ps.setString(1, OWNER_ID);
            ps.setString(2, LOAN_ID);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void insertJournalEvent(String eventId, String eventType, Long amountCents,
                                    long occurredAt, String transactionId) throws Exception {
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO loan_journal_v1 (event_id, operation_id, loan_id, owner_id, event_type, event_schema_version, " +
            "amount_cents, account_id, transaction_id, note, occurred_at, recorded_at, actor_id, origin_id, " +
            "metadata_counterparty_present, metadata_account_present, metadata_notes_present) " +
            "VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, NULL, ?, ?, ?, ?, 0, 0, 0)")) {
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
            ps.setLong(9, occurredAt);
            ps.setLong(10, occurredAt);
            ps.setString(11, OWNER_ID);
            ps.setString(12, OWNER_ID);
            ps.executeUpdate();
        }
    }

    private void insertTransaction(String id, String kind, long amountCents, long occurredAt) throws Exception {
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

    private void seedPayment(String id, long principal, String txId, long occurredAt) throws Exception {
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO loan_payments (id, loan_id, user_uid, account_id, principal_cents, occurred_at_epoch_sec, " +
            "linked_transaction_id, note, created_at_epoch_sec, updated_at_epoch_sec, updated_by, pending_sync) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, 'device', 0)")) {
            ps.setString(1, id);
            ps.setString(2, LOAN_ID);
            ps.setString(3, OWNER_ID);
            ps.setString(4, ACCOUNT_ID);
            ps.setLong(5, principal);
            ps.setLong(6, occurredAt);
            ps.setString(7, txId);
            ps.setLong(8, occurredAt);
            ps.setLong(9, occurredAt);
            ps.executeUpdate();
        }
    }

    private void seedLoan() throws Exception {
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO loans (id, user_uid, type, counterparty_name, principal_cents, currency, status, notes, " +
            "occurred_at_epoch_sec, account_id, created_at_epoch_sec, updated_at_epoch_sec, updated_by, pending_sync) " +
            "VALUES (?, ?, 'LENT', 'Counterparty', ?, 'COP', 'OPEN', NULL, ?, ?, ?, ?, 'device', 0)")) {
            ps.setString(1, LOAN_ID);
            ps.setString(2, OWNER_ID);
            ps.setLong(3, 100_000L);
            ps.setLong(4, OCCURRED_AT);
            ps.setString(5, ACCOUNT_ID);
            ps.setLong(6, OCCURRED_AT);
            ps.setLong(7, OCCURRED_AT);
            ps.executeUpdate();
        }
    }

    private void seedMovement(String id, String type, long amountCents, String linkedTxId, long occurredAt) throws Exception {
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO loan_movements (id, loan_id, user_uid, movement_type, amount_cents, account_id, " +
            "linked_transaction_id, note, occurred_at_epoch_sec, created_at_epoch_sec, updated_at_epoch_sec, pending_sync) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, 0)")) {
            ps.setString(1, id);
            ps.setString(2, LOAN_ID);
            ps.setString(3, OWNER_ID);
            ps.setString(4, type);
            ps.setLong(5, amountCents);
            ps.setString(6, ACCOUNT_ID);
            ps.setString(7, linkedTxId);
            ps.setLong(8, occurredAt);
            ps.setLong(9, occurredAt);
            ps.setLong(10, occurredAt);
            ps.executeUpdate();
        }
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
                "INSERT INTO categories (id, user_uid, name, parent_id, kind, created_at_epoch_sec, updated_at_epoch_sec) VALUES " +
                "('system-loan-" + OWNER_ID + "', '" + OWNER_ID + "', 'Préstamos', NULL, 'EXPENSE', 0, 0)");
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
}
