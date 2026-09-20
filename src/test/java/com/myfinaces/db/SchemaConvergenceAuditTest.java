package com.myfinaces.db;

import com.myfinaces.auth.AuthSession;
import com.myfinaces.sync.CanonicalLoanPublishQueue;
import myfinances.application.loan.LoanApplicationService;
import myfinances.infrastructure.loan.di.LoanApplicationServiceFactory;
import myfinances.infrastructure.loan.admin.LoanAdminStateBackfill;
import myfinances.infrastructure.loan.migration.CanonicalLoanTransactionBackfill;
import myfinances.infrastructure.loan.migration.LegacyLoanMigration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Auditoría Fase 3: verifica que una instalación nueva y una base de datos
 * antigua (esquema fd34a48, v2.0.1) convergen al mismo esquema lógico y que el
 * ciclo completo de escritura funciona sobre una DB recién creada.
 */
class SchemaConvergenceAuditTest {

    private static final String UID = "audit-user";

    // Replica exacta de la secuencia de arranque de MyFinances.start()
    private static void productionInit(SqliteDatabase db) throws Exception {
        AppSchema.init(db);
        LoanApplicationServiceFactory factory = new LoanApplicationServiceFactory(db);
        LoanApplicationService service = factory.loanApplicationService();
        LegacyLoanMigration.migrate(db, service);
        CanonicalLoanTransactionBackfill.run(db);
        LoanAdminStateBackfill.run(db);
        factory.projectionQueryRepository();
        new SessionRepository(db).init();
    }

    @Test
    void freshInstall_fullLifecycle(@TempDir Path dir) throws Exception {
        SqliteDatabase db = new SqliteDatabase(dir.resolve("myfinances.db"));
        productionInit(db);

        // usuario local (precondición FK)
        new UserRepository(db).upsert(UID, "audit@test.dev");

        // cuenta con color
        AccountRepository accounts = new AccountRepository(db);
        AccountRepository.Account acc = accounts.create(UID, "Banco", "BANK", "COP", "#112233");
        assertEquals("#112233", accounts.getById(UID, acc.id()).color());
        assertEquals(1, accounts.list(UID).size());

        // upsertFromRemote: insert persiste color
        AccountRepository.Account remoteNew = new AccountRepository.Account(
            "remote-1", UID, "Remota", "SAVINGS", "USD", "#AABBCC", 1000L, 1000L);
        accounts.upsertFromRemote(UID, remoteNew);
        assertEquals("#AABBCC", accounts.getById(UID, "remote-1").color());

        // upsertFromRemote: update más reciente persiste color
        AccountRepository.Account remoteUpd = new AccountRepository.Account(
            acc.id(), UID, "Banco Renombrado", "CASH", "USD", "#DDEEFF",
            acc.createdAtEpochSec(), acc.updatedAtEpochSec() + 100);
        accounts.upsertFromRemote(UID, remoteUpd);
        AccountRepository.Account afterUpd = accounts.getById(UID, acc.id());
        assertEquals("#DDEEFF", afterUpd.color());
        assertEquals("Banco Renombrado", afterUpd.name());

        // upsertFromRemote: stale no pisa el color local
        AccountRepository.Account stale = new AccountRepository.Account(
            acc.id(), UID, "Viejo", "BANK", "COP", "#000000",
            acc.createdAtEpochSec(), afterUpd.updatedAtEpochSec() - 1);
        accounts.upsertFromRemote(UID, stale);
        assertEquals("#DDEEFF", accounts.getById(UID, acc.id()).color());

        // categoría con icon
        CategoryRepository categories = new CategoryRepository(db);
        CategoryRepository.Category cat = categories.create(UID, "Comida", null, "EXPENSE", "fas-utensils");
        assertEquals("fas-utensils", categories.getById(UID, cat.id()).icon());
        assertEquals(1, categories.listAll(UID).size());

        // transacción + transferencia
        TransactionRepository txs = new TransactionRepository(db);
        String txId = txs.create(UID, acc.id(), cat.id(), "EXPENSE", 5_000L, 1_700_000_000L, "almuerzo");
        assertNotNull(txs.getForSyncByIdOrNull(UID, txId));

        AccountRepository.Account acc2 = accounts.create(UID, "Efectivo", "CASH", "COP", null);
        TransferRepository trs = new TransferRepository(db);
        String trId = trs.create(UID, acc.id(), acc2.id(), 2_000L, 1_700_000_000L, "retiro");
        assertNotNull(trs.getForSyncByIdOrNull(UID, trId));

        // préstamo + pago + movimiento (tabla loan_movements ausente antes del fix)
        LoanRepository loans = new LoanRepository(db);
        String loanId = loans.create(UID, "LENT", "Juan", acc.id(), 100_000L, "COP", 1_700_000_000L, "nota");

        LoanPaymentRepository payments = new LoanPaymentRepository(db);
        String payId = payments.create(UID, loanId, acc.id(), 10_000L, 1_700_000_100L, txId, "abono");
        assertNotNull(payId);

        LoanMovementRepository movs = new LoanMovementRepository(db);
        String movId = movs.create(UID, loanId, LoanMovementRepository.MOV_CREATION,
            100_000L, acc.id(), txId, "creación", 1_700_000_000L);
        assertNotNull(movs.getByIdOrNull(UID, movId));
        assertEquals(1, movs.listByLoan(UID, loanId).size());
        assertEquals(Set.of(movId), movs.listPendingSyncIds(UID));
        assertFalse(movs.listPendingForSync(UID).isEmpty());

        // upsertFromRemote de movimiento (escribe updated_by)
        LoanMovementRepository.LoanMovement remoteMov = new LoanMovementRepository.LoanMovement(
            "rmov-1", loanId, UID, LoanMovementRepository.MOV_PAYMENT_IN, 10_000L,
            acc.id(), txId, "pago", 1_700_000_100L, 1_700_000_100L, 1_700_000_100L, "device-x");
        movs.upsertFromRemote(UID, remoteMov);
        assertEquals("device-x", movs.getByIdOrNull(UID, "rmov-1").updatedBy());

        // outbox (cola de publicación canónica)
        CanonicalLoanPublishQueue.markPending(db, UID, loanId);
        assertEquals(1, CanonicalLoanPublishQueue.listPending(db).size());
        CanonicalLoanPublishQueue.markDone(db, loanId);
        assertTrue(CanonicalLoanPublishQueue.listPending(db).isEmpty());

        // pending_sync flags para el push inicial
        assertFalse(txs.listPendingForSync(UID).isEmpty());
        assertFalse(trs.listPendingForSync(UID).isEmpty());
        assertFalse(loans.listPendingForSync(UID).isEmpty());
        assertFalse(payments.listPendingForSync(UID).isEmpty());

        // sesión
        SessionRepository sessions = new SessionRepository(db);
        sessions.save(new AuthSession(UID, "audit@test.dev", "Audit", "idtok", "reftok", 9_999_999_999L));
        assertTrue(sessions.load().isPresent());
        assertEquals(UID, sessions.load().get().uid());
    }

