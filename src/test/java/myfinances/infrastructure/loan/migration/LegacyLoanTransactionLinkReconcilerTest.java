package myfinances.infrastructure.loan.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myfinaces.db.AppSchema;
import com.myfinaces.db.SqliteDatabase;
import com.myfinaces.sync.DeviceId;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import myfinances.application.loan.LoanApplicationService;
import myfinances.infrastructure.loan.di.LoanApplicationServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cobertura del enlace legacy → journal: la migración debe adoptar la
 * transacción financiera original cuando {@code linked_transaction_id} viene
 * NULL, y {@link LegacyLoanTransactionLinkReconciler} debe reparar journals ya
 * migrados escribiendo únicamente {@code transaction_id}.
 */
class LegacyLoanTransactionLinkReconcilerTest {
    private static final String OWNER_ID = "owner-1";
    private static final String LOAN_ID = "loan-1";
    private static final String ACCOUNT_ID = "account-1";
    private static final long OCCURRED_AT = 1_700_000_000L;

    @TempDir
    Path tempDir;

    private SqliteDatabase database;
    private LoanApplicationService service;

    @BeforeEach
    void setUp() throws Exception {
        database = new SqliteDatabase(tempDir.resolve("link-reconciler.db"));
        AppSchema.init(database);
        seedSupportRows();
        service = new LoanApplicationServiceFactory(database).loanApplicationService();
    }

    @Test
    void migrationLinksExistingLegacyCreationTransaction() throws Exception {
        seedLoan(LOAN_ID, 100_000L, "OPEN");
        seedMovement("mov-create", LOAN_ID, "CREATION", 100_000L, null, OCCURRED_AT);
        // Transacción legacy original: existía antes de la migración, sin vínculo.
        seedTransaction("tx-legacy-creation", "LOAN_LENT_OUT", 100_000L, OCCURRED_AT);

        LegacyLoanMigration.migrate(database, service);

        assertEquals("tx-legacy-creation", journalTransactionId(LOAN_ID, "CREATION"));
        // El backfill no debe haber creado un duplicado.
        assertEquals(1, countTransactions("LOAN_LENT_OUT"));
    }

    @Test
    void migrationLinksUnlinkedTopupAndPaymentMovements() throws Exception {
        seedLoan(LOAN_ID, 150_000L, "OPEN");
        seedMovement("mov-create", LOAN_ID, "CREATION", 100_000L, "tx-creation", OCCURRED_AT);
        seedMovement("mov-topup", LOAN_ID, "TOPUP", 50_000L, null, OCCURRED_AT + 10);
        seedMovement("mov-pay", LOAN_ID, "PAYMENT_IN", 30_000L, null, OCCURRED_AT + 20);
        seedTransaction("tx-creation", "LOAN_LENT_OUT", 100_000L, OCCURRED_AT);
        seedTransaction("tx-legacy-topup", "LOAN_LENT_TOPUP", 50_000L, OCCURRED_AT + 10);
        seedTransaction("tx-legacy-payment", "LOAN_REPAYMENT_PRINCIPAL_IN", 30_000L, OCCURRED_AT + 20);

        LegacyLoanMigration.migrate(database, service);

        assertEquals("tx-creation", journalTransactionId(LOAN_ID, "CREATION"));
        assertEquals("tx-legacy-topup", journalTransactionId(LOAN_ID, "TOPUP"));
        assertEquals("tx-legacy-payment", journalTransactionId(LOAN_ID, "PAYMENT"));
        // Ningún duplicado materializado.
        assertEquals(1, countTransactions("LOAN_LENT_OUT"));
        assertEquals(1, countTransactions("LOAN_LENT_TOPUP"));
        assertEquals(1, countTransactions("LOAN_REPAYMENT_PRINCIPAL_IN"));
    }

