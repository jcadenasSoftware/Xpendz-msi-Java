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

public final class AccountRepository {

    private final SqliteDatabase db;

    public AccountRepository(SqliteDatabase db) {
        this.db = db;
    }

    public record Account(
        String id,
        String userUid,
        String name,
        String type,
        String currency,
        String color,
        long createdAtEpochSec,
        long updatedAtEpochSec
    ) {
    }

    public static String normalizeType(String type) {
        String t = type == null ? "" : type.trim().toUpperCase(java.util.Locale.ROOT);
        if (t.isBlank()) {
            return "BANK";
        }
        if (
            "BANK".equals(t)
                || "CASH".equals(t)
                || "SAVINGS".equals(t)
                || "VIRTUAL_WALLET".equals(t)
                || "DIGITAL_ACCOUNT".equals(t)
                || "CREDIT".equals(t)
        ) {
            return t;
        }
        if ("CREDIT_CARD".equals(t)) {
            return "CREDIT";
        }
        if ("INVESTMENT".equals(t)) {
            return "SAVINGS";
        }
        if ("OTHER".equals(t)) {
            return "BANK";
        }
        return "BANK";
    }

    public Account create(String userUid, String name, String type, String currency) throws SQLException {
        return create(userUid, name, type, currency, null);
    }

    public Account create(String userUid, String name, String type, String currency, String color) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(name, "name");

