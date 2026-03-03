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

public final class GoalRepository {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CLOSED = "CLOSED";

    private final SqliteDatabase db;

    public GoalRepository(SqliteDatabase db) {
        this.db = db;
    }

    public record Goal(
        String id,
        String userUid,
        String name,
        String currency,
        long targetCents,
        long targetDateEpochSec,
        String accountId,
        String status,
        long createdAtEpochSec,
        long updatedAtEpochSec
    ) {
    }

    public Goal getByIdOrNull(String userUid, String goalId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(goalId, "goalId");

        String sql =
            "SELECT id, user_uid, name, currency, target_cents, target_date_epoch_sec, account_id, status, created_at_epoch_sec, updated_at_epoch_sec " +
            "FROM goals WHERE user_uid = ? AND id = ?";

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userUid);
            ps.setString(2, goalId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Goal(
                    rs.getString("id"),
                    rs.getString("user_uid"),
                    rs.getString("name"),
                    rs.getString("currency"),
                    rs.getLong("target_cents"),
                    rs.getLong("target_date_epoch_sec"),
                    rs.getString("account_id"),
                    rs.getString("status"),
                    rs.getLong("created_at_epoch_sec"),
                    rs.getLong("updated_at_epoch_sec")
                );
            }
        }
    }

    public List<Goal> listByUser(String userUid) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");

        String sql =
            "SELECT id, user_uid, name, currency, target_cents, target_date_epoch_sec, account_id, status, created_at_epoch_sec, updated_at_epoch_sec " +
            "FROM goals WHERE user_uid = ? ORDER BY updated_at_epoch_sec DESC";

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userUid);
            List<Goal> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Goal(
                        rs.getString("id"),
                        rs.getString("user_uid"),
                        rs.getString("name"),
                        rs.getString("currency"),
                        rs.getLong("target_cents"),
                        rs.getLong("target_date_epoch_sec"),
                        rs.getString("account_id"),
                        rs.getString("status"),
                        rs.getLong("created_at_epoch_sec"),
                        rs.getLong("updated_at_epoch_sec")
                    ));
                }
            }
            return out;
        }
    }

    public Goal create(
        String userUid,
        String name,
        String currency,
        long targetCents,
        long targetDateEpochSec,
        String accountId
    ) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(accountId, "accountId");

        if (targetCents < 0) {
            throw new IllegalArgumentException("targetCents");
        }

        String id = UUID.randomUUID().toString();
        long now = Instant.now().getEpochSecond();
        String n = name.trim();
        if (n.isBlank()) {
            throw new IllegalArgumentException("name");
        }

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "INSERT INTO goals (id, user_uid, name, currency, target_cents, target_date_epoch_sec, account_id, status, created_at_epoch_sec, updated_at_epoch_sec) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        )) {
            ps.setString(1, id);
            ps.setString(2, userUid);
            ps.setString(3, n);
            ps.setString(4, currency);
            ps.setLong(5, targetCents);
            ps.setLong(6, targetDateEpochSec);
            ps.setString(7, accountId);
            ps.setString(8, STATUS_OPEN);
            ps.setLong(9, now);
            ps.setLong(10, now);
            ps.executeUpdate();
        }

        return new Goal(id, userUid, n, currency, targetCents, targetDateEpochSec, accountId, STATUS_OPEN, now, now);
    }

    public void update(
        String userUid,
        String goalId,
        String name,
        String currency,
        long targetCents,
        long targetDateEpochSec,
        String accountId,
        String status
    ) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(goalId, "goalId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(status, "status");

        if (!STATUS_OPEN.equals(status) && !STATUS_CLOSED.equals(status)) {
            throw new IllegalArgumentException("status");
        }
        if (targetCents < 0) {
            throw new IllegalArgumentException("targetCents");
        }

        String n = name.trim();
        if (n.isBlank()) {
            throw new IllegalArgumentException("name");
        }

        long now = Instant.now().getEpochSecond();
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE goals SET name = ?, currency = ?, target_cents = ?, target_date_epoch_sec = ?, account_id = ?, status = ?, updated_at_epoch_sec = ? " +
            "WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, n);
            ps.setString(2, currency);
            ps.setLong(3, targetCents);
            ps.setLong(4, targetDateEpochSec);
            ps.setString(5, accountId);
            ps.setString(6, status);
            ps.setLong(7, now);
            ps.setString(8, userUid);
            ps.setString(9, goalId);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                throw new IllegalArgumentException("goal");
            }
        }
    }

    public void delete(String userUid, String goalId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(goalId, "goalId");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "DELETE FROM goals WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, goalId);
            ps.executeUpdate();
        }
    }

    public void upsertFromRemote(String userUid, Goal remote) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(remote, "remote");

        Goal local = getByIdOrNull(userUid, remote.id());
        if (local == null) {
            try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO goals (id, user_uid, name, currency, target_cents, target_date_epoch_sec, account_id, status, created_at_epoch_sec, updated_at_epoch_sec) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            )) {
                ps.setString(1, remote.id());
                ps.setString(2, userUid);
                ps.setString(3, remote.name());
                ps.setString(4, remote.currency());
                ps.setLong(5, remote.targetCents());
                ps.setLong(6, remote.targetDateEpochSec());
                ps.setString(7, remote.accountId());
                ps.setString(8, remote.status());
                ps.setLong(9, remote.createdAtEpochSec());
                ps.setLong(10, remote.updatedAtEpochSec());
                ps.executeUpdate();
            }
            return;
        }

        if (remote.updatedAtEpochSec() <= local.updatedAtEpochSec()) {
            return;
        }

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE goals SET name = ?, currency = ?, target_cents = ?, target_date_epoch_sec = ?, account_id = ?, status = ?, created_at_epoch_sec = ?, updated_at_epoch_sec = ? " +
            "WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, remote.name());
            ps.setString(2, remote.currency());
            ps.setLong(3, remote.targetCents());
            ps.setLong(4, remote.targetDateEpochSec());
            ps.setString(5, remote.accountId());
            ps.setString(6, remote.status());
            ps.setLong(7, remote.createdAtEpochSec());
            ps.setLong(8, remote.updatedAtEpochSec());
            ps.setString(9, userUid);
            ps.setString(10, remote.id());
            ps.executeUpdate();
        }
    }
}