    @Test
    void reconcilerLinksRealAdjustmentEvent() throws Exception {
        seedCanonicalLoan();
        insertJournalEvent("evt-adj-1", "ADJUSTMENT", -20_000L, OCCURRED_AT + 30, null);
        seedTransaction("tx-legacy-adj", "LOAN_LENT_CORRECTION_IN", 20_000L, OCCURRED_AT + 30);

        LegacyLoanTransactionLinkReconciler.LinkReport report =
            LegacyLoanTransactionLinkReconciler.reconcile(database);

        assertEquals(1, report.eventsLinked());
        assertEquals("tx-legacy-adj", journalTransactionId(LOAN_ID, "ADJUSTMENT"));
    }

    @Test
    void eventWithoutCandidateStaysNull() throws Exception {
        seedCanonicalLoan();
        insertJournalEvent("evt-topup-orphan", "TOPUP", 50_000L, OCCURRED_AT + 40, null);

        LegacyLoanTransactionLinkReconciler.LinkReport report =
            LegacyLoanTransactionLinkReconciler.reconcile(database);

        assertEquals(0, report.eventsLinked());
        assertEquals(1, report.unmatched());
        assertNull(journalTransactionId(LOAN_ID, "TOPUP"));
    }

    @Test
    void ambiguousCandidatesStayUnlinked() throws Exception {
        seedCanonicalLoan();
        insertJournalEvent("evt-topup-amb", "TOPUP", 50_000L, OCCURRED_AT + 40, null);
        seedTransaction("tx-a", "LOAN_LENT_TOPUP", 50_000L, OCCURRED_AT + 40);
        seedTransaction("tx-b", "LOAN_LENT_TOPUP", 50_000L, OCCURRED_AT + 40);

        LegacyLoanTransactionLinkReconciler.LinkReport report =
            LegacyLoanTransactionLinkReconciler.reconcile(database);

        assertEquals(0, report.eventsLinked());
        assertEquals(1, report.ambiguous());
        assertNull(journalTransactionId(LOAN_ID, "TOPUP"));
    }

    @Test
    void syntheticAdjustmentIsNeverLinked() throws Exception {
        seedCanonicalLoan();
        long occurredAt = OCCURRED_AT + 50;
        String syntheticId = CanonicalLoanEventIds.deterministic(
            LOAN_ID, "ADJUSTMENT", "synth:adjust:" + LOAN_ID, occurredAt);
        insertJournalEvent(syntheticId, "ADJUSTMENT", 50_000L, occurredAt, null);
        // Aunque exista una transacción de firma idéntica, el ajuste sintético
        // no representa flujo de dinero y no debe enlazarse.
        seedTransaction("tx-looks-similar", "LOAN_LENT_CORRECTION_OUT", 50_000L, occurredAt);

        LegacyLoanTransactionLinkReconciler.LinkReport report =
            LegacyLoanTransactionLinkReconciler.reconcile(database);

        assertEquals(0, report.eventsScanned());
        assertEquals(0, report.eventsLinked());
        assertNull(journalTransactionId(LOAN_ID, "ADJUSTMENT"));
    }

    @Test
    void candidateReferencedByAnotherMovementIsNotStolen() throws Exception {
        seedCanonicalLoan();
        seedLoan("loan-2", 60_000L, "OPEN");
        insertJournalEvent("evt-topup-x", "TOPUP", 50_000L, OCCURRED_AT + 40, null);
        seedTransaction("tx-owned-elsewhere", "LOAN_LENT_TOPUP", 50_000L, OCCURRED_AT + 40);
        seedMovement("mov-other", "loan-2", "TOPUP", 50_000L, "tx-owned-elsewhere", OCCURRED_AT + 40);

        LegacyLoanTransactionLinkReconciler.LinkReport report =
            LegacyLoanTransactionLinkReconciler.reconcile(database);

        assertEquals(0, report.eventsLinked());
        assertNull(journalTransactionId(LOAN_ID, "TOPUP"));
    }