        String id = UUID.randomUUID().toString();
        long now = Instant.now().getEpochSecond();
        String t = normalizeType(type);
        String cur = (currency == null || currency.isBlank()) ? "COP" : currency;

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "INSERT INTO accounts (id, user_uid, name, type, currency, color, created_at_epoch_sec, updated_at_epoch_sec) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        )) {
            ps.setString(1, id);
            ps.setString(2, userUid);
            ps.setString(3, name);
            ps.setString(4, t);
            ps.setString(5, cur);
            if (color == null || color.isBlank()) {
                ps.setObject(6, null);
            } else {
                ps.setString(6, color);
            }
            ps.setLong(7, now);
            ps.setLong(8, now);
            ps.executeUpdate();
        }

        return new Account(id, userUid, name, t, cur, color, now, now);
    }

    public List<Account> list(String userUid) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, user_uid, name, type, currency, color, created_at_epoch_sec, updated_at_epoch_sec " +
            "FROM accounts WHERE user_uid = ? ORDER BY name"
        )) {
            ps.setString(1, userUid);
            List<Account> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Account(
                        rs.getString("id"),
                        rs.getString("user_uid"),
                        rs.getString("name"),
                        rs.getString("type"),
                        rs.getString("currency"),
                        rs.getString("color"),
                        rs.getLong("created_at_epoch_sec"),
                        rs.getLong("updated_at_epoch_sec")
                    ));
                }
            }
            return out;
        }
    }

    public Account getById(String userUid, String accountId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(accountId, "accountId");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, user_uid, name, type, currency, color, created_at_epoch_sec, updated_at_epoch_sec " +
            "FROM accounts WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Account(
                    rs.getString("id"),
                    rs.getString("user_uid"),
                    rs.getString("name"),
                    rs.getString("type"),
                    rs.getString("currency"),
                    rs.getString("color"),
                    rs.getLong("created_at_epoch_sec"),
                    rs.getLong("updated_at_epoch_sec")
                );
            }
        }
    }

    public Account updateNameAndType(String userUid, String accountId, String newName, String newType) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(newName, "newName");

        String n = newName.trim();
        if (n.isBlank()) {
            throw new IllegalArgumentException("name");
        }

        String t = normalizeType(newType);

        long now = Instant.now().getEpochSecond();
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE accounts SET name = ?, type = ?, updated_at_epoch_sec = ? WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, n);
            ps.setString(2, t);
            ps.setLong(3, now);
            ps.setString(4, userUid);
            ps.setString(5, accountId);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                throw new IllegalArgumentException("account");
            }
        }

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, user_uid, name, type, currency, created_at_epoch_sec, updated_at_epoch_sec " +
            "FROM accounts WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("account");
                }
                return new Account(
                    rs.getString("id"),
                    rs.getString("user_uid"),
                    rs.getString("name"),
                    rs.getString("type"),
                    rs.getString("currency"),
                    rs.getString("color"),
                    rs.getLong("created_at_epoch_sec"),
                    rs.getLong("updated_at_epoch_sec")
                );
            }
        }
    }

    public void upsertFromRemote(String userUid, Account remote) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(remote, "remote");

        Account normalized = new Account(
            remote.id(),
            remote.userUid(),
            remote.name(),
            normalizeType(remote.type()),
            remote.currency(),
            remote.color(),
            remote.createdAtEpochSec(),
            remote.updatedAtEpochSec()
        );

        Account local = getById(userUid, normalized.id());
        if (local == null) {
            try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO accounts (id, user_uid, name, type, currency, created_at_epoch_sec, updated_at_epoch_sec) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)"
            )) {
                ps.setString(1, normalized.id());
                ps.setString(2, userUid);
                ps.setString(3, normalized.name());
                ps.setString(4, normalized.type());
                ps.setString(5, normalized.currency());
                ps.setLong(6, normalized.createdAtEpochSec());
                ps.setLong(7, normalized.updatedAtEpochSec());
                ps.executeUpdate();
            }
            return;
        }

        if (normalized.updatedAtEpochSec() <= local.updatedAtEpochSec()) {
            return;
        }

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE accounts SET name = ?, type = ?, currency = ?, created_at_epoch_sec = ?, updated_at_epoch_sec = ? " +
            "WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, normalized.name());
            ps.setString(2, normalized.type());
            ps.setString(3, normalized.currency());
            ps.setLong(4, normalized.createdAtEpochSec());
            ps.setLong(5, normalized.updatedAtEpochSec());
            ps.setString(6, userUid);
            ps.setString(7, normalized.id());
            ps.executeUpdate();
        }
    }

    public Account updateName(String userUid, String accountId, String newName) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(newName, "newName");

        String n = newName.trim();
        if (n.isBlank()) {
            throw new IllegalArgumentException("name");
        }

        long now = Instant.now().getEpochSecond();
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE accounts SET name = ?, updated_at_epoch_sec = ? WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, n);
            ps.setLong(2, now);
            ps.setString(3, userUid);
            ps.setString(4, accountId);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                throw new IllegalArgumentException("account");
            }
        }

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, user_uid, name, type, currency, color, created_at_epoch_sec, updated_at_epoch_sec " +
            "FROM accounts WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("account");
                }
                return new Account(
                    rs.getString("id"),
                    rs.getString("user_uid"),
                    rs.getString("name"),
                    rs.getString("type"),
                    rs.getString("currency"),
                    rs.getString("color"),
                    rs.getLong("created_at_epoch_sec"),
                    rs.getLong("updated_at_epoch_sec")
                );
            }
        }
    }

    public boolean hasMovements(String userUid, String accountId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(accountId, "accountId");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT (" +
            "  EXISTS(SELECT 1 FROM transactions WHERE user_uid = ? AND account_id = ?)" +
            "  OR EXISTS(SELECT 1 FROM transfers WHERE user_uid = ? AND (from_account_id = ? OR to_account_id = ?))" +
            ") AS has"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, accountId);
            ps.setString(3, userUid);
            ps.setString(4, accountId);
            ps.setString(5, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                return rs.getInt("has") != 0;
            }
        }
    }

    public void delete(String userUid, String accountId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(accountId, "accountId");

        if (hasMovements(userUid, accountId)) {
            throw new IllegalStateException("account_has_movements");
        }

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "DELETE FROM accounts WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, accountId);
            ps.executeUpdate();
        }
    }

    public long computeBalanceCents(String userUid, String accountId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(accountId, "accountId");

        String sql =
            "SELECT (" +
            "  COALESCE((SELECT SUM(CASE " +
            "    WHEN kind = 'INCOME' THEN amount_cents " +
            "    WHEN kind = 'LOAN_BORROWED_IN' THEN amount_cents " +
            "    WHEN kind = 'LOAN_REPAYMENT_PRINCIPAL_IN' THEN amount_cents " +
            "    WHEN kind = 'EXPENSE' THEN -amount_cents " +
            "    WHEN kind = 'LOAN_LENT_OUT' THEN -amount_cents " +
            "    WHEN kind = 'LOAN_REPAYMENT_PRINCIPAL_OUT' THEN -amount_cents " +
            "    ELSE 0 END)" +
            "          FROM transactions WHERE user_uid = ? AND account_id = ?), 0)" +
            "  + COALESCE((SELECT SUM(amount_cents) FROM transfers WHERE user_uid = ? AND to_account_id = ?), 0)" +
            "  - COALESCE((SELECT SUM(amount_cents) FROM transfers WHERE user_uid = ? AND from_account_id = ?), 0)" +
            ") AS balance";

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userUid);
            ps.setString(2, accountId);
            ps.setString(3, userUid);
            ps.setString(4, accountId);
            ps.setString(5, userUid);
            ps.setString(6, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0L;
                }
                return rs.getLong("balance");
            }
        }
    }
}