    @Test
    void legacySchema_migratesAndConverges(@TempDir Path dir) throws Exception {
        Path legacyFile = dir.resolve("legacy.db");
        createLegacySchemaV201(legacyFile); // esquema fd34a48 (v2.0.1)

        // datos preexistentes que deben sobrevivir intactos
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + legacyFile);
             Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO users (uid, email, created_at_epoch_sec, updated_at_epoch_sec) VALUES ('" + UID + "', 'old@test.dev', 1, 1)");
            st.executeUpdate("INSERT INTO accounts (id, user_uid, name, type, currency, created_at_epoch_sec, updated_at_epoch_sec) VALUES ('acc-old', '" + UID + "', 'Cuenta vieja', 'BANK', 'COP', 1, 1)");
            st.executeUpdate("INSERT INTO categories (id, user_uid, name, parent_id, created_at_epoch_sec, updated_at_epoch_sec) VALUES ('cat-old', '" + UID + "', 'GASTOS', NULL, 1, 1)");
        }

        SqliteDatabase legacy = new SqliteDatabase(legacyFile);
        productionInit(legacy); // aplica todas las migraciones

        // datos intactos + columnas nuevas presentes
        AccountRepository accounts = new AccountRepository(legacy);
        AccountRepository.Account old = accounts.getById(UID, "acc-old");
        assertNotNull(old);
        assertEquals("Cuenta vieja", old.name());
        assertNull(old.color());

        CategoryRepository categories = new CategoryRepository(legacy);
        assertEquals(1, categories.listAll(UID).size()); // kind/icon/color migradas + backfill kind

        // escritura completa sobre la DB migrada
        accounts.create(UID, "Nueva", "BANK", "COP", "#FF0000");
        categories.create(UID, "Nueva cat", "cat-old", "EXPENSE", "fas-tag");
        String loanId = new LoanRepository(legacy).create(UID, "BORROWED", "Ana", "acc-old", 50_000L, "COP", 1_700_000_000L, null);
        new LoanMovementRepository(legacy).create(UID, loanId, LoanMovementRepository.MOV_CREATION, 50_000L, "acc-old", null, null, 1_700_000_000L);

        // convergencia estructural: DB antigua migrada == DB nueva
        Path freshFile = dir.resolve("fresh.db");
        SqliteDatabase fresh = new SqliteDatabase(freshFile);
        productionInit(fresh);

        Map<String, TableShape> freshSchema = snapshotSchema(fresh);
        Map<String, TableShape> legacySchema = snapshotSchema(legacy);

        Set<String> onlyFresh = new TreeSet<>(freshSchema.keySet());
        onlyFresh.removeAll(legacySchema.keySet());
        Set<String> onlyLegacy = new TreeSet<>(legacySchema.keySet());
        onlyLegacy.removeAll(freshSchema.keySet());
        assertEquals(Set.of(), onlyFresh, "tablas solo en DB nueva: " + onlyFresh);
        assertEquals(Set.of(), onlyLegacy, "tablas solo en DB antigua: " + onlyLegacy);

        List<String> diffs = new ArrayList<>();
        for (String table : freshSchema.keySet()) {
            TableShape f = freshSchema.get(table);
            TableShape l = legacySchema.get(table);
            if (!f.equals(l)) {
                diffs.add(table + ":\n  fresh=" + f + "\n  legacy=" + l);
            }
        }
        assertTrue(diffs.isEmpty(), "divergencias de esquema:\n" + String.join("\n", diffs));
    }

    // ── snapshot estructural por tabla ──────────────────────────────

    private record TableShape(Set<String> columns, Set<String> indexes, Set<String> fks) {
    }

    private static Map<String, TableShape> snapshotSchema(SqliteDatabase db) throws Exception {
        Map<String, TableShape> out = new TreeMap<>();
        try (Connection c = db.openConnection(); Statement st = c.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = st.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            for (String table : tables) {
                out.put(table, new TableShape(columns(c, table), indexes(c, table), fks(c, table)));
            }
        }
        return out;
    }

    private static Set<String> columns(Connection c, String table) throws Exception {
        Set<String> out = new TreeSet<>();
        try (PreparedStatement ps = c.prepareStatement("PRAGMA table_info(" + table + ")");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString("name") + "|" + rs.getString("type")
                    + "|notnull=" + rs.getInt("notnull")
                    + "|dflt=" + rs.getString("dflt_value")
                    + "|pk=" + rs.getInt("pk"));
            }
        }
        return out;
    }

    private static Set<String> indexes(Connection c, String table) throws Exception {
        Set<String> out = new TreeSet<>();
        try (PreparedStatement ps = c.prepareStatement("PRAGMA index_list(" + table + ")");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString("name") + "|unique=" + rs.getInt("unique")
                    + "|origin=" + rs.getString("origin"));
            }
        }
        return out;
    }

    private static Set<String> fks(Connection c, String table) throws Exception {
        Set<String> out = new TreeSet<>();
        try (PreparedStatement ps = c.prepareStatement("PRAGMA foreign_key_list(" + table + ")");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString("table") + "|" + rs.getString("from")
                    + "->" + rs.getString("to") + "|on_delete=" + rs.getString("on_delete"));
            }
        }
        return out;
    }

    // ── fixture: esquema fd34a48 (v2.0.1), textual ──────────────────

    private static void createLegacySchemaV201(Path file) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");

            st.executeUpdate(
                "CREATE TABLE users (" +
                "  uid TEXT PRIMARY KEY," +
                "  email TEXT NOT NULL," +
                "  created_at_epoch_sec INTEGER NOT NULL," +
                "  updated_at_epoch_sec INTEGER NOT NULL" +
                ")");

            st.executeUpdate(
                "CREATE TABLE accounts (" +
                "  id TEXT PRIMARY KEY," +
                "  user_uid TEXT NOT NULL," +
                "  name TEXT NOT NULL," +
                "  type TEXT NOT NULL," +
                "  currency TEXT NOT NULL," +
                "  created_at_epoch_sec INTEGER NOT NULL," +
                "  updated_at_epoch_sec INTEGER NOT NULL," +
                "  FOREIGN KEY(user_uid) REFERENCES users(uid) ON DELETE CASCADE" +
                ")");
            st.executeUpdate("CREATE INDEX idx_accounts_user ON accounts(user_uid)");

            st.executeUpdate(
                "CREATE TABLE categories (" +
                "  id TEXT PRIMARY KEY," +
                "  user_uid TEXT NOT NULL," +
                "  name TEXT NOT NULL," +
                "  parent_id TEXT NULL," +
                "  created_at_epoch_sec INTEGER NOT NULL," +
                "  updated_at_epoch_sec INTEGER NOT NULL," +
                "  FOREIGN KEY(user_uid) REFERENCES users(uid) ON DELETE CASCADE," +
                "  FOREIGN KEY(parent_id) REFERENCES categories(id) ON DELETE CASCADE" +
                ")");
            st.executeUpdate("CREATE INDEX idx_categories_user ON categories(user_uid)");
            st.executeUpdate("CREATE INDEX idx_categories_parent ON categories(parent_id)");

            st.executeUpdate(
                "CREATE TABLE transactions (" +
                "  id TEXT PRIMARY KEY," +
                "  user_uid TEXT NOT NULL," +
                "  account_id TEXT NOT NULL," +
                "  category_id TEXT NOT NULL," +
                "  kind TEXT NOT NULL," +
                "  amount_cents INTEGER NOT NULL," +
                "  occurred_at_epoch_sec INTEGER NOT NULL," +
                "  note TEXT NULL," +
                "  created_at_epoch_sec INTEGER NOT NULL," +
                "  updated_at_epoch_sec INTEGER NOT NULL," +
                "  FOREIGN KEY(user_uid) REFERENCES users(uid) ON DELETE CASCADE," +
                "  FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE RESTRICT," +
                "  FOREIGN KEY(category_id) REFERENCES categories(id) ON DELETE RESTRICT" +
                ")");
            st.executeUpdate("CREATE INDEX idx_tx_user ON transactions(user_uid)");
            st.executeUpdate("CREATE INDEX idx_tx_account ON transactions(account_id)");
            st.executeUpdate("CREATE INDEX idx_tx_category ON transactions(category_id)");
            st.executeUpdate("CREATE INDEX idx_tx_occurred ON transactions(occurred_at_epoch_sec)");

            st.executeUpdate(
                "CREATE TABLE transfers (" +
                "  id TEXT PRIMARY KEY," +
                "  user_uid TEXT NOT NULL," +
                "  from_account_id TEXT NOT NULL," +
                "  to_account_id TEXT NOT NULL," +
                "  amount_cents INTEGER NOT NULL," +
                "  occurred_at_epoch_sec INTEGER NOT NULL," +
                "  note TEXT NULL," +
                "  created_at_epoch_sec INTEGER NOT NULL," +
                "  updated_at_epoch_sec INTEGER NOT NULL," +
                "  FOREIGN KEY(user_uid) REFERENCES users(uid) ON DELETE CASCADE," +
                "  FOREIGN KEY(from_account_id) REFERENCES accounts(id) ON DELETE RESTRICT," +
                "  FOREIGN KEY(to_account_id) REFERENCES accounts(id) ON DELETE RESTRICT" +
                ")");
            st.executeUpdate("CREATE INDEX idx_transfers_user ON transfers(user_uid)");
            st.executeUpdate("CREATE INDEX idx_transfers_from ON transfers(from_account_id)");
            st.executeUpdate("CREATE INDEX idx_transfers_to ON transfers(to_account_id)");
            st.executeUpdate("CREATE INDEX idx_transfers_occurred ON transfers(occurred_at_epoch_sec)");

            st.executeUpdate(
                "CREATE TABLE outbox (" +
                "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  user_uid TEXT NOT NULL," +
                "  entity_type TEXT NOT NULL," +
                "  entity_id TEXT NOT NULL," +
                "  operation TEXT NOT NULL," +
                "  payload_json TEXT NOT NULL," +
                "  status TEXT NOT NULL," +
                "  attempt_count INTEGER NOT NULL," +
                "  last_error TEXT NULL," +
                "  created_at_epoch_sec INTEGER NOT NULL," +
                "  updated_at_epoch_sec INTEGER NOT NULL," +
                "  FOREIGN KEY(user_uid) REFERENCES users(uid) ON DELETE CASCADE" +
                ")");
            st.executeUpdate("CREATE INDEX idx_outbox_user_status ON outbox(user_uid, status)");
            st.executeUpdate("CREATE INDEX idx_outbox_entity ON outbox(entity_type, entity_id)");
        }
    }
}
