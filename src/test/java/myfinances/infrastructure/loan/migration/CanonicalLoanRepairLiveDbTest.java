package myfinances.infrastructure.loan.migration;

import com.myfinaces.auth.AuthSession;
import com.myfinaces.auth.AuthSessionManager;
import com.myfinaces.auth.FirebaseAuthService;
import com.myfinaces.config.AppConfig;
import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.SessionRepository;
import com.myfinaces.db.SqliteDatabase;
import com.myfinaces.db.TransactionRepository;
import com.myfinaces.sync.FirestoreSyncService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Sprint 7J.6 — reparación histórica de la base real: enlaza el journal con
 * las transacciones legacy, audita cada evento financiero y elimina solo los
 * duplicados canónicos demostrados ({@code canonical-loan-tx:{eventId}}).
 *
 * <p>Modos:
 * <ul>
 *   <li>{@code -Dmyfinances.liveDb=<path>} — copia la base a un archivo temporal;
 *       ejecuta enlace + borrado LOCAL únicamente (dry-run seguro, sin tocar Firestore).</li>
 *   <li>{@code -Dmyfinances.targetDb=<path> -Dmyfinances.confirm=YES} — repara en sitio
 *       y propaga los borrados a Firestore con la sesión persistida del usuario.</li>
 * </ul>
 */
class CanonicalLoanRepairLiveDbTest {

    private record JournalRow(
        String eventId, String loanId, String eventType, Long amountCents,
        String accountId, String journalTxId, long occurredAt,
        String loanType, String defaultAccountId, String counterparty,
        boolean reversed, boolean synthetic) {}

    private record AuditRow(
        JournalRow event, String legacyTxId, String canonicalTxId,
        boolean canonicalExists, String classification) {}

    private record LoanRow(String loanId, long principal, long paid, long pending, String fingerprint) {}

    @Test
    void repairCanonicalLoanDuplicates() throws Exception {
        String sourceDb = System.getProperty("myfinances.liveDb");
        String targetDb = System.getProperty("myfinances.targetDb");
        boolean direct = "YES".equals(System.getProperty("myfinances.confirm"));
        Assumptions.assumeTrue(sourceDb != null || (direct && targetDb != null),
            "Set -Dmyfinances.liveDb=<db> (copy mode) or -Dmyfinances.targetDb=<db> -Dmyfinances.confirm=YES");

        Path dbPath;
        if (direct) {
            dbPath = Path.of(targetDb);
            System.out.println("*** MODO DIRECTO — reparando en sitio + Firestore: " + dbPath);
        } else {
            Path copy = Files.createTempFile("myfinances-7j6", ".db");
            Files.copy(Path.of(sourceDb), copy, StandardCopyOption.REPLACE_EXISTING);
            dbPath = copy;
            System.out.println("Modo copia (dry-run local) — reparando: " + dbPath);
        }

        SqliteDatabase database = new SqliteDatabase(dbPath);
        AccountRepository accountRepo = new AccountRepository(database);
        List<String> owners = journalOwners(database);

        // ============ FASE 1: enlazar journal con transacciones legacy ============
        System.out.println("\n===== FASE 1 — LegacyLoanTransactionLinkReconciler =====");
        LegacyLoanTransactionLinkReconciler.LinkReport linkReport =
            LegacyLoanTransactionLinkReconciler.reconcile(database);
        System.out.println("link: scanned=" + linkReport.eventsScanned()
            + " linked=" + linkReport.eventsLinked()
            + " ambiguous=" + linkReport.ambiguous()
            + " unmatched=" + linkReport.unmatched()
            + " errors=" + linkReport.errors().size());
        linkReport.errors().forEach(e -> System.out.println("   error " + e));

        // ============ FASE 2: reporte completo ANTES de borrar ============
        Map<String, List<AuditRow>> auditBefore = new LinkedHashMap<>();
        Map<String, Map<String, Long>> balancesBefore = new LinkedHashMap<>();
        Map<String, List<LoanRow>> loansBefore = new LinkedHashMap<>();
        for (String owner : owners) {
            System.out.println("\n===== REPORTE ANTES — owner " + owner + " =====");
            auditBefore.put(owner, printAudit(database, owner));
            balancesBefore.put(owner, balances(database, accountRepo, owner));
            loansBefore.put(owner, loanRows(database, owner));
        }

        // ============ FASE 3: borrado seguro de duplicados canónicos ============
        System.out.println("\n===== FASE 3 — CanonicalLoanDuplicateReconciler =====");
        AuthSession session = direct ? realSession(database) : dummySession(owners);
        FirestoreSyncService sync = new FirestoreSyncService(
            direct ? AppConfig.loadDefault().firebaseProjectId() : "test-project");
        int totalDeleted = 0;
        java.util.Set<String> deletedIds = new java.util.HashSet<>();
        for (String owner : owners) {
            java.util.Set<String> beforeIds = txIds(database, owner);
            boolean remote = direct && owner.equals(session.uid());
            AuthSession ownerSession = owner.equals(session.uid()) ? session
                : new AuthSession(owner, "", "", "no-remote", "", 0L);
            CanonicalLoanDuplicateReconciler.DedupReport dedup =
                CanonicalLoanDuplicateReconciler.reconcile(database, sync, ownerSession, remote);
            totalDeleted += dedup.duplicatesDeleted();
            java.util.Set<String> afterIds = txIds(database, owner);
            beforeIds.removeAll(afterIds);
            deletedIds.addAll(beforeIds);
            System.out.println("owner " + owner + " remote=" + remote
                + " -> found=" + dedup.duplicatesFound()
                + " deleted=" + dedup.duplicatesDeleted()
                + " conserved=" + dedup.conserved()
                + " errors=" + dedup.errors().size());
        }
        System.out.println("TOTAL ELIMINADAS: " + totalDeleted);

        // ============ FASE 4: reporte DESPUÉS + validaciones ============
        for (String owner : owners) {
            System.out.println("\n===== REPORTE DESPUÉS — owner " + owner + " =====");
            printAudit(database, owner);
            compareBalances(balancesBefore.get(owner), balances(database, accountRepo, owner));
            compareLoans(loansBefore.get(owner), loanRows(database, owner));
        }

        // ============ Verificación remota (solo modo directo) ============
        if (direct) {
            verifyRemoteAbsent(sync, session, deletedIds);
        }
    }

