package com.myfinaces.db;

import myfinances.infrastructure.obligation.sync.ObligationMergePolicy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class ObligationSettlementRepository {

    private final SqliteDatabase db;

    public ObligationSettlementRepository(SqliteDatabase db) {
        this.db = db;
    }

    public record ObligationSettlement(
        String id,
        String obligationId,
        String userUid,
        String accountId,
        long amountCents,
        long occurredAtEpochSec,
        String linkedTransactionId,
        String note,
        long createdAtEpochSec,
        long updatedAtEpochSec,
        String updatedBy
    ) {
    }

    public String create(
        String userUid,
        String obligationId,
        String accountId,
        long amountCents,
        long occurredAtEpochSec,
        String linkedTransactionId,
        String note
    ) throws SQLException {
        throw new UnsupportedOperationException("Use ObligationService.registerSettlement");
    }

    public void createWithId(
        String id,
        String userUid,
        String obligationId,
        String accountId,
        long amountCents,
        long occurredAtEpochSec,
        String linkedTransactionId,
        String note,
        String updatedBy
    ) throws SQLException {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userUid, "userUid");
        requireNonBlank(obligationId, "obligationId");
        requireNonBlank(accountId, "accountId");
        requireNonBlank(linkedTransactionId, "linkedTransactionId");
        if (amountCents <= 0L) {
            throw new IllegalArgumentException("amountCents");
        }

        long now = Instant.now().getEpochSecond();
        long occurred = occurredAtEpochSec > 0L ? occurredAtEpochSec : now;

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "INSERT INTO obligation_settlements (id, obligation_id, user_uid, account_id, amount_cents, occurred_at_epoch_sec, linked_transaction_id, note, created_at_epoch_sec, updated_at_epoch_sec, updated_by, pending_sync) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)"
        )) {
            ps.setString(1, id);
            ps.setString(2, obligationId);
            ps.setString(3, userUid);
            ps.setString(4, accountId);
            ps.setLong(5, amountCents);
            ps.setLong(6, occurred);
            ps.setString(7, linkedTransactionId);
            setNullableString(ps, 8, note);
            ps.setLong(9, now);
            ps.setLong(10, now);
            setNullableString(ps, 11, updatedBy);
            ps.executeUpdate();
        }
    }

    public void update(
        String userUid,
        String settlementId,
        String accountId,
        long amountCents,
        long occurredAtEpochSec,
        String linkedTransactionId,
        String note
    ) throws SQLException {
        throw new UnsupportedOperationException("Use ObligationService.updateSettlement");
    }

    public void deleteLocal(String userUid, String settlementId) throws SQLException {
        throw new UnsupportedOperationException("Use ObligationService.deleteSettlement");
    }

    public ObligationSettlement createDirect(
        Connection c,
        String userUid,
        String obligationId,
        String accountId,
        long amountCents,
        long occurredAtEpochSec,
        String linkedTransactionId,
        String note,
        String updatedBy
    ) throws SQLException {
        String id = UUID.randomUUID().toString();
        return createDirectWithId(c, id, userUid, obligationId, accountId, amountCents, occurredAtEpochSec, linkedTransactionId, note, updatedBy);
    }

    public ObligationSettlement createDirectWithId(
        Connection c,
        String id,
        String userUid,
        String obligationId,
        String accountId,
        long amountCents,
        long occurredAtEpochSec,
        String linkedTransactionId,
        String note,
        String updatedBy
    ) throws SQLException {
        Objects.requireNonNull(c, "connection");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userUid, "userUid");
        requireNonBlank(obligationId, "obligationId");
        requireNonBlank(accountId, "accountId");
        requireNonBlank(linkedTransactionId, "linkedTransactionId");
        if (amountCents <= 0L) {
            throw new IllegalArgumentException("amountCents");
        }

        long now = Instant.now().getEpochSecond();
        long occurred = occurredAtEpochSec > 0L ? occurredAtEpochSec : now;
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO obligation_settlements (id, obligation_id, user_uid, account_id, amount_cents, occurred_at_epoch_sec, linked_transaction_id, note, created_at_epoch_sec, updated_at_epoch_sec, updated_by, pending_sync) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)"
        )) {
            ps.setString(1, id);
            ps.setString(2, obligationId);
            ps.setString(3, userUid);
            ps.setString(4, accountId);
            ps.setLong(5, amountCents);
            ps.setLong(6, occurred);
            ps.setString(7, linkedTransactionId);
            setNullableString(ps, 8, note);
            ps.setLong(9, now);
            ps.setLong(10, now);
            setNullableString(ps, 11, updatedBy);
            ps.executeUpdate();
        }
        return new ObligationSettlement(id, obligationId, userUid, accountId, amountCents, occurred, linkedTransactionId, note, now, now, updatedBy);
    }

    public ObligationSettlement updateDirect(
        Connection c,
        ObligationSettlement existing,
        String accountId,
        long amountCents,
        long occurredAtEpochSec,
        String note,
        String updatedBy
    ) throws SQLException {
        Objects.requireNonNull(c, "connection");
        Objects.requireNonNull(existing, "existing");
        requireNonBlank(accountId, "accountId");
        if (amountCents <= 0L) {
            throw new IllegalArgumentException("amountCents");
        }

        long now = nextUpdatedAt(existing.updatedAtEpochSec());
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE obligation_settlements SET account_id = ?, amount_cents = ?, occurred_at_epoch_sec = ?, note = ?, updated_at_epoch_sec = ?, updated_by = ?, pending_sync = 1 WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, accountId);
            ps.setLong(2, amountCents);
            ps.setLong(3, occurredAtEpochSec);
            setNullableString(ps, 4, note);
            ps.setLong(5, now);
            setNullableString(ps, 6, updatedBy);
            ps.setString(7, existing.userUid());
            ps.setString(8, existing.id());
            ps.executeUpdate();
        }
        return new ObligationSettlement(
            existing.id(),
            existing.obligationId(),
            existing.userUid(),
            accountId,
            amountCents,
            occurredAtEpochSec,
            existing.linkedTransactionId(),
            note,
            existing.createdAtEpochSec(),
            now,
            updatedBy
        );
    }

    public void deleteDirect(Connection c, String userUid, String settlementId) throws SQLException {
        Objects.requireNonNull(c, "connection");
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(settlementId, "settlementId");
        try (PreparedStatement ps = c.prepareStatement(
            "DELETE FROM obligation_settlements WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, settlementId);
            ps.executeUpdate();
        }
    }

    public ObligationSettlement getByIdOrNull(String userUid, String settlementId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(settlementId, "settlementId");
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, obligation_id, user_uid, account_id, amount_cents, occurred_at_epoch_sec, linked_transaction_id, note, created_at_epoch_sec, updated_at_epoch_sec, updated_by FROM obligation_settlements WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, settlementId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    public ObligationSettlement getByLinkedTransactionId(String userUid, String transactionId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(transactionId, "transactionId");
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, obligation_id, user_uid, account_id, amount_cents, occurred_at_epoch_sec, linked_transaction_id, note, created_at_epoch_sec, updated_at_epoch_sec, updated_by FROM obligation_settlements WHERE user_uid = ? AND linked_transaction_id = ? LIMIT 1"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, transactionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    public List<ObligationSettlement> listByObligation(String userUid, String obligationId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(obligationId, "obligationId");
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, obligation_id, user_uid, account_id, amount_cents, occurred_at_epoch_sec, linked_transaction_id, note, created_at_epoch_sec, updated_at_epoch_sec, updated_by FROM obligation_settlements WHERE user_uid = ? AND obligation_id = ? ORDER BY occurred_at_epoch_sec ASC, created_at_epoch_sec ASC"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, obligationId);
            List<ObligationSettlement> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapRow(rs));
                }
            }
            return out;
        }
    }

    public List<ObligationSettlement> listAllByUser(String userUid) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, obligation_id, user_uid, account_id, amount_cents, occurred_at_epoch_sec, linked_transaction_id, note, created_at_epoch_sec, updated_at_epoch_sec, updated_by FROM obligation_settlements WHERE user_uid = ? ORDER BY occurred_at_epoch_sec DESC, created_at_epoch_sec DESC"
        )) {
            ps.setString(1, userUid);
            List<ObligationSettlement> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapRow(rs));
                }
            }
            return out;
        }
    }

    public long sumSettledCentsByObligation(String userUid, String obligationId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(obligationId, "obligationId");
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT COALESCE(SUM(amount_cents), 0) AS total FROM obligation_settlements WHERE user_uid = ? AND obligation_id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, obligationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("total") : 0L;
            }
        }
    }

    public List<ObligationSettlement> listPendingForSync(String userUid) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, obligation_id, user_uid, account_id, amount_cents, occurred_at_epoch_sec, linked_transaction_id, note, created_at_epoch_sec, updated_at_epoch_sec, updated_by FROM obligation_settlements WHERE user_uid = ? AND pending_sync = 1 ORDER BY occurred_at_epoch_sec ASC, created_at_epoch_sec ASC"
        )) {
            ps.setString(1, userUid);
            List<ObligationSettlement> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapRow(rs));
                }
            }
            return out;
        }
    }

    public Set<String> listPendingSyncIds(String userUid) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id FROM obligation_settlements WHERE user_uid = ? AND pending_sync = 1"
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

    public void markSynced(String userUid, String settlementId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(settlementId, "settlementId");
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE obligation_settlements SET pending_sync = 0 WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, settlementId);
            ps.executeUpdate();
        }
    }

    public void upsertFromRemote(String userUid, ObligationSettlement remote) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(remote, "remote");
        ObligationSettlement local = getByIdOrNull(userUid, remote.id());
        if (local == null) {
            try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO obligation_settlements (id, obligation_id, user_uid, account_id, amount_cents, occurred_at_epoch_sec, linked_transaction_id, note, created_at_epoch_sec, updated_at_epoch_sec, updated_by, pending_sync) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)"
            )) {
                bind(ps, remote, userUid);
                ps.executeUpdate();
            }
            return;
        }
        // Referencias estables: un settlement existente jamás cambia de
        // obligación padre ni de transacción enlazada por sync.
        if (!Objects.equals(local.obligationId(), remote.obligationId())
            || !Objects.equals(local.linkedTransactionId(), remote.linkedTransactionId())) {
            return;
        }
        if (!ObligationMergePolicy.shouldAcceptRemote(local, remote)) {
            return;
        }
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE obligation_settlements SET obligation_id = ?, account_id = ?, amount_cents = ?, occurred_at_epoch_sec = ?, linked_transaction_id = ?, note = ?, created_at_epoch_sec = ?, updated_at_epoch_sec = ?, updated_by = ?, pending_sync = 0 WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, remote.obligationId());
            ps.setString(2, remote.accountId());
            ps.setLong(3, remote.amountCents());
            ps.setLong(4, remote.occurredAtEpochSec());
            ps.setString(5, remote.linkedTransactionId());
            setNullableString(ps, 6, remote.note());
            ps.setLong(7, remote.createdAtEpochSec());
            ps.setLong(8, remote.updatedAtEpochSec());
            setNullableString(ps, 9, remote.updatedBy());
            ps.setString(10, userUid);
            ps.setString(11, remote.id());
            ps.executeUpdate();
        }
    }

    private void bind(PreparedStatement ps, ObligationSettlement settlement, String userUid) throws SQLException {
        ps.setString(1, settlement.id());
        ps.setString(2, settlement.obligationId());
        ps.setString(3, userUid);
        ps.setString(4, settlement.accountId());
        ps.setLong(5, settlement.amountCents());
        ps.setLong(6, settlement.occurredAtEpochSec());
        ps.setString(7, settlement.linkedTransactionId());
        setNullableString(ps, 8, settlement.note());
        ps.setLong(9, settlement.createdAtEpochSec());
        ps.setLong(10, settlement.updatedAtEpochSec());
        setNullableString(ps, 11, settlement.updatedBy());
    }

    private ObligationSettlement mapRow(ResultSet rs) throws SQLException {
        return new ObligationSettlement(
            rs.getString("id"),
            rs.getString("obligation_id"),
            rs.getString("user_uid"),
            rs.getString("account_id"),
            rs.getLong("amount_cents"),
            rs.getLong("occurred_at_epoch_sec"),
            rs.getString("linked_transaction_id"),
            rs.getString("note"),
            rs.getLong("created_at_epoch_sec"),
            rs.getLong("updated_at_epoch_sec"),
            rs.getString("updated_by")
        );
    }

    private static String requireNonBlank(String value, String field) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException(field);
        }
        return trimmed;
    }

    private static void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null || value.isBlank()) {
            ps.setObject(index, null);
        } else {
            ps.setString(index, value);
        }
    }

    private static long nextUpdatedAt(long previousUpdatedAt) {
        long now = Instant.now().getEpochSecond();
        return now > previousUpdatedAt ? now : previousUpdatedAt + 1L;
    }
}
