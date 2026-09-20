package com.myfinaces.sync;

import com.myfinaces.db.SqliteDatabase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Cola de reintento (tabla {@code outbox}) para la publicación del documento
 * canónico de préstamo {@code users/{uid}/loans/{loanId}}.
 *
 * <p>Si el publish inmediato desde LoansView falla (401, timeout, red, error
 * transitorio), el préstamo se registra aquí y el siguiente
 * {@code pushPending()} lo reintenta leyendo el snapshot canónico vigente. Solo
 * se encolan préstamos cuyo publish falló; nunca se republican préstamos
 * existentes ni se re-ejecutan eventos del journal.
 */
public final class CanonicalLoanPublishQueue {

    public static final String ENTITY_TYPE = "canonical_loan";
    private static final String OPERATION = "PUBLISH";
    private static final String STATUS_PENDING = "PENDING";
    private static final int MAX_ERROR_LEN = 500;

    public record Entry(long id, String userUid, String loanId) {}

    private CanonicalLoanPublishQueue() {
    }

    /** Encola el publish del préstamo si no hay ya una entrada pendiente. */
    public static void markPending(SqliteDatabase db, String userUid, String loanId)
        throws SQLException {
        long now = Instant.now().getEpochSecond();
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "INSERT INTO outbox (user_uid, entity_type, entity_id, operation, payload_json,"
                + " status, attempt_count, last_error, created_at_epoch_sec, updated_at_epoch_sec)"
                + " SELECT ?,?,?,?,?,?,0,NULL,?,?"
                + " WHERE NOT EXISTS ("
                + "   SELECT 1 FROM outbox WHERE entity_type=? AND entity_id=? AND status=?)")) {
            ps.setString(1, userUid);
            ps.setString(2, ENTITY_TYPE);
            ps.setString(3, loanId);
            ps.setString(4, OPERATION);
            ps.setString(5, "{\"loanId\":\"" + loanId + "\"}");
            ps.setString(6, STATUS_PENDING);
            ps.setLong(7, now);
            ps.setLong(8, now);
            ps.setString(9, ENTITY_TYPE);
            ps.setString(10, loanId);
            ps.setString(11, STATUS_PENDING);
            ps.executeUpdate();
        }
    }

    /** Elimina cualquier entrada pendiente del préstamo tras un publish exitoso. */
    public static void markDone(SqliteDatabase db, String loanId) throws SQLException {
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "DELETE FROM outbox WHERE entity_type=? AND entity_id=?")) {
            ps.setString(1, ENTITY_TYPE);
            ps.setString(2, loanId);
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

    /** Lista las publicaciones pendientes en orden de encolado. */
    public static List<Entry> listPending(SqliteDatabase db) throws SQLException {
        List<Entry> out = new ArrayList<>();
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, user_uid, entity_id FROM outbox"
                + " WHERE entity_type=? AND status=? ORDER BY id")) {
            ps.setString(1, ENTITY_TYPE);
            ps.setString(2, STATUS_PENDING);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Entry(rs.getLong(1), rs.getString(2), rs.getString(3)));
                }
            }
        }
        return out;
    }
}
