package myfinances.infrastructure.loan.migration;

import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.SqliteDatabase;
import com.myfinaces.db.TransactionKind;
import com.myfinaces.db.TransactionRepository;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import myfinances.infrastructure.loan.jdbc.LoanPersistenceException;

/**
 * Reconciliación idempotente de artefactos financieros derivados del journal
 * canónico ({@code loan_journal_v1}).
 * <p>
 * El saldo de una cuenta no se almacena: es una proyección derivada calculada
 * en lectura ({@link AccountRepository#computeBalanceCents}) como la suma
 * firmada de {@code transactions} más {@code transfers}. Por tanto un "saldo
 * incorrecto" sólo puede significar que el conjunto de transacciones LOAN_* es
 * incorrecto: filas duplicadas o faltantes respecto a lo que el journal
 * canónico declara.
 * <p>
 * La fuente de verdad es {@code loan_journal_v1}: cada evento financiero
 * (CREATION, TOPUP, ADJUSTMENT, PAYMENT) declara exactamente un artefacto
 * financiero con {@code transaction_id} (cuando existe), {@code account_id},
 * {@code amount_cents} y {@code occurred_at}. Los eventos cubiertos por un
 * REVERSAL no declaran artefacto: la reversión elimina su efecto financiero.
 * <p>
 * El algoritmo reclama, para cada evento esperado, una transacción equivalente
 * (misma cuenta, mismo kind canónico y mismo monto; para CREATION el monto se
 * tolera porque el replay pliega ajustes en el principal). Las transacciones
 * LOAN_* no reclamadas por ningún evento son sobrantes y se eliminan, salvo
 * que estén referenciadas por el journal o por el transporte
 * ({@code loan_payments}/{@code loan_movements.linked_transaction_id}), en
 * cuyo caso se conservan y se reportan. Los eventos sin transacción
 * equivalente se materializan con el mismo mecanismo determinístico que
 * {@link CanonicalLoanTransactionBackfill} (id preferente =
 * {@code transaction_id} del evento o UUID determinístico del event_id).
 * <p>
 * Utilidad de mantenimiento: se invoca explícitamente; no forma parte del
 * flujo de sincronización.
 */
public final class CanonicalAccountBalanceReconciler {

    private CanonicalAccountBalanceReconciler() {}

    private static final Set<String> LOAN_KINDS = Set.of(
        TransactionKind.LOAN_LENT_OUT.name(),
        TransactionKind.LOAN_LENT_TOPUP.name(),
        TransactionKind.LOAN_LENT_CORRECTION.name(),
        TransactionKind.LOAN_LENT_CORRECTION_IN.name(),
        TransactionKind.LOAN_LENT_CORRECTION_OUT.name(),
        TransactionKind.LOAN_BORROWED_IN.name(),
        TransactionKind.LOAN_BORROWED_TOPUP.name(),
        TransactionKind.LOAN_BORROWED_CORRECTION.name(),
        TransactionKind.LOAN_BORROWED_CORRECTION_IN.name(),
        TransactionKind.LOAN_BORROWED_CORRECTION_OUT.name(),
        TransactionKind.LOAN_REPAYMENT_PRINCIPAL_IN.name(),
        TransactionKind.LOAN_REPAYMENT_PRINCIPAL_OUT.name()
    );

    public record AccountDrift(
        String accountId,
        long storedBalanceCents,
        long reconciledBalanceCents,
        long driftCents,
        int artifactsCreated,
        int artifactsDeleted,
        int artifactsKeptProtected
    ) {}

    public record ReconciliationReport(
        String userUid,
        int accountsScanned,
        int expectedArtifacts,
        int actualArtifacts,
        int transactionsCreated,
        int transactionsDeleted,
        int transactionsKeptProtected,
        List<AccountDrift> accounts,
        List<String> warnings,
        List<String> errors
    ) {
        public boolean hasChanges() {
            return transactionsCreated > 0 || transactionsDeleted > 0;
        }
    }

    public static ReconciliationReport run(SqliteDatabase database, String userUid) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(userUid, "userUid");

        TransactionRepository txRepo = new TransactionRepository(database);
        AccountRepository accountRepo = new AccountRepository(database);