    private java.util.Set<String> txIds(SqliteDatabase database, String owner) throws SQLException {
        java.util.Set<String> ids = new java.util.HashSet<>();
        try (Connection c = database.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id FROM transactions WHERE user_uid = ?")) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getString(1));
            }
        }
        return ids;
    }

    // ---------------- reporte ----------------

    private List<AuditRow> printAudit(SqliteDatabase database, String owner) throws SQLException {
        List<JournalRow> events = journalRows(database, owner);
        List<AuditRow> rows = new ArrayList<>();
        int synthetic = 0;
        for (JournalRow e : events) {
            if (e.synthetic()) {
                synthetic++;
                continue;
            }
            String canonicalId = CanonicalLoanEventIds.deterministicTransactionId(e.eventId());
            String canonicalTx = txExists(database, owner, canonicalId) ? canonicalId : null;
            String legacy = e.journalTxId() != null && !e.journalTxId().equals(canonicalId)
                ? e.journalTxId() : null;
            String classification;
            if (e.journalTxId() == null) {
                int candidates = LegacyLoanTransactionLinkReconciler.candidateIds(
                    database, owner, e.eventId(), e.eventType(), e.loanType(), e.amountCents(),
                    e.accountId(), e.defaultAccountId(), e.occurredAt(), e.counterparty()).size();
                classification = candidates > 1 ? "Ambiguo" : "Falta transacción";
            } else if (canonicalTx != null && !canonicalTx.equals(e.journalTxId())) {
                classification = isProvenSafe(database, owner, e, canonicalTx) ? "Duplicado histórico" : "Ambiguo";
            } else {
                classification = "OK";
            }
            rows.add(new AuditRow(e, legacy, canonicalTx, canonicalTx != null, classification));
            System.out.printf("  %s | %-10s | journal=%s | legacy=%s | canon=%s | %s%n",
                shortId(e.eventId()), e.eventType(),
                shortId(e.journalTxId()), shortId(legacy), shortId(canonicalTx), classification);
        }

        long totalTx = count(database, "SELECT COUNT(*) FROM transactions WHERE user_uid = ?", owner);
        long duplicates = rows.stream().filter(r -> "Duplicado histórico".equals(r.classification())).count();
        long unlinked = rows.stream().filter(r -> r.event().journalTxId() == null).count();
        long orphans = orphanCount(database, owner);
        long pendingBackfill = rows.stream()
            .filter(r -> r.event().journalTxId() == null && !r.canonicalExists()).count();
        long ambiguos = rows.stream().filter(r -> "Ambiguo".equals(r.classification())).count();

        System.out.println("  -- totales: transactions=" + totalTx
            + " | duplicados históricos=" + duplicates
            + " | journal sin enlazar=" + unlinked
            + " | huérfanas=" + orphans
            + " | backfill pendientes=" + pendingBackfill
            + " | ambiguos=" + ambiguos
            + " | eventos sintéticos=" + synthetic);
        return rows;
    }

    /** Réplica de las guardas del reconciliador para clasificar el reporte. */
    private boolean isProvenSafe(SqliteDatabase database, String owner, JournalRow e, String canonicalTxId)
        throws SQLException {
        boolean isLent = "LENT".equalsIgnoreCase(e.loanType());
        String expectedKind = switch (e.eventType()) {
            case "CREATION" -> isLent ? "LOAN_LENT_OUT" : "LOAN_BORROWED_IN";
            case "TOPUP" -> isLent ? "LOAN_LENT_TOPUP" : "LOAN_BORROWED_TOPUP";
            case "PAYMENT" -> isLent ? "LOAN_REPAYMENT_PRINCIPAL_IN" : "LOAN_REPAYMENT_PRINCIPAL_OUT";
            case "ADJUSTMENT" -> {
                boolean inc = e.amountCents() != null && e.amountCents() > 0;
                yield isLent
                    ? (inc ? "LOAN_LENT_CORRECTION_OUT" : "LOAN_LENT_CORRECTION_IN")
                    : (inc ? "LOAN_BORROWED_CORRECTION_IN" : "LOAN_BORROWED_CORRECTION_OUT");
            }
            default -> null;
        };
        String expectedAccount = e.accountId() != null && !e.accountId().isBlank()
            ? e.accountId() : e.defaultAccountId();
        if (expectedKind == null || expectedAccount == null || e.amountCents() == null) {
            return false;
        }
        long expectedAmount = Math.abs(e.amountCents());
        TransactionRepository.TransactionSyncRow canonical =
            new TransactionRepository(database).getForSyncByIdOrNull(owner, canonicalTxId);
        TransactionRepository.TransactionSyncRow legacy =
            new TransactionRepository(database).getForSyncByIdOrNull(owner, e.journalTxId());
        if (canonical == null || legacy == null) {
            return false;
        }
        boolean canonicalSig = expectedKind.equals(canonical.kind())
            && expectedAccount.equals(canonical.accountId())
            && canonical.amountCents() == expectedAmount
            && canonical.occurredAtEpochSec() == e.occurredAt();
        boolean legacySig;
        if (expectedKind.equals(legacy.kind())) {
            legacySig = expectedAccount.equals(legacy.accountId())
                && legacy.occurredAtEpochSec() == e.occurredAt()
                && ("CREATION".equals(e.eventType()) || legacy.amountCents() == expectedAmount);
        } else {
            // Formato legacy antiguo: EXPENSE/INCOME + categoría sistema + nota "<kind>: <contraparte>".
            String legacyKind = "LENT".equalsIgnoreCase(e.loanType()) ? "EXPENSE" : "INCOME";
            legacySig = "CREATION".equals(e.eventType())
                && e.counterparty() != null
                && legacyKind.equals(legacy.kind())
                && ("system-loan-" + owner).equals(legacy.categoryId())
                && expectedAccount.equals(legacy.accountId())
                && legacy.amountCents() == expectedAmount
                && java.util.Objects.equals(legacy.note(), expectedKind + ": " + e.counterparty())
                && (legacy.occurredAtEpochSec() == e.occurredAt()
                    || legacy.createdAtEpochSec() == e.occurredAt());
        }
        return canonicalSig && legacySig && !referencedElsewhere(database, owner, canonicalTxId);
    }

    private boolean referencedElsewhere(SqliteDatabase database, String owner, String txId) throws SQLException {
        boolean hasMovements = tableExists(database, "loan_movements");
        String sql = "SELECT EXISTS(SELECT 1 FROM loan_journal_v1 WHERE owner_id=? AND transaction_id=?) "
            + "OR EXISTS(SELECT 1 FROM loan_payments WHERE user_uid=? AND linked_transaction_id=?) "
            + (hasMovements ? "OR EXISTS(SELECT 1 FROM loan_movements WHERE user_uid=? AND linked_transaction_id=?)" : "OR 0");
        try (Connection c = database.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, owner); ps.setString(2, txId);
            ps.setString(3, owner); ps.setString(4, txId);
            if (hasMovements) { ps.setString(5, owner); ps.setString(6, txId); }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) != 0;
            }
        }
    }

    private long orphanCount(SqliteDatabase database, String owner) throws SQLException {
        boolean hasMovements = tableExists(database, "loan_movements");
        String sql = "SELECT COUNT(*) FROM transactions t WHERE t.user_uid = ? AND t.kind LIKE 'LOAN\\_%' ESCAPE '\\' "
            + "AND NOT EXISTS (SELECT 1 FROM loan_journal_v1 j WHERE j.owner_id=t.user_uid AND j.transaction_id=t.id) "
            + "AND NOT EXISTS (SELECT 1 FROM loan_payments p WHERE p.user_uid=t.user_uid AND p.linked_transaction_id=t.id) "
            + (hasMovements
                ? "AND NOT EXISTS (SELECT 1 FROM loan_movements m WHERE m.user_uid=t.user_uid AND m.linked_transaction_id=t.id)"
                : "");
        try (Connection c = database.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    // ---------------- snapshots de validación ----------------

    private Map<String, Long> balances(SqliteDatabase database, AccountRepository repo, String owner) throws SQLException {
        Map<String, Long> out = new LinkedHashMap<>();
        try (Connection c = database.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, name FROM accounts WHERE user_uid = ? ORDER BY name")) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString("name") + " [" + shortId(rs.getString("id")) + "]",
                        repo.computeBalanceCents(owner, rs.getString("id")));
                }
            }
        }
        return out;
    }

    private void compareBalances(Map<String, Long> before, Map<String, Long> after) {
        System.out.println("  -- SALDOS (antes -> después | diff):");
        for (Map.Entry<String, Long> e : after.entrySet()) {
            long b = before.getOrDefault(e.getKey(), 0L);
            long a = e.getValue();
            System.out.printf("     %-40s %,12d -> %,12d | diff=%,12d %s%n",
                e.getKey(), b, a, a - b, a == b ? "=" : (a - b != 0 ? "CORREGIDA" : ""));
        }
    }

    private List<LoanRow> loanRows(SqliteDatabase database, String owner) throws SQLException {
        List<LoanRow> out = new ArrayList<>();
        try (Connection c = database.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT loan_id, principal_cents, total_paid_cents, pending_cents, journal_fingerprint " +
            "FROM loan_snapshots_v1 WHERE owner_id = ? ORDER BY loan_id")) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new LoanRow(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getString(5)));
                }
            }
        }
        return out;
    }

    private void compareLoans(List<LoanRow> before, List<LoanRow> after) {
        Map<String, LoanRow> beforeById = new LinkedHashMap<>();
        for (LoanRow r : before) beforeById.put(r.loanId(), r);
        int identical = 0, changed = 0;
        System.out.println("  -- PRÉSTAMOS (principal/pagado/pendiente/fingerprint):");
        for (LoanRow a : after) {
            LoanRow b = beforeById.get(a.loanId());
            boolean same = b != null && b.principal() == a.principal() && b.paid() == a.paid()
                && b.pending() == a.pending() && java.util.Objects.equals(b.fingerprint(), a.fingerprint());
            if (same) identical++; else changed++;
            System.out.printf("     %s principal=%,d paid=%,d pending=%,d fp=%s -> %s%n",
                shortId(a.loanId()), a.principal(), a.paid(), a.pending(),
                a.fingerprint() == null ? "-" : a.fingerprint().substring(0, 8),
                same ? "IDÉNTICO" : "¡CAMBIÓ!");
        }
        System.out.println("     loans=" + after.size() + " idénticos=" + identical + " cambiados=" + changed);
    }

    // ---------------- verificación remota ----------------

    private void verifyRemoteAbsent(FirestoreSyncService sync,
            AuthSession session, java.util.Set<String> deletedIds) throws Exception {
        System.out.println("\n===== VERIFICACIÓN REMOTA (Firestore) =====");
        List<TransactionRepository.TransactionSyncRow> remote = sync.pullTransactions(session);
        java.util.Set<String> remoteIds = new java.util.HashSet<>();
        for (TransactionRepository.TransactionSyncRow r : remote) remoteIds.add(r.id());
        int resurrected = 0;
        for (String id : deletedIds) {
            if (remoteIds.contains(id)) {
                resurrected++;
                System.out.println("   REMOTO AÚN PRESENTE: " + id);
            }
        }
        System.out.println("   docs remotos=" + remoteIds.size()
            + " | eliminadas localmente=" + deletedIds.size()
            + " | aún presentes en remoto=" + resurrected);
    }

    // ---------------- helpers ----------------

    private List<String> journalOwners(SqliteDatabase database) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = database.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT DISTINCT owner_id FROM loan_journal_v1 ORDER BY owner_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private List<JournalRow> journalRows(SqliteDatabase database, String owner) throws SQLException {
        String sql = "SELECT j.event_id, j.loan_id, j.event_type, j.amount_cents, j.account_id, " +
            "j.transaction_id, j.occurred_at, s.loan_type, s.default_account_id, s.counterparty_name, " +
            "EXISTS(SELECT 1 FROM loan_journal_v1 r WHERE r.owner_id=j.owner_id " +
            "  AND r.event_type='REVERSAL' AND r.payload_target_event_id=j.event_id) AS reversed " +
            "FROM loan_journal_v1 j " +
            "JOIN loan_snapshots_v1 s ON s.owner_id=j.owner_id AND s.loan_id=j.loan_id " +
            "WHERE j.owner_id=? AND j.event_type IN ('CREATION','TOPUP','PAYMENT','ADJUSTMENT') " +
            "ORDER BY j.loan_id, j.occurred_at, j.event_id";
        List<JournalRow> out = new ArrayList<>();
        try (Connection c = database.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String eventId = rs.getString("event_id");
                    String loanId = rs.getString("loan_id");
                    String type = rs.getString("event_type");
                    long occurredAt = rs.getLong("occurred_at");
                    boolean synthetic = "ADJUSTMENT".equals(type)
                        && CanonicalLoanEventIds.deterministic(
                            loanId, "ADJUSTMENT", "synth:adjust:" + loanId, occurredAt).equals(eventId);
                    Object amt = rs.getObject("amount_cents");
                    out.add(new JournalRow(
                        eventId, loanId, type,
                        amt == null ? null : ((Number) amt).longValue(),
                        rs.getString("account_id"), rs.getString("transaction_id"),
                        occurredAt, rs.getString("loan_type"), rs.getString("default_account_id"),
                        rs.getString("counterparty_name"),
                        rs.getInt("reversed") != 0, synthetic));
                }
            }
        }
        return out;
    }

    private boolean txExists(SqliteDatabase database, String owner, String txId) throws SQLException {
        try (Connection c = database.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT 1 FROM transactions WHERE user_uid = ? AND id = ?")) {
            ps.setString(1, owner); ps.setString(2, txId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private long count(SqliteDatabase database, String sql, String owner) throws SQLException {
        try (Connection c = database.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 0; }
        }
    }

    private boolean tableExists(SqliteDatabase database, String name) throws SQLException {
        try (Connection c = database.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private AuthSession realSession(SqliteDatabase database) throws Exception {
        AuthSession stored = new SessionRepository(database).load()
            .orElseThrow(() -> new IllegalStateException("Sin sesión persistida en user_session"));
        AuthSessionManager manager = new AuthSessionManager(
            new SessionRepository(database),
            new FirebaseAuthService(AppConfig.loadDefault().firebaseApiKey())::refresh,
            stored);
        AuthSession valid = manager.validSession();
        System.out.println("Sesión válida para uid=" + valid.uid());
        return valid;
    }

    private AuthSession dummySession(List<String> owners) {
        return new AuthSession(owners.isEmpty() ? "none" : owners.getFirst(), "", "", "no-remote", "", 0L);
    }

    private static String shortId(String id) {
        return id == null ? "-" : (id.length() > 8 ? id.substring(0, 8) : id);
    }
}
