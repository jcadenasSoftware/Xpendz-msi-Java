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

public final class TransferRepository {

    private final SqliteDatabase db;

    public TransferRepository(SqliteDatabase db) {
        this.db = db;
    }

    public record TransferRow(
        String id,
        String userUid,
        String fromAccountId,
        String fromAccountName,
        String toAccountId,
        String toAccountName,
        long amountCents,
        long occurredAtEpochSec,
        String note
    ) {
    }

    public record TransferSyncRow(
        String id,
        String userUid,
        String fromAccountId,
        String toAccountId,
        long amountCents,
        long occurredAtEpochSec,
        String note,
        long createdAtEpochSec,
        long updatedAtEpochSec
    ) {
    }

    public String create(
        String userUid,
        String fromAccountId,
        String toAccountId,
        long amountCents,
        long occurredAtEpochSec,
        String note
    ) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(fromAccountId, "fromAccountId");
        Objects.requireNonNull(toAccountId, "toAccountId");

        if (fromAccountId.equals(toAccountId)) {
            throw new IllegalArgumentException("fromAccountId");
        }
        if (amountCents < 0) {
            throw new IllegalArgumentException("amountCents");
        }

        String id = UUID.randomUUID().toString();
        long now = Instant.now().getEpochSecond();

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "INSERT INTO transfers (id, user_uid, from_account_id, to_account_id, amount_cents, occurred_at_epoch_sec, note, created_at_epoch_sec, updated_at_epoch_sec, pending_sync) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)"
        )) {
            ps.setString(1, id);
            ps.setString(2, userUid);
            ps.setString(3, fromAccountId);
            ps.setString(4, toAccountId);
            ps.setLong(5, amountCents);
            ps.setLong(6, occurredAtEpochSec);
            ps.setString(7, note);
            ps.setLong(8, now);
            ps.setLong(9, now);
            ps.executeUpdate();
        }

        return id;
    }

    public TransferSyncRow getForSyncByIdOrNull(String userUid, String transferId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(transferId, "transferId");

        String sql =
            "SELECT id, user_uid, from_account_id, to_account_id, amount_cents, occurred_at_epoch_sec, note, created_at_epoch_sec, updated_at_epoch_sec " +
            "FROM transfers WHERE user_uid = ? AND id = ?";

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userUid);
            ps.setString(2, transferId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new TransferSyncRow(
                    rs.getString("id"),
                    rs.getString("user_uid"),
                    rs.getString("from_account_id"),
                    rs.getString("to_account_id"),
                    rs.getLong("amount_cents"),
                    rs.getLong("occurred_at_epoch_sec"),
                    rs.getString("note"),
                    rs.getLong("created_at_epoch_sec"),
                    rs.getLong("updated_at_epoch_sec")
                );
            }
        }
    }

    public void upsertFromRemote(String userUid, TransferSyncRow remote) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(remote, "remote");

        TransferSyncRow local = getForSyncByIdOrNull(userUid, remote.id());
        if (local == null) {
            try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO transfers (id, user_uid, from_account_id, to_account_id, amount_cents, occurred_at_epoch_sec, note, created_at_epoch_sec, updated_at_epoch_sec, pending_sync) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)"
            )) {
                ps.setString(1, remote.id());
                ps.setString(2, userUid);
                ps.setString(3, remote.fromAccountId());
                ps.setString(4, remote.toAccountId());
                ps.setLong(5, remote.amountCents());
                ps.setLong(6, remote.occurredAtEpochSec());
                ps.setString(7, remote.note());
                ps.setLong(8, remote.createdAtEpochSec());
                ps.setLong(9, remote.updatedAtEpochSec());
                ps.executeUpdate();
            }
            return;
        }

        if (remote.updatedAtEpochSec() < local.updatedAtEpochSec()) {
            return;
        }
        if (remote.updatedAtEpochSec() == local.updatedAtEpochSec()) {
            boolean same =
                Objects.equals(remote.fromAccountId(), local.fromAccountId()) &&
                    Objects.equals(remote.toAccountId(), local.toAccountId()) &&
                    remote.amountCents() == local.amountCents() &&
                    remote.occurredAtEpochSec() == local.occurredAtEpochSec() &&
                    Objects.equals(remote.note(), local.note());
            if (same) {
                return;
            }
        }

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE transfers SET from_account_id = ?, to_account_id = ?, amount_cents = ?, occurred_at_epoch_sec = ?, note = ?, created_at_epoch_sec = ?, updated_at_epoch_sec = ?, pending_sync = 0 " +
            "WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, remote.fromAccountId());
            ps.setString(2, remote.toAccountId());
            ps.setLong(3, remote.amountCents());
            ps.setLong(4, remote.occurredAtEpochSec());
            ps.setString(5, remote.note());
            ps.setLong(6, remote.createdAtEpochSec());
            ps.setLong(7, remote.updatedAtEpochSec());
            ps.setString(8, userUid);
            ps.setString(9, remote.id());
            ps.executeUpdate();
        }
    }

    public TransferSyncRow getForSyncById(String userUid, String transferId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(transferId, "transferId");

        String sql =
            "SELECT id, user_uid, from_account_id, to_account_id, amount_cents, occurred_at_epoch_sec, note, created_at_epoch_sec, updated_at_epoch_sec " +
            "FROM transfers WHERE user_uid = ? AND id = ?";

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userUid);
            ps.setString(2, transferId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("transfer_not_found");
                }
                return new TransferSyncRow(
                    rs.getString("id"),
                    rs.getString("user_uid"),
                    rs.getString("from_account_id"),
                    rs.getString("to_account_id"),
                    rs.getLong("amount_cents"),
                    rs.getLong("occurred_at_epoch_sec"),
                    rs.getString("note"),
                    rs.getLong("created_at_epoch_sec"),
                    rs.getLong("updated_at_epoch_sec")
                );
            }
        }
    }

    public void update(
        String userUid,
        String transferId,
        String fromAccountId,
        String toAccountId,
        long amountCents,
        long occurredAtEpochSec,
        String note
    ) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(fromAccountId, "fromAccountId");
        Objects.requireNonNull(toAccountId, "toAccountId");

        if (fromAccountId.equals(toAccountId)) {
            throw new IllegalArgumentException("fromAccountId");
        }
        if (amountCents < 0) {
            throw new IllegalArgumentException("amountCents");
        }

        long now = Instant.now().getEpochSecond();
        try (Connection c = db.openConnection()) {
            long existingUpdatedAt = 0L;
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT updated_at_epoch_sec FROM transfers WHERE user_uid = ? AND id = ?"
            )) {
                ps.setString(1, userUid);
                ps.setString(2, transferId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        existingUpdatedAt = rs.getLong("updated_at_epoch_sec");
                    }
                }
            }
            if (now <= existingUpdatedAt) {
                now = existingUpdatedAt + 1L;
            }

            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE transfers " +
                "SET from_account_id = ?, to_account_id = ?, amount_cents = ?, occurred_at_epoch_sec = ?, note = ?, updated_at_epoch_sec = ?, pending_sync = 1 " +
                "WHERE user_uid = ? AND id = ?"
            )) {
                ps.setString(1, fromAccountId);
                ps.setString(2, toAccountId);
                ps.setLong(3, amountCents);
                ps.setLong(4, occurredAtEpochSec);
                ps.setString(5, note);
                ps.setLong(6, now);
                ps.setString(7, userUid);
                ps.setString(8, transferId);
                ps.executeUpdate();
            }
        }
    }

    public List<TransferSyncRow> listPendingForSync(String userUid) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");

        String sql =
            "SELECT id, user_uid, from_account_id, to_account_id, amount_cents, occurred_at_epoch_sec, note, created_at_epoch_sec, updated_at_epoch_sec " +
            "FROM transfers WHERE user_uid = ? AND pending_sync = 1 ORDER BY occurred_at_epoch_sec ASC, created_at_epoch_sec ASC";

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userUid);
            List<TransferSyncRow> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TransferSyncRow(
                        rs.getString("id"),
                        rs.getString("user_uid"),
                        rs.getString("from_account_id"),
                        rs.getString("to_account_id"),
                        rs.getLong("amount_cents"),
                        rs.getLong("occurred_at_epoch_sec"),
                        rs.getString("note"),
                        rs.getLong("created_at_epoch_sec"),
                        rs.getLong("updated_at_epoch_sec")
                    ));
                }
            }
            return out;
        }
    }

    public void markSynced(String userUid, String transferId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(transferId, "transferId");
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE transfers SET pending_sync = 0 WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, transferId);
            ps.executeUpdate();
        }
    }

    public List<String> listIdsForRemotePrune(String userUid) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id FROM transfers WHERE user_uid = ? AND pending_sync = 0"
        )) {
            ps.setString(1, userUid);
            List<String> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString("id"));
                }
            }
            return out;
        }
    }

    public void delete(String userUid, String transferId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(transferId, "transferId");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "DELETE FROM transfers WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, transferId);
            ps.executeUpdate();
        }
    }

    public List<TransferRow> listFiltered(
        String userUid,
        String accountId,
        Long fromOccurredAtEpochSec,
        Long toOccurredAtEpochSec,
        int limit
    ) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        int lim = limit <= 0 ? 50 : limit;

        StringBuilder sql = new StringBuilder(
            "SELECT tr.id, tr.user_uid, tr.from_account_id, a_from.name AS from_account_name, " +
            "       tr.to_account_id, a_to.name AS to_account_name, " +
            "       tr.amount_cents, tr.occurred_at_epoch_sec, tr.note " +
            "FROM transfers tr " +
            "INNER JOIN accounts a_from ON a_from.id = tr.from_account_id " +
            "INNER JOIN accounts a_to ON a_to.id = tr.to_account_id " +
            "WHERE tr.user_uid = ?"
        );

        List<Object> args = new ArrayList<>();
        args.add(userUid);

        if (accountId != null && !accountId.isBlank()) {
            sql.append(" AND (tr.from_account_id = ? OR tr.to_account_id = ?)");
            args.add(accountId);
            args.add(accountId);
        }
        if (fromOccurredAtEpochSec != null) {
            sql.append(" AND tr.occurred_at_epoch_sec >= ?");
            args.add(fromOccurredAtEpochSec);
        }
        if (toOccurredAtEpochSec != null) {
            sql.append(" AND tr.occurred_at_epoch_sec <= ?");
            args.add(toOccurredAtEpochSec);
        }

        sql.append(" ORDER BY tr.occurred_at_epoch_sec DESC, tr.created_at_epoch_sec DESC LIMIT ?");
        args.add(lim);

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                Object v = args.get(i);
                int idx = i + 1;
                if (v == null) {
                    ps.setObject(idx, null);
                } else if (v instanceof Long l) {
                    ps.setLong(idx, l);
                } else if (v instanceof Integer in) {
                    ps.setInt(idx, in);
                } else {
                    ps.setString(idx, v.toString());
                }
            }

            List<TransferRow> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TransferRow(
                        rs.getString("id"),
                        rs.getString("user_uid"),
                        rs.getString("from_account_id"),
                        rs.getString("from_account_name"),
                        rs.getString("to_account_id"),
                        rs.getString("to_account_name"),
                        rs.getLong("amount_cents"),
                        rs.getLong("occurred_at_epoch_sec"),
                        rs.getString("note")
                    ));
                }
            }
            return out;
        }
    }

    public List<TransferSyncRow> listAllForSync(String userUid) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");

        String sql =
            "SELECT id, user_uid, from_account_id, to_account_id, amount_cents, occurred_at_epoch_sec, note, created_at_epoch_sec, updated_at_epoch_sec " +
            "FROM transfers WHERE user_uid = ? ORDER BY occurred_at_epoch_sec ASC, created_at_epoch_sec ASC";

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userUid);
            List<TransferSyncRow> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TransferSyncRow(
                        rs.getString("id"),
                        rs.getString("user_uid"),
                        rs.getString("from_account_id"),
                        rs.getString("to_account_id"),
                        rs.getLong("amount_cents"),
                        rs.getLong("occurred_at_epoch_sec"),
                        rs.getString("note"),
                        rs.getLong("created_at_epoch_sec"),
                        rs.getLong("updated_at_epoch_sec")
                    ));
                }
            }
            return out;
        }
    }
}