        List<ExpectedArtifact> expected = readExpectedArtifacts(database, userUid);
        List<ActualTransaction> actual = readLoanTransactions(database, userUid);
        Set<String> protectedIds = readProtectedTransactionIds(database, userUid);

        List<String> accountIds = readAccountIds(database, userUid);
        Map<String, Long> storedBalances = new LinkedHashMap<>();
        try {
            for (String accountId : accountIds) {
                storedBalances.put(accountId, accountRepo.computeBalanceCents(userUid, accountId));
            }
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }

        Set<String> claimedIds = new LinkedHashSet<>();
        List<ExpectedArtifact> missing = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<String, Integer> createdByAccount = new LinkedHashMap<>();
        Map<String, Integer> deletedByAccount = new LinkedHashMap<>();
        Map<String, Integer> keptProtectedByAccount = new LinkedHashMap<>();

        List<ExpectedArtifact> orderedExpected = new ArrayList<>(expected);
        orderedExpected.sort(Comparator
            .comparing((ExpectedArtifact e) -> e.accountId)
            .thenComparing(ExpectedArtifact::kind)
            .thenComparingLong(ExpectedArtifact::occurredAt)
            .thenComparing(ExpectedArtifact::eventId));

        for (ExpectedArtifact event : orderedExpected) {
            ActualTransaction claimed = claimCandidate(actual, claimedIds, event);
            if (claimed == null) {
                missing.add(event);
            } else {
                claimedIds.add(claimed.id());
            }
        }

        int created = 0;
        int deleted = 0;
        int keptProtected = 0;

        for (ActualTransaction tx : actual) {
            if (claimedIds.contains(tx.id())) {
                continue;
            }
            if (protectedIds.contains(tx.id())) {
                keptProtected++;
                keptProtectedByAccount.merge(tx.accountId(), 1, Integer::sum);
                warnings.add("transacción sobrante protegida por referencia: " + tx.id()
                    + " kind=" + tx.kind() + " account=" + tx.accountId() + " amount=" + tx.amountCents());
                continue;
            }
            try {
                txRepo.deleteFailedLoanTransaction(userUid, tx.id());
                deleted++;
                deletedByAccount.merge(tx.accountId(), 1, Integer::sum);
            } catch (SQLException ex) {
                errors.add("no se pudo eliminar sobrante " + tx.id() + ": " + ex.getMessage());
            }
        }

        for (ExpectedArtifact event : missing) {
            String transactionId = event.transactionId != null && !event.transactionId.isBlank()
                ? event.transactionId
                : deterministicTransactionId(event.eventId);
            try {
                TransactionRepository.TransactionSyncRow conflict =
                    txRepo.getForSyncByIdOrNull(userUid, transactionId);
                if (conflict != null) {
                    warnings.add("artefacto esperado " + event.eventId + " no creado: id " + transactionId
                        + " ya existe con firma distinta kind=" + conflict.kind()
                        + " amount=" + conflict.amountCents());
                    continue;
                }
                String categoryId = "PAYMENT".equals(event.eventType)
                    ? ensureRepaymentCategory(database, userUid)
                    : ensureLoanCategory(database, userUid);
                txRepo.createWithId(
                    transactionId,
                    userUid,
                    event.accountId,
                    categoryId,
                    event.kind(),
                    event.amountCents,
                    event.occurredAt,
                    event.note
                );
                created++;
                createdByAccount.merge(event.accountId, 1, Integer::sum);
            } catch (SQLException ex) {
                errors.add("no se pudo materializar artefacto " + event.eventId
                    + " -> " + transactionId + ": " + ex.getMessage());
            }
        }

        List<AccountDrift> drifts = new ArrayList<>();
        try {
            for (String accountId : accountIds) {
                long stored = storedBalances.getOrDefault(accountId, 0L);
                long reconciled = accountRepo.computeBalanceCents(userUid, accountId);
                int c = createdByAccount.getOrDefault(accountId, 0);
                int d = deletedByAccount.getOrDefault(accountId, 0);
                int k = keptProtectedByAccount.getOrDefault(accountId, 0);
                if (stored != reconciled || c > 0 || d > 0 || k > 0) {
                    drifts.add(new AccountDrift(accountId, stored, reconciled, stored - reconciled, c, d, k));
                }
            }
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }

