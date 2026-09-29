package com.myfinaces.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Cola de borrados remotos de settlements de obligaciones sobre la tabla
 * {@code outbox} existente.
 *
 * <p>Un settlement nunca existe sin su transacción enlazada: cuando el borrado
 * local es coordinado (ObligationService) o proviene de una poda por ausencia
 * remota, la entrada encolada transporta también el {@code transactionId} para
 * eliminar ambos documentos remotos en el mismo reintento.
 */
public final class ObligationPublishQueue {

    public static final String ENTITY_TYPE = "obligation_settlement";
    public static final String OP_DELETE = "DELETE";
    private static final String STATUS_PENDING = "PENDING";
    private static final int MAX_ERROR_LEN = 500;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Entry(long id, String userUid, String settlementId, String transactionId) {}

    private ObligationPublishQueue() {
    }

    /** Encola el borrado remoto del par settlement+transaction dentro de una transacción JDBC abierta. */
    public static void markPendingDelete(
        Connection c,
        String userUid,
        String settlementId,
        String transactionId
    ) throws SQLException {
        long now = Instant.now().getEpochSecond();
        try (PreparedStatement ps = c.prepareStatement(
            "DELETE FROM outbox WHERE entity_type=? AND entity_id=? AND status=?")) {
            ps.setString(1, ENTITY_TYPE);
            ps.setString(2, settlementId);
            ps.setString(3, STATUS_PENDING);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO outbox (user_uid, entity_type, entity_id, operation, payload_json,"
                + " status, attempt_count, last_error, created_at_epoch_sec, updated_at_epoch_sec)"
                + " VALUES (?,?,?,?,?,?,0,NULL,?,?)")) {
            ps.setString(1, userUid);
            ps.setString(2, ENTITY_TYPE);
            ps.setString(3, settlementId);
            ps.setString(4, OP_DELETE);
            ps.setString(5, "{\"settlementId\":\"" + settlementId
                + "\",\"transactionId\":\"" + (transactionId == null ? "" : transactionId) + "\"}");
            ps.setString(6, STATUS_PENDING);
            ps.setLong(7, now);
            ps.setLong(8, now);
            ps.executeUpdate();
        }
    }

    public static void markPendingDelete(
        SqliteDatabase db,
        String userUid,
        String settlementId,
        String transactionId
    ) throws SQLException {
        try (Connection c = db.openConnection()) {
            markPendingDelete(c, userUid, settlementId, transactionId);
        }
    }

    /** Elimina la entrada de outbox ya procesada. */
    public static void markDone(SqliteDatabase db, long outboxId) throws SQLException {
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "DELETE FROM outbox WHERE id=?")) {
            ps.setLong(1, outboxId);
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

    /** Lista los borrados pendientes en orden de encolado. */
    public static List<Entry> listPending(SqliteDatabase db) throws SQLException {
        List<Entry> out = new ArrayList<>();
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, user_uid, entity_id, payload_json FROM outbox"
                + " WHERE entity_type=? AND status=? ORDER BY id")) {
            ps.setString(1, ENTITY_TYPE);
            ps.setString(2, STATUS_PENDING);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Entry(
                        rs.getLong(1),
                        rs.getString(2),
                        rs.getString(3),
                        readTransactionId(rs.getString(4))
                    ));
                }
            }
        }
        return out;
    }

    /** Devuelve los settlementIds con un borrado remoto pendiente. */
    public static Set<String> pendingSettlementIds(SqliteDatabase db) throws SQLException {
        Set<String> out = new LinkedHashSet<>();
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT entity_id FROM outbox WHERE entity_type=? AND operation=? AND status=?")) {
            ps.setString(1, ENTITY_TYPE);
            ps.setString(2, OP_DELETE);
            ps.setString(3, STATUS_PENDING);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    private static String readTransactionId(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(payloadJson).get("transactionId");
            String value = node == null ? null : node.asText();
            return value == null || value.isBlank() ? null : value;
        } catch (Exception ignored) {
            return null;
        }
    }
}
