package com.myfinaces.e2e;

import com.myfinaces.auth.AuthSession;
import com.myfinaces.auth.FirebaseAuthService;
import com.myfinaces.config.AppConfig;
import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.AppSchema;
import com.myfinaces.db.CategoryRepository;
import com.myfinaces.db.ObligationRepository;
import com.myfinaces.db.ObligationSettlementRepository;
import com.myfinaces.db.SqliteDatabase;
import com.myfinaces.db.TransactionRepository;
import com.myfinaces.db.UserRepository;
import com.myfinaces.service.ObligationService;
import com.myfinaces.sync.FirestoreSyncService;
import com.myfinaces.sync.ObligationSyncApplier;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Driver headless para la auditoría E2E de Fase 2F.
 *
 * Uso:
 *   E2EObligationsDriver pull <dbFile>
 *   E2EObligationsDriver push <dbFile> <RECEIVABLE|PAYABLE> <obligationCents> <settleCents>
 *   E2EObligationsDriver editSettle <dbFile> <settlementId> <newAmountCents>
 *   E2EObligationsDriver deleteSettle <dbFile> <settlementId>
 *   E2EObligationsDriver cancelObligation <dbFile> <obligationId>
 *   E2EObligationsDriver state <dbFile>
 *   E2EObligationsDriver remote
 *
 * Credenciales por variables de entorno E2E_EMAIL / E2E_PASSWORD.
 */
public final class E2EObligationsDriver {