        ReconciliationReport report = new ReconciliationReport(
            userUid,
            accountIds.size(),
            expected.size(),
            actual.size(),
            created,
            deleted,
            keptProtected,
            List.copyOf(drifts),
            List.copyOf(warnings),
            List.copyOf(errors)
        );
        System.out.println("[CanonicalAccountBalanceReconciler] user=" + userUid
            + " accounts=" + report.accountsScanned()
            + " expected=" + report.expectedArtifacts()
            + " actual=" + report.actualArtifacts()
            + " created=" + report.transactionsCreated()
            + " deleted=" + report.transactionsDeleted()
            + " keptProtected=" + report.transactionsKeptProtected()
            + " warnings=" + report.warnings().size()
            + " errors=" + report.errors().size());
        for (AccountDrift drift : report.accounts()) {
            System.out.println("[CanonicalAccountBalanceReconciler]   account=" + drift.accountId()
                + " stored=" + drift.storedBalanceCents()
                + " reconciled=" + drift.reconciledBalanceCents()
                + " drift=" + drift.driftCents()
                + " created=" + drift.artifactsCreated()
                + " deleted=" + drift.artifactsDeleted()
                + " keptProtected=" + drift.artifactsKeptProtected());
        }
        for (String warning : report.warnings()) {
            System.out.println("[CanonicalAccountBalanceReconciler]   warning " + warning);
        }
        for (String error : report.errors()) {
            System.out.println("[CanonicalAccountBalanceReconciler]   error " + error);
        }
        return report;
    }

    private static List<String> readAccountIds(SqliteDatabase database, String userUid) {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT id FROM accounts WHERE user_uid = ? ORDER BY name, id")) {
            statement.setString(1, userUid);
            List<String> out = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    out.add(result.getString("id"));
                }
            }
            return out;
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }

    private static ActualTransaction claimCandidate(
        List<ActualTransaction> actual,
        Set<String> claimedIds,
        ExpectedArtifact event
    ) {
        boolean ignoreAmount = "CREATION".equals(event.eventType);
        ActualTransaction best = null;
        for (ActualTransaction tx : actual) {
            if (claimedIds.contains(tx.id())) {
                continue;
            }
            if (!Objects.equals(tx.accountId(), event.accountId) || !tx.kind().equals(event.kind())) {
                continue;
            }
            if (!ignoreAmount && tx.amountCents() != event.amountCents) {
                continue;
            }
            if (best == null) {
                best = tx;
                continue;
            }
            int scoreTx = claimScore(tx, event);
            int scoreBest = claimScore(best, event);
            if (scoreTx > scoreBest
                || (scoreTx == scoreBest && compareForClaim(tx, best) < 0)) {
                best = tx;
            }
        }
        return best;
    }

    private static int claimScore(ActualTransaction tx, ExpectedArtifact event) {
        int score = 0;
        if (event.transactionId != null && event.transactionId.equals(tx.id())) {
            score += 4;
        }
        if (deterministicTransactionId(event.eventId).equals(tx.id())) {
            score += 2;
        }
        if (tx.occurredAt() == event.occurredAt) {
            score += 1;
        }
        return score;
    }

    private static int compareForClaim(ActualTransaction a, ActualTransaction b) {
        int byCreated = Long.compare(a.createdAt(), b.createdAt());
        return byCreated != 0 ? byCreated : a.id().compareTo(b.id());
    }

    private static String deterministicTransactionId(String eventId) {
        return UUID.nameUUIDFromBytes(
            ("canonical-loan-tx:" + eventId).getBytes(StandardCharsets.UTF_8)
        ).toString();
    }

    private static List<ExpectedArtifact> readExpectedArtifacts(SqliteDatabase database, String userUid) {
        String sql =
            "SELECT j.event_id, j.loan_id, j.event_type, j.amount_cents, j.account_id, " +
            "       j.transaction_id, j.occurred_at, " +
            "       s.loan_type, s.counterparty_name, s.default_account_id " +
            "FROM loan_journal_v1 j " +
            "JOIN loan_snapshots_v1 s ON s.owner_id = j.owner_id AND s.loan_id = j.loan_id " +
            "WHERE j.owner_id = ? " +
            "AND j.event_type IN ('CREATION', 'TOPUP', 'ADJUSTMENT', 'PAYMENT') " +
            // Un evento cubierto por REVERSAL no declara artefacto financiero:
            // la reversión elimina su efecto (misma regla que el backfill).
            "AND NOT EXISTS (SELECT 1 FROM loan_journal_v1 r " +
            "WHERE r.owner_id = j.owner_id AND r.event_type = 'REVERSAL' " +
            "AND r.payload_target_event_id = j.event_id) " +
            "ORDER BY j.loan_id, j.occurred_at, j.recorded_at, j.event_id";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, userUid);
            List<ExpectedArtifact> out = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String eventType = result.getString("event_type");
                    Long amount = nullableLong(result);
                    if (amount == null || amount == 0L) {
                        continue;
                    }
                    String loanType = result.getString("loan_type");
                    if (loanType == null || loanType.isBlank()) {
                        continue;
                    }
                    String accountId = result.getString("account_id");
                    if (accountId == null || accountId.isBlank()) {
                        accountId = result.getString("default_account_id");
                    }
                    if (accountId == null || accountId.isBlank()) {
                        continue;
                    }
                    boolean isLent = "LENT".equalsIgnoreCase(loanType);
                    String counterparty = result.getString("counterparty_name");
                    out.add(new ExpectedArtifact(
                        result.getString("event_id"),
                        result.getString("loan_id"),
                        eventType,
                        accountId,
                        Math.abs(amount),
                        result.getString("transaction_id"),
                        result.getLong("occurred_at"),
                        kindFor(eventType, amount > 0, isLent),
                        noteFor(eventType, isLent, counterparty)
                    ));
                }
            }
            return out;
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }

    private static List<ActualTransaction> readLoanTransactions(SqliteDatabase database, String userUid) {
        StringBuilder sql = new StringBuilder(
            "SELECT id, account_id, kind, amount_cents, occurred_at_epoch_sec, created_at_epoch_sec " +
            "FROM transactions WHERE user_uid = ? AND kind IN (");
        String[] kinds = LOAN_KINDS.toArray(new String[0]);
        for (int i = 0; i < kinds.length; i++) {
            sql.append(i == 0 ? "?" : ", ?");
        }
        sql.append(") ORDER BY created_at_epoch_sec, id");
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            statement.setString(1, userUid);
            for (int i = 0; i < kinds.length; i++) {
                statement.setString(2 + i, kinds[i]);
            }
            List<ActualTransaction> out = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    out.add(new ActualTransaction(
                        result.getString("id"),
                        result.getString("account_id"),
                        result.getString("kind"),
                        result.getLong("amount_cents"),
                        result.getLong("occurred_at_epoch_sec"),
                        result.getLong("created_at_epoch_sec")
                    ));
                }
            }
            return out;
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }

    /**
     * Transacciones protegidas contra borrado: referenciadas por el journal
     * ({@code transaction_id}) o por el transporte ({@code loan_payments} y
     * {@code loan_movements.linked_transaction_id}). Las segundas son
     * candidatas de adopción canónica pendientes de replay/reconciliación.
     */
    private static Set<String> readProtectedTransactionIds(SqliteDatabase database, String userUid) {
        Set<String> out = new LinkedHashSet<>();
        collectIds(database, out,
            "SELECT transaction_id FROM loan_journal_v1 WHERE owner_id = ? AND transaction_id IS NOT NULL",
            userUid);
        try {
            collectIds(database, out,
                "SELECT linked_transaction_id FROM loan_payments WHERE user_uid = ? AND linked_transaction_id IS NOT NULL",
                userUid);
        } catch (LoanPersistenceException ex) {
            // loan_payments puede no existir en esquemas antiguos; se ignora.
        }
        try {
            collectIds(database, out,
                "SELECT linked_transaction_id FROM loan_movements WHERE user_uid = ? AND linked_transaction_id IS NOT NULL",
                userUid);
        } catch (LoanPersistenceException ex) {
            // loan_movements puede no existir en esquemas antiguos; se ignora.
        }
        return out;
    }

    private static void collectIds(SqliteDatabase database, Set<String> out, String sql, String userUid) {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, userUid);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String id = result.getString(1);
                    if (id != null && !id.isBlank()) {
                        out.add(id);
                    }
                }
            }
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }

    private static String kindFor(String eventType, boolean positiveAmount, boolean isLent) {
        return switch (eventType) {
            case "CREATION" -> isLent
                ? TransactionKind.LOAN_LENT_OUT.name()
                : TransactionKind.LOAN_BORROWED_IN.name();
            case "TOPUP" -> isLent
                ? TransactionKind.LOAN_LENT_TOPUP.name()
                : TransactionKind.LOAN_BORROWED_TOPUP.name();
            case "ADJUSTMENT" -> {
                yield isLent
                    ? (positiveAmount
                        ? TransactionKind.LOAN_LENT_CORRECTION_OUT.name()
                        : TransactionKind.LOAN_LENT_CORRECTION_IN.name())
                    : (positiveAmount
                        ? TransactionKind.LOAN_BORROWED_CORRECTION_IN.name()
                        : TransactionKind.LOAN_BORROWED_CORRECTION_OUT.name());
            }
            case "PAYMENT" -> isLent
                ? TransactionKind.LOAN_REPAYMENT_PRINCIPAL_IN.name()
                : TransactionKind.LOAN_REPAYMENT_PRINCIPAL_OUT.name();
            default -> throw new IllegalStateException("evento financiero desconocido: " + eventType);
        };
    }

    private static String noteFor(String eventType, boolean isLent, String counterparty) {
        String name = counterparty != null ? counterparty : "";
        return switch (eventType) {
            case "CREATION" -> isLent
                ? "Préstamo otorgado a: " + name
                : "Dinero recibido de: " + name;
            case "TOPUP" -> isLent
                ? "Aumento de préstamo otorgado a: " + name
                : "Aumento de deuda con: " + name;
            case "ADJUSTMENT" -> isLent
                ? "Corrección de préstamo otorgado a: " + name
                : "Corrección de deuda con: " + name;
            case "PAYMENT" -> isLent
                ? "Pago recibido de: " + name
                : "Pago realizado a: " + name;
            default -> null;
        };
    }

    private static String ensureLoanCategory(SqliteDatabase database, String userUid) throws SQLException {
        String categoryId = "system-loan-" + userUid;
        if (!categoryExists(database, userUid, categoryId)) {
            insertCategory(database, userUid, categoryId, "Préstamos");
        }
        return categoryId;
    }

    private static String ensureRepaymentCategory(SqliteDatabase database, String userUid) throws SQLException {
        String categoryId = "sys_repayment_" + userUid;
        if (!categoryExists(database, userUid, categoryId)) {
            insertCategory(database, userUid, categoryId, "Devoluciones");
        }
        return categoryId;
    }

    private static void insertCategory(SqliteDatabase database, String userUid, String id, String name) throws SQLException {
        long now = System.currentTimeMillis() / 1000L;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT INTO categories (id, user_uid, name, parent_id, kind, created_at_epoch_sec, updated_at_epoch_sec) " +
                 "VALUES (?, ?, ?, NULL, NULL, ?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, userUid);
            statement.setString(3, name);
            statement.setLong(4, now);
            statement.setLong(5, now);
            statement.executeUpdate();
        }
    }

    private static boolean categoryExists(SqliteDatabase database, String userUid, String categoryId) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT 1 FROM categories WHERE user_uid = ? AND id = ? LIMIT 1")) {
            statement.setString(1, userUid);
            statement.setString(2, categoryId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static Long nullableLong(ResultSet result) throws SQLException {
        Object value = result.getObject("amount_cents");
        return value == null ? null : ((Number) value).longValue();
    }

    private record ExpectedArtifact(
        String eventId,
        String loanId,
        String eventType,
        String accountId,
        long amountCents,
        String transactionId,
        long occurredAt,
        String kind,
        String note
    ) {}

    private record ActualTransaction(
        String id,
        String accountId,
        String kind,
        long amountCents,
        long occurredAt,
        long createdAt
    ) {}
}