    @Test
    void secondRunIsIdempotentAndAlreadyLinkedEventsAreUntouched() throws Exception {
        seedCanonicalLoan();
        insertJournalEvent("evt-pay-linked", "PAYMENT", 10_000L, OCCURRED_AT + 60, "tx-already");
        insertJournalEvent("evt-topup-new", "TOPUP", 50_000L, OCCURRED_AT + 40, null);
        seedTransaction("tx-already", "LOAN_REPAYMENT_PRINCIPAL_IN", 10_000L, OCCURRED_AT + 60);
        seedTransaction("tx-topup-hit", "LOAN_LENT_TOPUP", 50_000L, OCCURRED_AT + 40);

        LegacyLoanTransactionLinkReconciler.LinkReport first =
            LegacyLoanTransactionLinkReconciler.reconcile(database);
        assertEquals(1, first.eventsLinked());
        assertEquals("tx-already", journalTransactionId(LOAN_ID, "PAYMENT"));
        assertEquals("tx-topup-hit", journalTransactionId(LOAN_ID, "TOPUP"));

        LegacyLoanTransactionLinkReconciler.LinkReport second =
            LegacyLoanTransactionLinkReconciler.reconcile(database);
        assertEquals(0, second.eventsScanned());
        assertEquals(0, second.eventsLinked());
        assertTrue(second.errors().isEmpty());
    }

    /**
     * Formato legacy antiguo: el desembolso quedó como {@code EXPENSE} en la
     * categoría de sistema {@code system-loan-{uid}} con nota
     * {@code "LOAN_LENT_OUT: <contraparte>"} y {@code occurred_at} retrasado a
     * medianoche; el ancla temporal es {@code created_at == event.occurred_at}.
     */
    @Test
    void linksLegacyExpenseFormattedCreation() throws Exception {
        seedCanonicalLoan();
        insertJournalEvent("evt-cre-legacy", "CREATION", 50_000L, OCCURRED_AT + 70, null);
        insertLegacyFormatTransaction("tx-legacy-expense", "EXPENSE", 50_000L,
            OCCURRED_AT + 69, OCCURRED_AT + 70, "LOAN_LENT_OUT: Counterparty");

        LegacyLoanTransactionLinkReconciler.LinkReport report =
            LegacyLoanTransactionLinkReconciler.reconcile(database);

        assertEquals(1, report.eventsLinked());
        assertEquals("tx-legacy-expense", journalTransactionIdForEvent("evt-cre-legacy"));
    }

    /**
     * Ventana de correlación: el evento quedó con {@code occurred_at}
     * normalizado a fin de día (~21 h de desfase) pero existe una única
     * transacción con mismo kind/cuenta/monto y la nota terminada en la
     * contraparte. El enlace es inequívoco → se escribe.
     */
    @Test
    void windowedMatchLinksUniqueCandidate() throws Exception {
        seedCanonicalLoan();
        insertJournalEvent("evt-cre-windowed", "CREATION", 50_000L, OCCURRED_AT + 3_600, null);
        seedTransactionWithNote("tx-real-windowed", "LOAN_LENT_OUT", 50_000L,
            OCCURRED_AT + 3_600 + 74_000, "LOAN_LENT_OUT: Counterparty");

        LegacyLoanTransactionLinkReconciler.LinkReport report =
            LegacyLoanTransactionLinkReconciler.reconcile(database);

        assertEquals(1, report.eventsLinked());
        assertEquals("tx-real-windowed", journalTransactionIdForEvent("evt-cre-windowed"));
    }

    /** Dos candidatos equivalentes dentro de la ventana: ambiguo → no se enlaza. */
    @Test
    void windowedAmbiguousCandidatesStayUnlinked() throws Exception {
        seedCanonicalLoan();
        insertJournalEvent("evt-cre-windowed-amb", "CREATION", 50_000L, OCCURRED_AT + 3_600, null);
        seedTransactionWithNote("tx-wa", "LOAN_LENT_OUT", 50_000L,
            OCCURRED_AT + 3_600 + 70_000, "LOAN_LENT_OUT: Counterparty");
        seedTransactionWithNote("tx-wb", "LOAN_LENT_OUT", 50_000L,
            OCCURRED_AT + 3_600 + 71_000, "LOAN_LENT_OUT: Counterparty");

        LegacyLoanTransactionLinkReconciler.LinkReport report =
            LegacyLoanTransactionLinkReconciler.reconcile(database);

        assertEquals(0, report.eventsLinked());
        assertEquals(1, report.ambiguous());
        assertNull(journalTransactionIdForEvent("evt-cre-windowed-amb"));
    }

