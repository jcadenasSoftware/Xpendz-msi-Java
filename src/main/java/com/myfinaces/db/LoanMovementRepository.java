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

/**
 * Repositorio para movimientos de préstamos.
 * <p>
 * Cada acción financiera sobre un préstamo se registra como un movimiento,
 * creando un historial completo: creación, adiciones, pagos recibidos/realizados, cierre.
 */
public final class LoanMovementRepository {

    // ── Tipos de movimiento ─────────────────────────────────────
    public static final String MOV_CREATION       = "CREATION";
    public static final String MOV_TOPUP          = "TOPUP";
    public static final String MOV_PAYMENT_IN     = "PAYMENT_IN";
    public static final String MOV_PAYMENT_OUT    = "PAYMENT_OUT";
    public static final String MOV_CLOSE          = "CLOSE";

    private final SqliteDatabase db;

    public LoanMovementRepository(SqliteDatabase db) {
        this.db = db;
    }

    public record LoanMovement(
        String id,
        String loanId,
        String userUid,
        String movementType,
        long amountCents,
        String accountId,
        String linkedTransactionId,
        String note,
        long occurredAtEpochSec,
        long createdAtEpochSec,
        long updatedAtEpochSec,
        String updatedBy
    ) {}

    // ── CREATE ──────────────────────────────────────────────────

    public String create(
        String userUid,
        String loanId,
        String movementType,
        long amountCents,
        String accountId,
        String linkedTransactionId,
        String note,
        long occurredAtEpochSec
    ) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(loanId, "loanId");
        Objects.requireNonNull(movementType, "movementType");

