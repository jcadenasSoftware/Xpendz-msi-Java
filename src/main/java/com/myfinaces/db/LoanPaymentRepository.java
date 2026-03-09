package com.myfinaces.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
            "INSERT INTO loan_payments (id, loan_id, user_uid, account_id, principal_cents, occurred_at_epoch_sec, linked_transaction_id, note, created_at_epoch_sec, updated_at_epoch_sec) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
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
            "UPDATE loan_payments SET loan_id = ?, account_id = ?, principal_cents = ?, occurred_at_epoch_sec = ?, note = ?, updated_at_epoch_sec = ? WHERE user_uid = ? AND id = ?"
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

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "DELETE FROM loan_payments WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, paymentId);
            ps.executeUpdate();
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

    public void upsertFromRemote(String userUid, LoanPayment remote) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(remote, "remote");

        LoanPayment local = getByIdOrNull(userUid, remote.id());
        if (local == null) {
            try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO loan_payments (id, loan_id, user_uid, account_id, principal_cents, occurred_at_epoch_sec, linked_transaction_id, note, created_at_epoch_sec, updated_at_epoch_sec, updated_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
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
            return;
        }

        if (remote.updatedAtEpochSec() <= local.updatedAtEpochSec()) {
            return;
        }

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE loan_payments SET loan_id = ?, account_id = ?, principal_cents = ?, occurred_at_epoch_sec = ?, linked_transaction_id = ?, note = ?, created_at_epoch_sec = ?, updated_at_epoch_sec = ?, updated_by = ? " +
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
}