    /**
     * El replay emitió dos veces el mismo hecho (por movimiento y por
     * transacción): el hermano ya reclama la tx. El huérfano queda clasificado
     * como cubierto, sin enlace y sin candidato propio.
     */
    @Test
    void orphanEventWithLinkedSiblingIsCovered() throws Exception {
        seedCanonicalLoan();
        insertJournalEvent("evt-topup-twin", "TOPUP", 50_000L, OCCURRED_AT + 40, "tx-twin");
        insertJournalEvent("evt-topup-orphan", "TOPUP", 50_000L, OCCURRED_AT + 40, null);
        seedTransaction("tx-twin", "LOAN_LENT_TOPUP", 50_000L, OCCURRED_AT + 40);

        LegacyLoanTransactionLinkReconciler.LinkReport report =
            LegacyLoanTransactionLinkReconciler.reconcile(database);

        assertEquals(0, report.eventsLinked());
        assertEquals(1, report.coveredBySibling());
        assertNull(journalTransactionIdForEvent("evt-topup-orphan"));
    }

    /**
     * Ajuste sintético escrito por un build anterior (event_id no
     * determinístico): se reconoce por {@code payload_reason} y jamás se enlaza.
     */
    @Test
    void adjustmentWithSyntheticReasonIsNeverLinked() throws Exception {
        seedCanonicalLoan();
        insertJournalEventWithReason("evt-adj-legacy", "ADJUSTMENT", -12_000L, OCCURRED_AT + 50, null,
            "Ajuste incremental remoto");
        seedTransaction("tx-adj-looks-similar", "LOAN_LENT_CORRECTION_IN", 12_000L, OCCURRED_AT + 50);

        LegacyLoanTransactionLinkReconciler.LinkReport report =
            LegacyLoanTransactionLinkReconciler.reconcile(database);

        assertEquals(0, report.eventsScanned());
        assertNull(journalTransactionIdForEvent("evt-adj-legacy"));
    }

    /** La firma legacy-format exige categoría de sistema y nota exacta: sin ellas no enlaza. */
    @Test
    void legacyFormatRequiresSystemCategoryAndExactNote() throws Exception {
        seedCanonicalLoan();
        insertJournalEvent("evt-cre-plain", "CREATION", 50_000L, OCCURRED_AT + 80, null);
        // Mismo monto/cuenta/created_at pero gasto cotidiano sin categoría ni nota de préstamo.
        insertLegacyFormatTransaction("tx-plain-expense", "EXPENSE", 50_000L,
            OCCURRED_AT + 79, OCCURRED_AT + 80, "Compra cualquiera");

        LegacyLoanTransactionLinkReconciler.LinkReport report =
            LegacyLoanTransactionLinkReconciler.reconcile(database);

        assertEquals(0, report.eventsLinked());
        assertNull(journalTransactionIdForEvent("evt-cre-plain"));
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

    @Test
    void cleanBaseProducesZeroChanges() {
        LegacyLoanTransactionLinkReconciler.LinkReport report =
            LegacyLoanTransactionLinkReconciler.reconcile(database);
        assertEquals(0, report.eventsScanned());
        assertEquals(0, report.eventsLinked());
        assertTrue(report.errors().isEmpty());
    }

    // Crea un préstamo canónico con su evento CREATION ya enlazado.
    private void seedCanonicalLoan() throws Exception {
        seedLoan(LOAN_ID, 100_000L, "OPEN");
        seedMovement("mov-create", LOAN_ID, "CREATION", 100_000L, "tx-creation", OCCURRED_AT);
        seedTransaction("tx-creation", "LOAN_LENT_OUT", 100_000L, OCCURRED_AT);
        LegacyLoanMigration.migrate(database, service);
        assertEquals("tx-creation", journalTransactionId(LOAN_ID, "CREATION"));
    }

    private void insertJournalEvent(String eventId, String eventType, Long amountCents,
                                    long occurredAt, String transactionId) throws Exception {
        insertJournalEventWithReason(eventId, eventType, amountCents, occurredAt, transactionId, null);
    }

    private void insertJournalEventWithReason(String eventId, String eventType, Long amountCents,
                                    long occurredAt, String transactionId, String payloadReason) throws Exception {
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO loan_journal_v1 (event_id, operation_id, loan_id, owner_id, event_type, event_schema_version, " +
            "amount_cents, account_id, transaction_id, note, occurred_at, recorded_at, actor_id, origin_id, " +
            "payload_reason, metadata_counterparty_present, metadata_account_present, metadata_notes_present) " +
            "VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, NULL, ?, ?, ?, ?, ?, 0, 0, 0)")) {
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
            ps.setLong(9, occurredAt);
            ps.setLong(10, occurredAt);
            ps.setString(11, OWNER_ID);
            ps.setString(12, OWNER_ID);
            ps.setString(13, payloadReason);
            ps.executeUpdate();
        }
    }