        String id = UUID.randomUUID().toString();
        long now = Instant.now().getEpochSecond();
        long occ = occurredAtEpochSec > 0 ? occurredAtEpochSec : now;

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "INSERT INTO loan_movements (id, loan_id, user_uid, movement_type, amount_cents, account_id, linked_transaction_id, note, occurred_at_epoch_sec, created_at_epoch_sec, updated_at_epoch_sec, pending_sync) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)"
        )) {
            ps.setString(1, id);
            ps.setString(2, loanId);
            ps.setString(3, userUid);
            ps.setString(4, movementType);
            ps.setLong(5, amountCents);
            setNullableString(ps, 6, accountId);
            setNullableString(ps, 7, linkedTransactionId);
            ps.setString(8, note);
            ps.setLong(9, occ);
            ps.setLong(10, now);
            ps.setLong(11, now);
            ps.executeUpdate();
        }
        return id;
    }

    // ── READ ────────────────────────────────────────────────────

    public LoanMovement getByIdOrNull(String userUid, String movementId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(movementId, "movementId");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, loan_id, user_uid, movement_type, amount_cents, account_id, linked_transaction_id, note, occurred_at_epoch_sec, created_at_epoch_sec, updated_at_epoch_sec, updated_by " +
            "FROM loan_movements WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, movementId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    public List<LoanMovement> listByLoan(String userUid, String loanId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(loanId, "loanId");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, loan_id, user_uid, movement_type, amount_cents, account_id, linked_transaction_id, note, occurred_at_epoch_sec, created_at_epoch_sec, updated_at_epoch_sec, updated_by " +
            "FROM loan_movements WHERE user_uid = ? AND loan_id = ? ORDER BY occurred_at_epoch_sec ASC, created_at_epoch_sec ASC"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, loanId);
            return collectRows(ps);
        }
    }

    public List<LoanMovement> listAllByUser(String userUid) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, loan_id, user_uid, movement_type, amount_cents, account_id, linked_transaction_id, note, occurred_at_epoch_sec, created_at_epoch_sec, updated_at_epoch_sec, updated_by " +
            "FROM loan_movements WHERE user_uid = ? ORDER BY occurred_at_epoch_sec DESC, created_at_epoch_sec DESC"
        )) {
            ps.setString(1, userUid);
            return collectRows(ps);
        }
    }

    /** Suma de topups (dinero adicional agregado) para un préstamo. */
    public long sumTopupCents(String userUid, String loanId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(loanId, "loanId");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT COALESCE(SUM(amount_cents), 0) AS total FROM loan_movements WHERE user_uid = ? AND loan_id = ? AND movement_type = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, loanId);
            ps.setString(3, MOV_TOPUP);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("total") : 0L;
            }
        }
    }

    // ── SYNC ────────────────────────────────────────────────────

    public List<LoanMovement> listPendingForSync(String userUid) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, loan_id, user_uid, movement_type, amount_cents, account_id, linked_transaction_id, note, occurred_at_epoch_sec, created_at_epoch_sec, updated_at_epoch_sec, updated_by " +
            "FROM loan_movements WHERE user_uid = ? AND pending_sync = 1 ORDER BY occurred_at_epoch_sec ASC"
        )) {
            ps.setString(1, userUid);
            return collectRows(ps);
        }
    }

    public void markSynced(String userUid, String movementId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(movementId, "movementId");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE loan_movements SET pending_sync = 0 WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, movementId);
            ps.executeUpdate();
        }
    }

    public void upsertFromRemote(String userUid, LoanMovement remote) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(remote, "remote");

        LoanMovement local = getByIdOrNull(userUid, remote.id());
        if (local == null) {
            try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO loan_movements (id, loan_id, user_uid, movement_type, amount_cents, account_id, linked_transaction_id, note, occurred_at_epoch_sec, created_at_epoch_sec, updated_at_epoch_sec, updated_by, pending_sync) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)"
            )) {
                ps.setString(1, remote.id());
                ps.setString(2, remote.loanId());
                ps.setString(3, userUid);
                ps.setString(4, remote.movementType());
                ps.setLong(5, remote.amountCents());
                setNullableString(ps, 6, remote.accountId());
                setNullableString(ps, 7, remote.linkedTransactionId());
                ps.setString(8, remote.note());
                ps.setLong(9, remote.occurredAtEpochSec());
                ps.setLong(10, remote.createdAtEpochSec());
                ps.setLong(11, remote.updatedAtEpochSec());
                setNullableString(ps, 12, remote.updatedBy());
                ps.executeUpdate();
            }
            return;
        }

        if (remote.updatedAtEpochSec() <= local.updatedAtEpochSec()) return;

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE loan_movements SET movement_type = ?, amount_cents = ?, account_id = ?, linked_transaction_id = ?, note = ?, occurred_at_epoch_sec = ?, updated_at_epoch_sec = ?, updated_by = ?, pending_sync = 0 " +
            "WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, remote.movementType());
            ps.setLong(2, remote.amountCents());
            setNullableString(ps, 3, remote.accountId());
            setNullableString(ps, 4, remote.linkedTransactionId());
            ps.setString(5, remote.note());
            ps.setLong(6, remote.occurredAtEpochSec());
            ps.setLong(7, remote.updatedAtEpochSec());
            setNullableString(ps, 8, remote.updatedBy());
            ps.setString(9, userUid);
            ps.setString(10, remote.id());
            ps.executeUpdate();
        }
    }

    // ── INTERNALS ───────────────────────────────────────────────

    private static LoanMovement mapRow(ResultSet rs) throws SQLException {
        return new LoanMovement(
            rs.getString("id"),
            rs.getString("loan_id"),
            rs.getString("user_uid"),
            rs.getString("movement_type"),
            rs.getLong("amount_cents"),
            rs.getString("account_id"),
            rs.getString("linked_transaction_id"),
            rs.getString("note"),
            rs.getLong("occurred_at_epoch_sec"),
            rs.getLong("created_at_epoch_sec"),
            rs.getLong("updated_at_epoch_sec"),
            rs.getString("updated_by")
        );
    }

    private static List<LoanMovement> collectRows(PreparedStatement ps) throws SQLException {
        List<LoanMovement> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(mapRow(rs));
        }
        return out;
    }

    private static void setNullableString(PreparedStatement ps, int idx, String val) throws SQLException {
        if (val == null || val.isBlank()) {
            ps.setObject(idx, null);
        } else {
            ps.setString(idx, val);
        }
    }
}
