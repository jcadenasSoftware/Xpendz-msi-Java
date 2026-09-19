package myfinances.infrastructure.loan.migration;

import com.myfinaces.auth.AuthSession;
import com.myfinaces.db.LoanMovementRepository;
import com.myfinaces.db.LoanPaymentRepository;
import com.myfinaces.db.SqliteDatabase;
import com.myfinaces.db.TransactionRepository;
import com.myfinaces.sync.FirestoreSyncService;
import myfinances.infrastructure.loan.jdbc.LoanPersistenceException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Garantiza que toda reversión presente en el journal canónico tenga sus
 * artefactos de transporte eliminados, incluso si la reversión ocurrió antes de
 * la corrección de identidad o el borrado remoto falló (offline).
 *
 * Para cada evento REVERSAL apuntando a un PAYMENT se elimina:
 * - la fila/doc {@code loan_payments} (localizada por transactionId/firma,
 *   además del doc remoto indexado por el eventId original);
 * - el movimiento PAYMENT_* vinculado (local y remoto);
 * - la transacción LOAN_REPAYMENT_* vinculada (local y remota).
 *
 * Idempotente: se ejecuta en cada sincronización y solo actúa sobre residuos.
 */
public final class ReversedLoanPaymentReconciler {

    private ReversedLoanPaymentReconciler() {
    }

    private record ReversedPayment(
        String loanId,
        String targetEventId,
        String transactionId,
        String accountId,
        long amountCents,
        long occurredAtEpochSec
    ) {}

    public static int reconcile(
        SqliteDatabase database,
        FirestoreSyncService sync,
        AuthSession session
    ) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(sync, "sync");
        Objects.requireNonNull(session, "session");

        String userUid = session.uid();
        LoanPaymentRepository paymentRepo = new LoanPaymentRepository(database);
        LoanMovementRepository movementRepo = new LoanMovementRepository(database);
        TransactionRepository txRepo = new TransactionRepository(database);

        List<ReversedPayment> reversed = readReversedPayments(database, userUid);
        Map<String, List<LoanMovementRepository.LoanMovement>> remoteMovements = new HashMap<>();
        int cleaned = 0;

        for (ReversedPayment target : reversed) {
            try {
                // 1) loan_payments: fila local + doc remoto por docId real y por eventId.
                LoanPaymentRepository.LoanPayment row = target.transactionId() != null
                    ? paymentRepo.getByLinkedTransactionId(userUid, target.transactionId())
                    : null;
                if (row == null) {
                    row = paymentRepo.getBySignature(userUid, target.loanId(), target.accountId(),
                        target.amountCents(), target.occurredAtEpochSec());
                }
                if (row != null) {
                    paymentRepo.delete(userUid, row.id());
                    try {
                        sync.deleteLoanPayment(session, row.id());
                    } catch (Exception e) {
                        System.out.println("[ReversedPaymentSync] delete remote loanPayment " + row.id() + " failed: " + e.getMessage());
                    }
                }
                try {
                    sync.deleteLoanPayment(session, target.targetEventId());
                } catch (Exception ignored) {
                    // El doc remoto puede no existir bajo el eventId local.
                }

                // 2) Movimiento PAYMENT_* vinculado (local + remoto por docId y por firma).
                LoanMovementRepository.LoanMovement movement = target.transactionId() != null
                    ? movementRepo.getByLinkedTransactionId(userUid, target.transactionId())
                    : null;
                if (movement == null) {
                    movement = movementRepo.getPaymentBySignature(userUid, target.loanId(),
                        target.accountId(), target.amountCents(), target.occurredAtEpochSec());
                }
                if (movement != null) {
                    movementRepo.delete(userUid, movement.id());
                    try {
                        sync.deleteLoanMovement(session, userUid, movement.loanId(), movement.id());
                    } catch (Exception ignored) {
                    }
                }
                List<LoanMovementRepository.LoanMovement> remote = remoteMovements.computeIfAbsent(
                    target.loanId(),
                    loanId -> pullMovementsQuietly(sync, session, userUid, loanId)
                );
                for (LoanMovementRepository.LoanMovement ref : new ArrayList<>(remote)) {
                    if (!matches(target, ref)) {
                        continue;
                    }
                    try {
                        sync.deleteLoanMovement(session, userUid, target.loanId(), ref.id());
                    } catch (Exception ignored) {
                    }
                    remote.remove(ref);
                }

                // 3) Transacción LOAN_REPAYMENT_* vinculada (saldo de cuenta).
                if (target.transactionId() != null) {
                    try {
                        txRepo.deleteFailedLoanTransaction(userUid, target.transactionId());
                    } catch (Exception ignored) {
                    }
                    try {
                        sync.deleteTransaction(session, target.transactionId());
                    } catch (Exception ignored) {
                    }
                }
                cleaned++;
            } catch (Exception e) {
                System.out.println("[ReversedPaymentSync] cleanup failed for " + target.targetEventId() + ": " + e.getMessage());
            }
        }
        if (cleaned > 0) {
            System.out.println("[ReversedPaymentSync] reconciled reversals=" + cleaned + "/" + reversed.size());
        }
        return cleaned;
    }

    private static boolean matches(ReversedPayment target, LoanMovementRepository.LoanMovement ref) {
        if (target.transactionId() != null && target.transactionId().equals(ref.linkedTransactionId())) {
            return true;
        }
        String type = ref.movementType();
        boolean paymentType = "PAYMENT".equals(type) || "PAYMENT_IN".equals(type) || "PAYMENT_OUT".equals(type);
        return paymentType
            && ref.amountCents() == target.amountCents()
            && ref.occurredAtEpochSec() == target.occurredAtEpochSec()
            && Objects.equals(ref.accountId(), target.accountId());
    }

    private static List<LoanMovementRepository.LoanMovement> pullMovementsQuietly(
        FirestoreSyncService sync,
        AuthSession session,
        String userUid,
        String loanId
    ) {
        try {
            return new ArrayList<>(sync.pullLoanMovements(session, userUid, loanId));
        } catch (Exception e) {
            System.out.println("[ReversedPaymentSync] pull movements failed loanId=" + loanId + ": " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private static List<ReversedPayment> readReversedPayments(SqliteDatabase database, String ownerId) {
        String sql =
            "SELECT r.loan_id AS loan_id, r.payload_target_event_id AS target_event_id, " +
            "p.transaction_id AS transaction_id, p.account_id AS account_id, " +
            "p.amount_cents AS amount_cents, p.occurred_at AS occurred_at " +
            "FROM loan_journal_v1 r " +
            "JOIN loan_journal_v1 p ON p.owner_id = r.owner_id AND p.event_id = r.payload_target_event_id " +
            "WHERE r.owner_id = ? AND r.event_type = 'REVERSAL' AND p.event_type = 'PAYMENT'";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId);
            List<ReversedPayment> out = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    out.add(new ReversedPayment(
                        rs.getString("loan_id"),
                        rs.getString("target_event_id"),
                        rs.getString("transaction_id"),
                        rs.getString("account_id"),
                        rs.getLong("amount_cents"),
                        rs.getLong("occurred_at")
                    ));
                }
            }
            return out;
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }
}