    private String journalTransactionIdForEvent(String eventId) throws Exception {
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(
            "SELECT transaction_id FROM loan_journal_v1 WHERE owner_id = ? AND event_id = ?")) {
            ps.setString(1, OWNER_ID);
            ps.setString(2, eventId);
            try (ResultSet result = ps.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private String journalTransactionId(String loanId, String eventType) throws Exception {
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(
            "SELECT transaction_id FROM loan_journal_v1 WHERE owner_id = ? AND loan_id = ? AND event_type = ?")) {
            ps.setString(1, OWNER_ID);
            ps.setString(2, loanId);
            ps.setString(3, eventType);
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

    private void seedLoan(String loanId, long principal, String status) throws Exception {
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO loans (id, user_uid, type, counterparty_name, principal_cents, currency, status, notes, " +
            "occurred_at_epoch_sec, account_id, created_at_epoch_sec, updated_at_epoch_sec, updated_by, pending_sync) " +
            "VALUES (?, ?, 'LENT', 'Counterparty', ?, 'COP', ?, NULL, ?, ?, ?, ?, ?, 0)")) {
            ps.setString(1, loanId);
            ps.setString(2, OWNER_ID);
            ps.setLong(3, principal);
            ps.setString(4, status);
            ps.setLong(5, OCCURRED_AT);
            ps.setString(6, ACCOUNT_ID);
            ps.setLong(7, OCCURRED_AT);
            ps.setLong(8, OCCURRED_AT);
            ps.setString(9, DeviceId.get());
            ps.executeUpdate();
        }
    }

    private void seedMovement(String id, String loanId, String type, long amountCents,
                              String linkedTxId, long occurredAt) throws Exception {
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO loan_movements (id, loan_id, user_uid, movement_type, amount_cents, account_id, " +
            "linked_transaction_id, note, occurred_at_epoch_sec, created_at_epoch_sec, updated_at_epoch_sec, pending_sync) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, 0)")) {
            ps.setString(1, id);
            ps.setString(2, loanId);
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

    private void seedTransaction(String id, String kind, long amountCents, long occurredAt) throws Exception {
        seedTransactionWithNote(id, kind, amountCents, occurredAt, null);
    }

    private void seedTransactionWithNote(String id, String kind, long amountCents, long occurredAt,
                                         String note) throws Exception {
        try (Connection connection = database.openConnection(); PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO transactions (id, user_uid, account_id, category_id, kind, amount_cents, occurred_at_epoch_sec, " +
            "note, created_at_epoch_sec, updated_at_epoch_sec, pending_sync) " +
            "VALUES (?, ?, ?, 'cat-1', ?, ?, ?, ?, ?, ?, 0)")) {
            ps.setString(1, id);
            ps.setString(2, OWNER_ID);
            ps.setString(3, ACCOUNT_ID);
            ps.setString(4, kind);
            ps.setLong(5, amountCents);
            ps.setLong(6, occurredAt);
            ps.setString(7, note);
            ps.setLong(8, occurredAt);
            ps.setLong(9, occurredAt);
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
