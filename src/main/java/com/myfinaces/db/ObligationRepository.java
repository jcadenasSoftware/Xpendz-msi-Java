package com.myfinaces.db;

import com.myfinaces.sync.DeviceId;
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

public final class ObligationRepository {

    public static final String TYPE_RECEIVABLE = "POR_COBRAR";
    public static final String TYPE_PAYABLE = "POR_PAGAR";

    private final SqliteDatabase db;

    public ObligationRepository(SqliteDatabase db) {
        this.db = db;
    }

    public SqliteDatabase database() {
        return db;
    }

    public record Obligation(
        String id,
        String userUid,
        String type,
        String title,
        String counterpartyName,
        String notes,
        String reference,
        String obligationCategoryId,
        String currency,
        long originalAmountCents,
        long issuedAtEpochSec,
        Long dueAtEpochSec,
        Long cancelledAtEpochSec,
        long createdAtEpochSec,
        long updatedAtEpochSec,
        String updatedBy
    ) {
    }

    public static String normalizeType(String type) {
        String normalized = type == null ? "" : type.trim().toUpperCase(java.util.Locale.ROOT);
        if (TYPE_RECEIVABLE.equals(normalized) || TYPE_PAYABLE.equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("type");
    }

    public String create(
        String userUid,
        String type,
        String title,
        String counterpartyName,
        String currency,
        long originalAmountCents,
        long issuedAtEpochSec,
        Long dueAtEpochSec,
        String obligationCategoryId,
        String reference,
        String notes
    ) throws SQLException {
        String id = UUID.randomUUID().toString();
        createWithId(id, userUid, type, title, counterpartyName, currency, originalAmountCents, issuedAtEpochSec, dueAtEpochSec, null, obligationCategoryId, reference, notes);
        return id;
    }

    public void createWithId(
        String id,
        String userUid,
        String type,
        String title,
        String counterpartyName,
        String currency,
        long originalAmountCents,
        long issuedAtEpochSec,
        Long dueAtEpochSec,
        Long cancelledAtEpochSec,
        String obligationCategoryId,
        String reference,
        String notes
    ) throws SQLException {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(counterpartyName, "counterpartyName");
        Objects.requireNonNull(currency, "currency");

        String normalizedType = normalizeType(type);
        String normalizedTitle = requireNonBlank(title, "title");
        String normalizedCounterparty = requireNonBlank(counterpartyName, "counterpartyName");
        String normalizedCurrency = requireNonBlank(currency, "currency");
        if (originalAmountCents <= 0L) {
            throw new IllegalArgumentException("originalAmountCents");
        }

        long now = Instant.now().getEpochSecond();
        long issued = issuedAtEpochSec > 0L ? issuedAtEpochSec : now;

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "INSERT INTO obligations (id, user_uid, type, title, counterparty_name, notes, reference, obligation_category_id, currency, original_amount_cents, issued_at_epoch_sec, due_at_epoch_sec, cancelled_at_epoch_sec, created_at_epoch_sec, updated_at_epoch_sec, updated_by, pending_sync) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)"
        )) {
            ps.setString(1, id);
            ps.setString(2, userUid);
            ps.setString(3, normalizedType);
            ps.setString(4, normalizedTitle);
            ps.setString(5, normalizedCounterparty);
            setNullableString(ps, 6, notes);
            setNullableString(ps, 7, reference);
            setNullableString(ps, 8, obligationCategoryId);
            ps.setString(9, normalizedCurrency);
            ps.setLong(10, originalAmountCents);
            ps.setLong(11, issued);
            setNullableLong(ps, 12, dueAtEpochSec);
            setNullableLong(ps, 13, cancelledAtEpochSec);
            ps.setLong(14, now);
            ps.setLong(15, now);
            setNullableString(ps, 16, DeviceId.get());
            ps.executeUpdate();
        }
    }

    public void update(
        String userUid,
        String obligationId,
        String type,
        String title,
        String counterpartyName,
        String currency,
        long originalAmountCents,
        long issuedAtEpochSec,
        Long dueAtEpochSec,
        Long cancelledAtEpochSec,
        String obligationCategoryId,
        String reference,
        String notes
    ) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(obligationId, "obligationId");
        String normalizedType = normalizeType(type);
        String normalizedTitle = requireNonBlank(title, "title");
        String normalizedCounterparty = requireNonBlank(counterpartyName, "counterpartyName");
        String normalizedCurrency = requireNonBlank(currency, "currency");
        if (originalAmountCents <= 0L) {
            throw new IllegalArgumentException("originalAmountCents");
        }

        long now = Instant.now().getEpochSecond();
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE obligations SET type = ?, title = ?, counterparty_name = ?, notes = ?, reference = ?, obligation_category_id = ?, currency = ?, original_amount_cents = ?, issued_at_epoch_sec = ?, due_at_epoch_sec = ?, cancelled_at_epoch_sec = ?, updated_at_epoch_sec = ?, updated_by = ?, pending_sync = 1 WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, normalizedType);
            ps.setString(2, normalizedTitle);
            ps.setString(3, normalizedCounterparty);
            setNullableString(ps, 4, notes);
            setNullableString(ps, 5, reference);
            setNullableString(ps, 6, obligationCategoryId);
            ps.setString(7, normalizedCurrency);
            ps.setLong(8, originalAmountCents);
            ps.setLong(9, issuedAtEpochSec);
            setNullableLong(ps, 10, dueAtEpochSec);
            setNullableLong(ps, 11, cancelledAtEpochSec);
            ps.setLong(12, now);
            setNullableString(ps, 13, DeviceId.get());
            ps.setString(14, userUid);
            ps.setString(15, obligationId);
            ps.executeUpdate();
        }
    }

    public void cancel(String userUid, String obligationId, long cancelledAtEpochSec) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(obligationId, "obligationId");
        long now = Instant.now().getEpochSecond();
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE obligations SET cancelled_at_epoch_sec = ?, updated_at_epoch_sec = ?, updated_by = ?, pending_sync = 1 WHERE user_uid = ? AND id = ?"
        )) {
            ps.setLong(1, cancelledAtEpochSec);
            ps.setLong(2, now);
            setNullableString(ps, 3, DeviceId.get());
            ps.setString(4, userUid);
            ps.setString(5, obligationId);
            ps.executeUpdate();
        }
    }

    public Obligation getByIdOrNull(String userUid, String obligationId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(obligationId, "obligationId");
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, user_uid, type, title, counterparty_name, notes, reference, obligation_category_id, currency, original_amount_cents, issued_at_epoch_sec, due_at_epoch_sec, cancelled_at_epoch_sec, created_at_epoch_sec, updated_at_epoch_sec, updated_by FROM obligations WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, obligationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    public List<Obligation> listAllByUser(String userUid) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, user_uid, type, title, counterparty_name, notes, reference, obligation_category_id, currency, original_amount_cents, issued_at_epoch_sec, due_at_epoch_sec, cancelled_at_epoch_sec, created_at_epoch_sec, updated_at_epoch_sec, updated_by FROM obligations WHERE user_uid = ? ORDER BY updated_at_epoch_sec DESC, created_at_epoch_sec DESC"
        )) {
            ps.setString(1, userUid);
            List<Obligation> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapRow(rs));
                }
            }
            return out;
        }
    }

    public List<Obligation> listPendingForSync(String userUid) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, user_uid, type, title, counterparty_name, notes, reference, obligation_category_id, currency, original_amount_cents, issued_at_epoch_sec, due_at_epoch_sec, cancelled_at_epoch_sec, created_at_epoch_sec, updated_at_epoch_sec, updated_by FROM obligations WHERE user_uid = ? AND pending_sync = 1 ORDER BY updated_at_epoch_sec ASC, created_at_epoch_sec ASC"
        )) {
            ps.setString(1, userUid);
            List<Obligation> out = new ArrayList<>();
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
            "SELECT id FROM obligations WHERE user_uid = ? AND pending_sync = 1"
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

    public void markSynced(String userUid, String obligationId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(obligationId, "obligationId");
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE obligations SET pending_sync = 0 WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, obligationId);
            ps.executeUpdate();
        }
    }

    public void deleteLocal(String userUid, String obligationId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(obligationId, "obligationId");
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "DELETE FROM obligations WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, obligationId);
            ps.executeUpdate();
        }
    }

    public void upsertFromRemote(String userUid, Obligation remote) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(remote, "remote");
        Obligation local = getByIdOrNull(userUid, remote.id());
        if (local == null) {
            try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO obligations (id, user_uid, type, title, counterparty_name, notes, reference, obligation_category_id, currency, original_amount_cents, issued_at_epoch_sec, due_at_epoch_sec, cancelled_at_epoch_sec, created_at_epoch_sec, updated_at_epoch_sec, updated_by, pending_sync) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)"
            )) {
                bind(ps, remote, userUid);
                ps.executeUpdate();
            }
            return;
        }
        if (!ObligationMergePolicy.shouldAcceptRemote(local, remote)) {
            return;
        }
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE obligations SET type = ?, title = ?, counterparty_name = ?, notes = ?, reference = ?, obligation_category_id = ?, currency = ?, original_amount_cents = ?, issued_at_epoch_sec = ?, due_at_epoch_sec = ?, cancelled_at_epoch_sec = ?, created_at_epoch_sec = ?, updated_at_epoch_sec = ?, updated_by = ?, pending_sync = 0 WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, normalizeType(remote.type()));
            ps.setString(2, remote.title());
            ps.setString(3, remote.counterpartyName());
            setNullableString(ps, 4, remote.notes());
            setNullableString(ps, 5, remote.reference());
            setNullableString(ps, 6, remote.obligationCategoryId());
            ps.setString(7, remote.currency());
            ps.setLong(8, remote.originalAmountCents());
            ps.setLong(9, remote.issuedAtEpochSec());
            setNullableLong(ps, 10, remote.dueAtEpochSec());
            setNullableLong(ps, 11, remote.cancelledAtEpochSec());
            ps.setLong(12, remote.createdAtEpochSec());
            ps.setLong(13, remote.updatedAtEpochSec());
            setNullableString(ps, 14, remote.updatedBy());
            ps.setString(15, userUid);
            ps.setString(16, remote.id());
            ps.executeUpdate();
        }
    }

    private void bind(PreparedStatement ps, Obligation obligation, String userUid) throws SQLException {
        ps.setString(1, obligation.id());
        ps.setString(2, userUid);
        ps.setString(3, normalizeType(obligation.type()));
        ps.setString(4, obligation.title());
        ps.setString(5, obligation.counterpartyName());
        setNullableString(ps, 6, obligation.notes());
        setNullableString(ps, 7, obligation.reference());
        setNullableString(ps, 8, obligation.obligationCategoryId());
        ps.setString(9, obligation.currency());
        ps.setLong(10, obligation.originalAmountCents());
        ps.setLong(11, obligation.issuedAtEpochSec());
        setNullableLong(ps, 12, obligation.dueAtEpochSec());
        setNullableLong(ps, 13, obligation.cancelledAtEpochSec());
        ps.setLong(14, obligation.createdAtEpochSec());
        ps.setLong(15, obligation.updatedAtEpochSec());
        setNullableString(ps, 16, obligation.updatedBy());
    }

    private Obligation mapRow(ResultSet rs) throws SQLException {
        Long dueAt = getNullableLong(rs, "due_at_epoch_sec");
        Long cancelledAt = getNullableLong(rs, "cancelled_at_epoch_sec");
        return new Obligation(
            rs.getString("id"),
            rs.getString("user_uid"),
            rs.getString("type"),
            rs.getString("title"),
            rs.getString("counterparty_name"),
            rs.getString("notes"),
            rs.getString("reference"),
            rs.getString("obligation_category_id"),
            rs.getString("currency"),
            rs.getLong("original_amount_cents"),
            rs.getLong("issued_at_epoch_sec"),
            dueAt,
            cancelledAt,
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

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setObject(index, null);
        } else {
            ps.setLong(index, value);
        }
    }

    private static Long getNullableLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value instanceof Number number ? number.longValue() : null;
    }
}
