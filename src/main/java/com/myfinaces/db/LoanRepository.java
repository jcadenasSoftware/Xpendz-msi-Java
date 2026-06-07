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

public final class LoanRepository {

    public static final String TYPE_LENT = "LENT";
    public static final String TYPE_BORROWED = "BORROWED";

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CLOSED = "CLOSED";

    private final SqliteDatabase db;

    public LoanRepository(SqliteDatabase db) {
        this.db = db;
    }

    public record Loan(
        String id,
        String userUid,
        String type,
        String counterpartyName,
        String accountId,
        long principalCents,
        String currency,
        String status,
        String notes,
        long occurredAtEpochSec,
        long createdAtEpochSec,
        long updatedAtEpochSec,
        String updatedBy
    ) {
    }

    public String create(
        String userUid,
        String type,
        String counterpartyName,
        String accountId,
        long principalCents,
        String currency,
        long occurredAtEpochSec,
        String notes
    ) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(counterpartyName, "counterpartyName");
        Objects.requireNonNull(currency, "currency");

        if (!TYPE_LENT.equals(type) && !TYPE_BORROWED.equals(type)) {
            throw new IllegalArgumentException("type");
        }
        if (principalCents < 0) {
            throw new IllegalArgumentException("principalCents");
        }

        String id = UUID.randomUUID().toString();
        long now = Instant.now().getEpochSecond();

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "INSERT INTO loans (id, user_uid, type, counterparty_name, account_id, principal_cents, currency, status, notes, occurred_at_epoch_sec, created_at_epoch_sec, updated_at_epoch_sec, updated_by, pending_sync) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)"
        )) {
            ps.setString(1, id);
            ps.setString(2, userUid);
            ps.setString(3, type);
            ps.setString(4, counterpartyName);
            if (accountId == null || accountId.isBlank()) {
                ps.setObject(5, null);
            } else {
                ps.setString(5, accountId);
            }
            ps.setLong(6, principalCents);
            ps.setString(7, currency);
            ps.setString(8, STATUS_OPEN);
            ps.setString(9, notes);
            ps.setLong(10, occurredAtEpochSec <= 0L ? now : occurredAtEpochSec);
            ps.setLong(11, now);
            ps.setLong(12, now);
            ps.setObject(13, null);
            ps.executeUpdate();
        }

        return id;
    }

    public void update(String userUid, String loanId, String type, String counterpartyName, long principalCents, String currency, String status, String notes) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(loanId, "loanId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(counterpartyName, "counterpartyName");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(status, "status");

        if (!TYPE_LENT.equals(type) && !TYPE_BORROWED.equals(type)) {
            throw new IllegalArgumentException("type");
        }
        if (!STATUS_OPEN.equals(status) && !STATUS_CLOSED.equals(status)) {
            throw new IllegalArgumentException("status");
        }
        if (principalCents < 0) {
            throw new IllegalArgumentException("principalCents");
        }

        long now = Instant.now().getEpochSecond();
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE loans SET type = ?, counterparty_name = ?, principal_cents = ?, currency = ?, status = ?, notes = ?, updated_at_epoch_sec = ?, pending_sync = 1 WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, type);
            ps.setString(2, counterpartyName);
            ps.setLong(3, principalCents);
            ps.setString(4, currency);
            ps.setString(5, status);
            ps.setString(6, notes);
            ps.setLong(7, now);
            ps.setString(8, userUid);
            ps.setString(9, loanId);
            ps.executeUpdate();
        }
    }

    public List<Loan> listAllByUser(String userUid) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");

        String sql =
            "SELECT id, user_uid, type, counterparty_name, account_id, principal_cents, currency, status, notes, occurred_at_epoch_sec, created_at_epoch_sec, updated_at_epoch_sec, updated_by " +
            "FROM loans WHERE user_uid = ? ORDER BY updated_at_epoch_sec DESC, created_at_epoch_sec DESC";

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userUid);

            List<Loan> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Loan(
                        rs.getString("id"),
                        rs.getString("user_uid"),
                        rs.getString("type"),
                        rs.getString("counterparty_name"),
                        rs.getString("account_id"),
                        rs.getLong("principal_cents"),
                        rs.getString("currency"),
                        rs.getString("status"),
                        rs.getString("notes"),
                        rs.getLong("occurred_at_epoch_sec"),
                        rs.getLong("created_at_epoch_sec"),
                        rs.getLong("updated_at_epoch_sec"),
                        rs.getString("updated_by")
                    ));
                }
            }
            return out;
        }
    }

    public Loan getByIdOrNull(String userUid, String loanId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(loanId, "loanId");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, user_uid, type, counterparty_name, account_id, principal_cents, currency, status, notes, occurred_at_epoch_sec, created_at_epoch_sec, updated_at_epoch_sec, updated_by FROM loans WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, loanId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Loan(
                    rs.getString("id"),
                    rs.getString("user_uid"),
                    rs.getString("type"),
                    rs.getString("counterparty_name"),
                    rs.getString("account_id"),
                    rs.getLong("principal_cents"),
                    rs.getString("currency"),
                    rs.getString("status"),
                    rs.getString("notes"),
                    rs.getLong("occurred_at_epoch_sec"),
                    rs.getLong("created_at_epoch_sec"),
                    rs.getLong("updated_at_epoch_sec"),
                    rs.getString("updated_by")
                );
            }
        }
    }

    public List<Loan> listByType(String userUid, String type, String currency, boolean onlyOpen) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(type, "type");

        boolean hasCurrency = currency != null && !currency.isBlank();

        String sql =
            "SELECT id, user_uid, type, counterparty_name, account_id, principal_cents, currency, status, notes, occurred_at_epoch_sec, created_at_epoch_sec, updated_at_epoch_sec, updated_by " +
            "FROM loans WHERE user_uid = ? AND type = ?" + (hasCurrency ? " AND currency = ?" : "");

        if (onlyOpen) {
            sql += " AND status = 'OPEN'";
        }
        sql += " ORDER BY updated_at_epoch_sec DESC";

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userUid);
            ps.setString(2, type);
            if (hasCurrency) {
                ps.setString(3, currency);
            }

            List<Loan> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Loan(
                        rs.getString("id"),
                        rs.getString("user_uid"),
                        rs.getString("type"),
                        rs.getString("counterparty_name"),
                        rs.getString("account_id"),
                        rs.getLong("principal_cents"),
                        rs.getString("currency"),
                        rs.getString("status"),
                        rs.getString("notes"),
                        rs.getLong("occurred_at_epoch_sec"),
                        rs.getLong("created_at_epoch_sec"),
                        rs.getLong("updated_at_epoch_sec"),
                        rs.getString("updated_by")
                    ));
                }
            }
            return out;
        }
    }

    public void upsertFromRemote(String userUid, Loan remote) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(remote, "remote");

        Loan local = getByIdOrNull(userUid, remote.id());
        if (local == null) {
            try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO loans (id, user_uid, type, counterparty_name, account_id, principal_cents, currency, status, notes, occurred_at_epoch_sec, created_at_epoch_sec, updated_at_epoch_sec, updated_by, pending_sync) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)"
            )) {
                ps.setString(1, remote.id());
                ps.setString(2, userUid);
                ps.setString(3, remote.type());
                ps.setString(4, remote.counterpartyName());
                if (remote.accountId() == null || remote.accountId().isBlank()) {
                    ps.setObject(5, null);
                } else {
                    ps.setString(5, remote.accountId());
                }
                ps.setLong(6, remote.principalCents());
                ps.setString(7, remote.currency());
                ps.setString(8, remote.status());
                ps.setString(9, remote.notes());
                ps.setLong(10, remote.occurredAtEpochSec());
                ps.setLong(11, remote.createdAtEpochSec());
                ps.setLong(12, remote.updatedAtEpochSec());
                if (remote.updatedBy() == null || remote.updatedBy().isBlank()) {
                    ps.setObject(13, null);
                } else {
                    ps.setString(13, remote.updatedBy());
                }
                ps.executeUpdate();
            }
            return;
        }

        if (remote.updatedAtEpochSec() <= local.updatedAtEpochSec()) {
            return;
        }

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE loans SET type = ?, counterparty_name = ?, account_id = ?, principal_cents = ?, currency = ?, status = ?, notes = ?, occurred_at_epoch_sec = ?, created_at_epoch_sec = ?, updated_at_epoch_sec = ?, updated_by = ?, pending_sync = 0 " +
            "WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, remote.type());
            ps.setString(2, remote.counterpartyName());
            if (remote.accountId() == null || remote.accountId().isBlank()) {
                ps.setObject(3, null);
            } else {
                ps.setString(3, remote.accountId());
            }
            ps.setLong(4, remote.principalCents());
            ps.setString(5, remote.currency());
            ps.setString(6, remote.status());
            ps.setString(7, remote.notes());
            ps.setLong(8, remote.occurredAtEpochSec());
            ps.setLong(9, remote.createdAtEpochSec());
            ps.setLong(10, remote.updatedAtEpochSec());
            if (remote.updatedBy() == null || remote.updatedBy().isBlank()) {
                ps.setObject(11, null);
            } else {
                ps.setString(11, remote.updatedBy());
            }
            ps.setString(12, userUid);
            ps.setString(13, remote.id());
            ps.executeUpdate();
        }
    }

    public List<Loan> listPendingForSync(String userUid) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");

        String sql =
            "SELECT id, user_uid, type, counterparty_name, account_id, principal_cents, currency, status, notes, occurred_at_epoch_sec, created_at_epoch_sec, updated_at_epoch_sec, updated_by " +
            "FROM loans WHERE user_uid = ? AND pending_sync = 1 ORDER BY updated_at_epoch_sec ASC";

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userUid);
            List<Loan> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Loan(
                        rs.getString("id"),
                        rs.getString("user_uid"),
                        rs.getString("type"),
                        rs.getString("counterparty_name"),
                        rs.getString("account_id"),
                        rs.getLong("principal_cents"),
                        rs.getString("currency"),
                        rs.getString("status"),
                        rs.getString("notes"),
                        rs.getLong("occurred_at_epoch_sec"),
                        rs.getLong("created_at_epoch_sec"),
                        rs.getLong("updated_at_epoch_sec"),
                        rs.getString("updated_by")
                    ));
                }
            }
            return out;
        }
    }

    public void markSynced(String userUid, String loanId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(loanId, "loanId");
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE loans SET pending_sync = 0 WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, loanId);
            ps.executeUpdate();
        }
    }

    public void delete(String userUid, String loanId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(loanId, "loanId");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "DELETE FROM loans WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, loanId);
            ps.executeUpdate();
        }
    }

    public Loan findActiveByCounterpartyAndType(String userUid, String counterpartyName, String type) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(counterpartyName, "counterpartyName");
        Objects.requireNonNull(type, "type");

        String sql = "SELECT * FROM loans WHERE user_uid = ? AND counterparty_name = ? AND type = ? AND status = ? LIMIT 1";
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userUid);
            ps.setString(2, counterpartyName);
            ps.setString(3, type);
            ps.setString(4, STATUS_OPEN);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Loan(
                        rs.getString("id"),
                        rs.getString("user_uid"),
                        rs.getString("type"),
                        rs.getString("counterparty_name"),
                        rs.getString("account_id"),
                        rs.getLong("principal_cents"),
                        rs.getString("currency"),
                        rs.getString("status"),
                        rs.getString("notes"),
                        rs.getLong("occurred_at_epoch_sec"),
                        rs.getLong("created_at_epoch_sec"),
                        rs.getLong("updated_at_epoch_sec"),
                        rs.getString("updated_by")
                    );
                }
                return null;
            }
        }
    }

    public void archive(String userUid, String loanId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(loanId, "loanId");

        long now = Instant.now().getEpochSecond();
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE loans SET status = ?, updated_at_epoch_sec = ? WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, STATUS_CLOSED);
            ps.setLong(2, now);
            ps.setString(3, userUid);
            ps.setString(4, loanId);
            ps.executeUpdate();
        }
    }
}
