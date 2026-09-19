package com.myfinaces.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.UUID;

public final class LoanPaymentRepository {

    private final SqliteDatabase db;

    public LoanPaymentRepository(SqliteDatabase db) {
        this.db = db;
    }

    public record LoanPayment(
        String id,
        String loanId,
        String userUid,
        String accountId,
        long principalCents,
        long occurredAtEpochSec,
        String linkedTransactionId,
        String note,
        long createdAtEpochSec,
        long updatedAtEpochSec,
        String updatedBy
    ) {
    }

    public LoanPayment getByIdOrNull(String userUid, String paymentId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(paymentId, "paymentId");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, loan_id, user_uid, account_id, principal_cents, occurred_at_epoch_sec, linked_transaction_id, note, created_at_epoch_sec, updated_at_epoch_sec, updated_by " +
            "FROM loan_payments WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, paymentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new LoanPayment(
                    rs.getString("id"),
                    rs.getString("loan_id"),
                    rs.getString("user_uid"),
                    rs.getString("account_id"),
                    rs.getLong("principal_cents"),
                    rs.getLong("occurred_at_epoch_sec"),
                    rs.getString("linked_transaction_id"),
                    rs.getString("note"),
                    rs.getLong("created_at_epoch_sec"),
                    rs.getLong("updated_at_epoch_sec"),
                    rs.getString("updated_by")
                );
            }
        }
    }

    public String create(
        String userUid,
        String loanId,
        String accountId,
        long principalCents,
        long occurredAtEpochSec,
        String linkedTransactionId,
        String note
    ) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(loanId, "loanId");
        Objects.requireNonNull(accountId, "accountId");

        if (principalCents < 0) {
            throw new IllegalArgumentException("principalCents");
        }

        String id = UUID.randomUUID().toString();
        long now = Instant.now().getEpochSecond();

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "INSERT INTO loan_payments (id, loan_id, user_uid, account_id, principal_cents, occurred_at_epoch_sec, linked_transaction_id, note, created_at_epoch_sec, updated_at_epoch_sec, pending_sync) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)"
        )) {
            ps.setString(1, id);
            ps.setString(2, loanId);
            ps.setString(3, userUid);
            ps.setString(4, accountId);
            ps.setLong(5, principalCents);
            ps.setLong(6, occurredAtEpochSec);
            if (linkedTransactionId == null || linkedTransactionId.isBlank()) {
                ps.setObject(7, null);
            } else {
                ps.setString(7, linkedTransactionId);
            }
            ps.setString(8, note);
            ps.setLong(9, now);
            ps.setLong(10, now);
            ps.executeUpdate();
        }

        System.out.println("[LoanPaymentRepository] create id=" + id
            + " loanId=" + loanId
            + " transactionId=" + linkedTransactionId
            + " amount=" + principalCents);

        return id;
    }

    public void update(
        String userUid,
        String paymentId,
        String loanId,
        String accountId,
        long principalCents,
        long occurredAtEpochSec,
        String note
    ) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(loanId, "loanId");
        Objects.requireNonNull(accountId, "accountId");

        if (principalCents < 0) {
            throw new IllegalArgumentException("principalCents");
        }

        long now = Instant.now().getEpochSecond();
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE loan_payments SET loan_id = ?, account_id = ?, principal_cents = ?, occurred_at_epoch_sec = ?, note = ?, updated_at_epoch_sec = ?, pending_sync = 1 WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, loanId);
            ps.setString(2, accountId);
            ps.setLong(3, principalCents);
            ps.setLong(4, occurredAtEpochSec);
            ps.setString(5, note);
            ps.setLong(6, now);
            ps.setString(7, userUid);
            ps.setString(8, paymentId);
            ps.executeUpdate();
        }
    }

    public void delete(String userUid, String paymentId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(paymentId, "paymentId");

        LoanPayment payment = getByIdOrNull(userUid, paymentId);
        System.out.println("[LoanPaymentRepository] delete requested paymentId=" + paymentId
            + " userUid=" + userUid
            + (payment == null ? " paymentNotFound=true" : " loanId=" + payment.loanId()
                + " amount=" + payment.principalCents()
                + " linkedTransactionId=" + payment.linkedTransactionId()));
        System.out.println("[LoanPaymentRepository] delete stackTrace\n" + stackTrace());

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "DELETE FROM loan_payments WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, paymentId);
            ps.executeUpdate();
        }
    }

    public LoanPayment getByLinkedTransactionId(String userUid, String transactionId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(transactionId, "transactionId");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, loan_id, user_uid, account_id, principal_cents, occurred_at_epoch_sec, linked_transaction_id, note, created_at_epoch_sec, updated_at_epoch_sec, updated_by " +
            "FROM loan_payments WHERE user_uid = ? AND linked_transaction_id = ? LIMIT 1"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, transactionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    public LoanPayment getBySignature(
        String userUid,
        String loanId,
        String accountId,
        long principalCents,
        long occurredAtEpochSec
    ) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(loanId, "loanId");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, loan_id, user_uid, account_id, principal_cents, occurred_at_epoch_sec, linked_transaction_id, note, created_at_epoch_sec, updated_at_epoch_sec, updated_by " +
            "FROM loan_payments WHERE user_uid = ? AND loan_id = ? AND principal_cents = ? AND occurred_at_epoch_sec = ? AND account_id IS ? LIMIT 1"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, loanId);
            ps.setLong(3, principalCents);
            ps.setLong(4, occurredAtEpochSec);
            if (accountId == null || accountId.isBlank()) {
                ps.setObject(5, null);
            } else {
                ps.setString(5, accountId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    public List<LoanPayment> listByLoan(String userUid, String loanId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(loanId, "loanId");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, loan_id, user_uid, account_id, principal_cents, occurred_at_epoch_sec, linked_transaction_id, note, created_at_epoch_sec, updated_at_epoch_sec, updated_by " +
            "FROM loan_payments WHERE user_uid = ? AND loan_id = ? ORDER BY occurred_at_epoch_sec ASC, created_at_epoch_sec ASC"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, loanId);

            List<LoanPayment> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new LoanPayment(
                        rs.getString("id"),
                        rs.getString("loan_id"),
                        rs.getString("user_uid"),
                        rs.getString("account_id"),
                        rs.getLong("principal_cents"),
                        rs.getLong("occurred_at_epoch_sec"),
                        rs.getString("linked_transaction_id"),
                        rs.getString("note"),
                        rs.getLong("created_at_epoch_sec"),
                        rs.getLong("updated_at_epoch_sec"),
                        rs.getString("updated_by")
                    ));
                }
            }
            return out;
        }
    }

    public List<LoanPayment> listAllByUser(String userUid) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, loan_id, user_uid, account_id, principal_cents, occurred_at_epoch_sec, linked_transaction_id, note, created_at_epoch_sec, updated_at_epoch_sec, updated_by " +
            "FROM loan_payments WHERE user_uid = ?"
        )) {
            ps.setString(1, userUid);
            List<LoanPayment> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new LoanPayment(
                        rs.getString("id"),
                        rs.getString("loan_id"),
                        rs.getString("user_uid"),
                        rs.getString("account_id"),
                        rs.getLong("principal_cents"),
                        rs.getLong("occurred_at_epoch_sec"),
                        rs.getString("linked_transaction_id"),
                        rs.getString("note"),
                        rs.getLong("created_at_epoch_sec"),
                        rs.getLong("updated_at_epoch_sec"),
                        rs.getString("updated_by")
                    ));
                }
            }
            return out;
        }
    }

    /**
     * Ids locales pendientes de push. La poda por snapshot remoto debe
     * conservarlos: si el push falló, el doc aún no existe en Firestore pero la
     * fila local sigue siendo válida (Desktop usa REST, sin cola offline).
     */
    public Set<String> listPendingSyncIds(String userUid) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id FROM loan_payments WHERE user_uid = ? AND pending_sync = 1"
        )) {
            ps.setString(1, userUid);
            Set<String> out = new HashSet<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString("id"));
                }
            }
            return out;
        }
    }

    public void upsertFromRemote(String userUid, LoanPayment remote) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(remote, "remote");

        LoanPayment local = getByIdOrNull(userUid, remote.id());
        if (local == null) {
            try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO loan_payments (id, loan_id, user_uid, account_id, principal_cents, occurred_at_epoch_sec, linked_transaction_id, note, created_at_epoch_sec, updated_at_epoch_sec, updated_by, pending_sync) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)"
            )) {
                ps.setString(1, remote.id());
                ps.setString(2, remote.loanId());
                ps.setString(3, userUid);
                ps.setString(4, remote.accountId());
                ps.setLong(5, remote.principalCents());
                ps.setLong(6, remote.occurredAtEpochSec());
                if (remote.linkedTransactionId() == null || remote.linkedTransactionId().isBlank()) {
                    ps.setObject(7, null);
                } else {
                    ps.setString(7, remote.linkedTransactionId());
                }
                ps.setString(8, remote.note());
                ps.setLong(9, remote.createdAtEpochSec());
                ps.setLong(10, remote.updatedAtEpochSec());
                if (remote.updatedBy() == null || remote.updatedBy().isBlank()) {
                    ps.setObject(11, null);
                } else {
                    ps.setString(11, remote.updatedBy());
                }
                ps.executeUpdate();
            }
            logPaymentUpsert(remote, "inserted", null);
            return;
        }

        if (remote.updatedAtEpochSec() <= local.updatedAtEpochSec()) {
            logPaymentUpsert(remote, "staleSkipped", local.updatedAtEpochSec());
            return;
        }

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE loan_payments SET loan_id = ?, account_id = ?, principal_cents = ?, occurred_at_epoch_sec = ?, linked_transaction_id = ?, note = ?, created_at_epoch_sec = ?, updated_at_epoch_sec = ?, updated_by = ?, pending_sync = 0 " +
            "WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, remote.loanId());
            ps.setString(2, remote.accountId());
            ps.setLong(3, remote.principalCents());
            ps.setLong(4, remote.occurredAtEpochSec());
            if (remote.linkedTransactionId() == null || remote.linkedTransactionId().isBlank()) {
                ps.setObject(5, null);
            } else {
                ps.setString(5, remote.linkedTransactionId());
            }
            ps.setString(6, remote.note());
            ps.setLong(7, remote.createdAtEpochSec());
            ps.setLong(8, remote.updatedAtEpochSec());
            if (remote.updatedBy() == null || remote.updatedBy().isBlank()) {
                ps.setObject(9, null);
            } else {
                ps.setString(9, remote.updatedBy());
            }
            ps.setString(10, userUid);
            ps.setString(11, remote.id());
            ps.executeUpdate();
        }
        logPaymentUpsert(remote, "updated", local.updatedAtEpochSec());
    }

    public List<LoanPayment> listPendingForSync(String userUid) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, loan_id, user_uid, account_id, principal_cents, occurred_at_epoch_sec, linked_transaction_id, note, created_at_epoch_sec, updated_at_epoch_sec, updated_by " +
                "FROM loan_payments WHERE user_uid = ? AND pending_sync = 1 ORDER BY occurred_at_epoch_sec ASC, created_at_epoch_sec ASC"
        )) {
            ps.setString(1, userUid);
            List<LoanPayment> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new LoanPayment(
                        rs.getString("id"),
                        rs.getString("loan_id"),
                        rs.getString("user_uid"),
                        rs.getString("account_id"),
                        rs.getLong("principal_cents"),
                        rs.getLong("occurred_at_epoch_sec"),
                        rs.getString("linked_transaction_id"),
                        rs.getString("note"),
                        rs.getLong("created_at_epoch_sec"),
                        rs.getLong("updated_at_epoch_sec"),
                        rs.getString("updated_by")
                    ));
                }
            }
            return out;
        }
    }

    public void markSynced(String userUid, String paymentId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(paymentId, "paymentId");
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE loan_payments SET pending_sync = 0 WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, paymentId);
            ps.executeUpdate();
        }
    }

    public long sumPrincipalPaidCents(String userUid, String loanId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(loanId, "loanId");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT COALESCE(SUM(principal_cents), 0) AS total FROM loan_payments WHERE user_uid = ? AND loan_id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, loanId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0L;
                }
                return rs.getLong("total");
            }
        }
    }

    private static LoanPayment mapRow(ResultSet rs) throws SQLException {
        return new LoanPayment(
            rs.getString("id"),
            rs.getString("loan_id"),
            rs.getString("user_uid"),
            rs.getString("account_id"),
            rs.getLong("principal_cents"),
            rs.getLong("occurred_at_epoch_sec"),
            rs.getString("linked_transaction_id"),
            rs.getString("note"),
            rs.getLong("created_at_epoch_sec"),
            rs.getLong("updated_at_epoch_sec"),
            rs.getString("updated_by")
        );
    }

    private static void logPaymentUpsert(LoanPayment payment, String action, Long localUpdatedAt) {
        System.out.println(
            "[LoanPaymentTrace] PAYMENT_UPSERT"
                + " loanId=" + valueOrDash(payment.loanId())
                + " paymentId=" + valueOrDash(payment.id())
                + " transactionId=" + valueOrDash(payment.linkedTransactionId())
                + " operationId=- eventId=" + valueOrDash(payment.id())
                + " updatedAt=" + payment.updatedAtEpochSec()
                + " updatedBy=" + valueOrDash(payment.updatedBy())
                + " accountId=" + valueOrDash(payment.accountId())
                + " principalCents=" + payment.principalCents()
                + " occurredAt=" + payment.occurredAtEpochSec()
                + " action=" + valueOrDash(action)
                + " localUpdatedAt=" + (localUpdatedAt == null ? "-" : localUpdatedAt)
        );
    }

    private static String valueOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String stackTrace() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        for (int i = 2; i < stack.length; i++) {
            sb.append("  at ").append(stack[i]).append('\n');
        }
        return sb.toString();
    }
}
