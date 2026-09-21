package com.myfinaces.sync;

import com.myfinaces.db.SqliteDatabase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Cola de publicación de metas sobre la tabla {@code outbox} existente.
 *
 * <p>Permite al sincronizador distinguir, sin decisiones funcionales, entre:
 * una meta local cuyo push aún no se confirmó ({@code PUBLISH} pendiente),
 * una eliminación local pendiente de propagar ({@code DELETE} pendiente) y
 * una meta ya sincronizada que desapareció del snapshot remoto (borrado
 * legítimo ejecutado en otro dispositivo).
 */
public final class GoalPublishQueue {

    public static final String ENTITY_TYPE = "goal";
    public static final String OP_PUBLISH = "PUBLISH";
    public static final String OP_DELETE = "DELETE";
    private static final String STATUS_PENDING = "PENDING";
    private static final int MAX_ERROR_LEN = 500;

    public record Entry(long id, String userUid, String goalId, String operation) {}

    private GoalPublishQueue() {
    }

    /** Marca la meta como pendiente de publicación (creación/edición/reapertura). */
    public static void markPendingPublish(SqliteDatabase db, String userUid, String goalId)
        throws SQLException {
        markPending(db, userUid, goalId, OP_PUBLISH);
    }

    /** Marca la meta como pendiente de borrado remoto. */
    public static void markPendingDelete(SqliteDatabase db, String userUid, String goalId)
        throws SQLException {
        markPending(db, userUid, goalId, OP_DELETE);
    }

    private static void markPending(SqliteDatabase db, String userUid, String goalId, String operation)
        throws SQLException {
        long now = Instant.now().getEpochSecond();
        try (Connection c = db.openConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM outbox WHERE entity_type=? AND entity_id=? AND status=?")) {
                ps.setString(1, ENTITY_TYPE);
                ps.setString(2, goalId);
                ps.setString(3, STATUS_PENDING);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO outbox (user_uid, entity_type, entity_id, operation, payload_json,"
                    + " status, attempt_count, last_error, created_at_epoch_sec, updated_at_epoch_sec)"
                    + " VALUES (?,?,?,?,?,?,0,NULL,?,?)")) {
                ps.setString(1, userUid);
                ps.setString(2, ENTITY_TYPE);
                ps.setString(3, goalId);
                ps.setString(4, operation);
                ps.setString(5, "{\"goalId\":\"" + goalId + "\",\"operation\":\"" + operation + "\"}");
                ps.setString(6, STATUS_PENDING);
                ps.setLong(7, now);
                ps.setLong(8, now);
                ps.executeUpdate();
            }
        }
    }

    /** Elimina cualquier entrada pendiente de la meta tras un push exitoso. */
    public static void markDone(SqliteDatabase db, String goalId) throws SQLException {
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "DELETE FROM outbox WHERE entity_type=? AND entity_id=?")) {
            ps.setString(1, ENTITY_TYPE);
            ps.setString(2, goalId);
            ps.executeUpdate();
        }
    }

    /** Incrementa el contador de intentos y registra el último error. */
    public static void recordFailure(SqliteDatabase db, long id, String error) throws SQLException {
        String err = error == null || error.isBlank() ? "unknown" : error;
        if (err.length() > MAX_ERROR_LEN) {
            err = err.substring(0, MAX_ERROR_LEN);
        }
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE outbox SET attempt_count=attempt_count+1, last_error=?,"
                + " updated_at_epoch_sec=? WHERE id=?")) {
            ps.setString(1, err);
            ps.setLong(2, Instant.now().getEpochSecond());
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    /** Lista las publicaciones/borrados pendientes en orden de encolado. */
    public static List<Entry> listPending(SqliteDatabase db) throws SQLException {
        List<Entry> out = new ArrayList<>();
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, user_uid, entity_id, operation FROM outbox"
                + " WHERE entity_type=? AND status=? ORDER BY id")) {
            ps.setString(1, ENTITY_TYPE);
            ps.setString(2, STATUS_PENDING);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Entry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
                }
            }
        }
        return out;
    }

    /** Devuelve los ids de metas con una operación pendiente del tipo indicado. */
    public static Set<String> pendingIds(SqliteDatabase db, String operation) throws SQLException {
        Set<String> out = new LinkedHashSet<>();
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT entity_id FROM outbox WHERE entity_type=? AND operation=? AND status=?")) {
            ps.setString(1, ENTITY_TYPE);
            ps.setString(2, operation);
            ps.setString(3, STATUS_PENDING);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }
}