    private static final String KIND_INCOME = "INCOME";
    private static final String KIND_EXPENSE = "EXPENSE";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("missing command");
            System.exit(2);
        }
        String command = args[0];
        AppConfig config = AppConfig.loadDefault();
        FirebaseAuthService auth = new FirebaseAuthService(config.firebaseApiKey());
        String email = System.getenv("E2E_EMAIL");
        String password = System.getenv("E2E_PASSWORD");
        if (email == null || password == null) {
            System.err.println("E2E_EMAIL/E2E_PASSWORD required");
            System.exit(2);
        }
        AuthSession session = auth.signInWithEmailPassword(email, password);
        FirestoreSyncService sync = new FirestoreSyncService(config.firebaseProjectId());

        switch (command) {
            case "remote" -> dumpRemote(sync, session);
            case "pull" -> pull(sync, session, Path.of(args[1]));
            case "push" -> push(sync, session, Path.of(args[1]), args[2], Long.parseLong(args[3]), Long.parseLong(args[4]));
            case "editSettle" -> editSettle(sync, session, Path.of(args[1]), args[2], Long.parseLong(args[3]));
            case "deleteSettle" -> deleteSettle(sync, session, Path.of(args[1]), args[2]);
            case "cancelObligation" -> cancelObligation(sync, session, Path.of(args[1]), args[2]);
            case "state" -> {
                SqliteDatabase db = openDb(Path.of(args[1]));
                dumpState(db, session.uid());
            }
            default -> {
                System.err.println("unknown command " + command);
                System.exit(2);
            }
        }
    }

    private static SqliteDatabase openDb(Path file) throws Exception {
        SqliteDatabase db = new SqliteDatabase(file);
        AppSchema.init(db);
        return db;
    }

    private static void pull(FirestoreSyncService sync, AuthSession s, Path dbFile) throws Exception {
        SqliteDatabase db = openDb(dbFile);
        UserRepository userRepo = new UserRepository(db);
        userRepo.upsert(s.uid(), s.email());
        AccountRepository accountRepo = new AccountRepository(db);
        CategoryRepository categoryRepo = new CategoryRepository(db);
        TransactionRepository txRepo = new TransactionRepository(db);
        ObligationRepository obligationRepo = new ObligationRepository(db);
        ObligationSettlementRepository settlementRepo = new ObligationSettlementRepository(db);
        ObligationSyncApplier applier = new ObligationSyncApplier(obligationRepo, settlementRepo, txRepo, db);

        for (CategoryRepository.Category c : sync.pullCategories(s)) {
            categoryRepo.upsertFromRemote(s.uid(), c);
        }
        for (AccountRepository.Account a : sync.pullAccounts(s)) {
            accountRepo.upsertFromRemote(s.uid(), a);
        }
        List<TransactionRepository.TransactionSyncRow> remoteTxs = sync.pullTransactions(s);
        Set<String> remoteTxIds = new HashSet<>();
        for (TransactionRepository.TransactionSyncRow t : remoteTxs) {
            remoteTxIds.add(t.id());
            txRepo.upsertFromRemote(s.uid(), t);
        }
        for (TransactionRepository.TransactionSyncRow local : txRepo.listAllForSync(s.uid())) {
            if (remoteTxIds.contains(local.id())) continue;
            if (settlementRepo.getByLinkedTransactionId(s.uid(), local.id()) != null) continue;
            deleteRow(db, "transactions", local.id());
        }
        var remoteObligations = sync.pullObligations(s);
        var r1 = applier.applyRemoteObligations(s.uid(), remoteObligations.items(), remoteObligations.documentIds());
        System.out.println("[pull] obligations applied=" + r1.applied() + " deferred=" + r1.deferred()
            + " rejected=" + r1.rejected() + " pruned=" + r1.pruned() + " preserved=" + r1.preserved());
        var remoteSettlements = sync.pullObligationSettlements(s);
        var r2 = applier.applyRemoteSettlements(s.uid(), remoteSettlements.items(), remoteSettlements.documentIds());
        System.out.println("[pull] settlements applied=" + r2.applied() + " deferred=" + r2.deferred()
            + " rejected=" + r2.rejected() + " pruned=" + r2.pruned() + " preserved=" + r2.preserved());
        dumpState(db, s.uid());
    }

    private static void push(FirestoreSyncService sync, AuthSession s, Path dbFile,
                             String type, long obligationCents, long settleCents) throws Exception {
        SqliteDatabase db = openDb(dbFile);
        seedUserAccountCategory(db, s);
        ObligationService service = new ObligationService(db, new ObligationRepository(db),
            new ObligationSettlementRepository(db), new TransactionRepository(db));
        String uid = s.uid();
        String accountId = queryScalar(db, "SELECT id FROM accounts WHERE user_uid = '" + uid + "' LIMIT 1");
        String finCatId = queryScalar(db,
            "SELECT id FROM categories WHERE user_uid = '" + uid + "' AND kind = '"
                + (type.equals("PAYABLE") ? KIND_EXPENSE : KIND_INCOME) + "' LIMIT 1");
        String oblType = type.equals("PAYABLE") ? "POR_PAGAR" : "POR_COBRAR";
        var obligation = service.createObligation(uid, oblType, "E2E " + type,
            "E2E Counterparty", "COP", obligationCents, now(), null, null, null, null);
        ObligationService.SettlementMutationResult result = null;
        if (settleCents > 0) {
            result = service.registerSettlement(uid, obligation.id(), accountId, finCatId,
                settleCents, now(), "e2e settle");
        }
        pushAllPending(sync, s, db);
        dumpState(db, uid);
    }

    private static void editSettle(FirestoreSyncService sync, AuthSession s, Path dbFile,
                                   String settlementId, long newAmountCents) throws Exception {
        SqliteDatabase db = openDb(dbFile);
        ObligationService service = new ObligationService(db, new ObligationRepository(db),
            new ObligationSettlementRepository(db), new TransactionRepository(db));
        ObligationSettlementRepository settlementRepo = new ObligationSettlementRepository(db);
        var st = settlementRepo.getByIdOrNull(s.uid(), settlementId);
        if (st == null) { System.err.println("settlement not found"); System.exit(3); }
        String finCatId = queryScalar(db, "SELECT id FROM categories WHERE user_uid = '" + s.uid() + "' LIMIT 1");
        service.updateSettlement(s.uid(), settlementId, st.accountId(), finCatId,
            newAmountCents, st.occurredAtEpochSec(), st.note());
        pushAllPending(sync, s, db);
        dumpState(db, s.uid());
    }

    private static void deleteSettle(FirestoreSyncService sync, AuthSession s, Path dbFile,
                                     String settlementId) throws Exception {
        SqliteDatabase db = openDb(dbFile);
        ObligationService service = new ObligationService(db, new ObligationRepository(db),
            new ObligationSettlementRepository(db), new TransactionRepository(db));
        var result = service.deleteSettlement(s.uid(), settlementId);
        sync.deleteObligationSettlement(s, result.deletedSettlementId());
        try {
            sync.deleteTransaction(s, result.deletedTransactionId());
        } catch (Exception e) {
            System.out.println("[push] remote tx delete failed: " + e.getMessage());
        }
        dumpState(db, s.uid());
    }

    private static void cancelObligation(FirestoreSyncService sync, AuthSession s, Path dbFile,
                                         String obligationId) throws Exception {
        SqliteDatabase db = openDb(dbFile);
        ObligationService service = new ObligationService(db, new ObligationRepository(db),
            new ObligationSettlementRepository(db), new TransactionRepository(db));
        service.cancelObligation(s.uid(), obligationId, now());
        pushAllPending(sync, s, db);
        dumpState(db, s.uid());
    }

    private static void pushAllPending(FirestoreSyncService sync, AuthSession s, SqliteDatabase db) throws Exception {
        TransactionRepository txRepo = new TransactionRepository(db);
        ObligationRepository obligationRepo = new ObligationRepository(db);
        ObligationSettlementRepository settlementRepo = new ObligationSettlementRepository(db);
        sync.syncTransactions(s, txRepo);
        for (ObligationRepository.Obligation o : obligationRepo.listPendingForSync(s.uid())) {
            sync.syncObligation(s, o);
            obligationRepo.markSynced(s.uid(), o.id());
        }
        for (ObligationSettlementRepository.ObligationSettlement st : settlementRepo.listPendingForSync(s.uid())) {
            sync.syncObligationSettlement(s, st);
            settlementRepo.markSynced(s.uid(), st.id());
        }
    }

    private static void seedUserAccountCategory(SqliteDatabase db, AuthSession s) throws Exception {
        new UserRepository(db).upsert(s.uid(), s.email());
        AccountRepository accountRepo = new AccountRepository(db);
        CategoryRepository categoryRepo = new CategoryRepository(db);
        TransactionRepository txRepo = new TransactionRepository(db);
        if (categoryRepo.listAll(s.uid()).isEmpty()) {
            categoryRepo.create(s.uid(), "Ventas", null, KIND_INCOME, null);
            categoryRepo.create(s.uid(), "Servicios", null, KIND_EXPENSE, null);
        }
        if (accountRepo.list(s.uid()).isEmpty()) {
            var acc = accountRepo.create(s.uid(), "E2E Account", "BANK", "USD");
            String incomeCat = seedCategory(categoryRepo, s.uid(), KIND_INCOME);
            try (Connection c = db.openConnection()) {
                txRepo.createDirect(c, s.uid(), acc.id(), incomeCat,
                    KIND_INCOME, 100_000_000L, now(), "e2e seed income");
            }
        }
    }

    private static String seedCategory(CategoryRepository repo, String uid, String kind) throws Exception {
        for (CategoryRepository.Category c : repo.listAll(uid)) {
            if (kind.equals(c.kind())) return c.id();
        }
        return repo.create(uid, kind.equals(KIND_INCOME) ? "Ventas" : "Servicios", null, kind, null).id();
    }

    private static void dumpRemote(FirestoreSyncService sync, AuthSession s) throws Exception {
        var obligations = sync.pullObligations(s);
        var settlements = sync.pullObligationSettlements(s);
        var txs = sync.pullTransactions(s);
        System.out.println("REMOTE_OBLIGATIONS " + obligations.documentIds().size());
        obligations.items().forEach(o -> System.out.println(
            "ROBL id=" + o.id() + " type=" + o.type() + " title=" + o.title()
                + " original=" + o.originalAmountCents() + " cancelled=" + o.cancelledAtEpochSec()));
        System.out.println("REMOTE_SETTLEMENTS " + settlements.documentIds().size());
        settlements.items().forEach(st -> System.out.println(
            "RSET id=" + st.id() + " obligation=" + st.obligationId()
                + " tx=" + st.linkedTransactionId() + " amount=" + st.amountCents()));
        System.out.println("REMOTE_TRANSACTIONS " + txs.size());
        txs.forEach(t -> System.out.println(
            "RTX id=" + t.id() + " kind=" + t.kind() + " amount=" + t.amountCents()));
    }

    private static void dumpState(SqliteDatabase db, String uid) throws Exception {
        ObligationRepository obligationRepo = new ObligationRepository(db);
        ObligationSettlementRepository settlementRepo = new ObligationSettlementRepository(db);
        TransactionRepository txRepo = new TransactionRepository(db);
        ObligationService service = new ObligationService(db, obligationRepo, settlementRepo, txRepo);

        List<ObligationRepository.Obligation> obligations = obligationRepo.listAllByUser(uid);
        List<ObligationSettlementRepository.ObligationSettlement> settlements = settlementRepo.listAllByUser(uid);
        List<TransactionRepository.TransactionSyncRow> txs = txRepo.listAllForSync(uid);

        System.out.println("COUNTS obligations=" + obligations.size()
            + " settlements=" + settlements.size() + " transactions=" + txs.size());
        for (ObligationRepository.Obligation o : obligations) {
            var st = service.getResolvedState(uid, o.id(), now());
            System.out.println("OBL id=" + o.id() + " type=" + o.type() + " title=" + o.title()
                + " original=" + o.originalAmountCents() + " settled=" + st.totalSettledCents()
                + " pending=" + st.pendingAmountCents() + " status=" + st.status());
        }
        for (ObligationSettlementRepository.ObligationSettlement st : settlements) {
            System.out.println("SET id=" + st.id() + " obligation=" + st.obligationId()
                + " tx=" + st.linkedTransactionId() + " amount=" + st.amountCents()
                + " account=" + st.accountId());
        }
        for (TransactionRepository.TransactionSyncRow t : txs) {
            boolean linked = settlementRepo.getByLinkedTransactionId(uid, t.id()) != null;
            System.out.println("TX id=" + t.id() + " kind=" + t.kind() + " amount=" + t.amountCents()
                + " account=" + t.accountId() + " linkedToSettlement=" + linked);
        }
        try (Connection c = db.openConnection(); Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery(
                "SELECT account_id, SUM(CASE WHEN kind='INCOME' THEN amount_cents ELSE -amount_cents END) "
                    + "FROM transactions WHERE user_uid='" + uid + "' GROUP BY account_id");
            while (rs.next()) {
                System.out.println("BALANCE account=" + rs.getString(1) + " cents=" + rs.getLong(2));
            }
        }
    }

    private static void deleteRow(SqliteDatabase db, String table, String id) throws Exception {
        try (Connection c = db.openConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM " + table + " WHERE id='" + id + "'");
        }
    }

    private static String queryScalar(SqliteDatabase db, String sql) throws Exception {
        try (Connection c = db.openConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    private E2EObligationsDriver() {}
}
