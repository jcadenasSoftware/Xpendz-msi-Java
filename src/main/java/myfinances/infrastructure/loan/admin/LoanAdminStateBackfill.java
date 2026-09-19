package myfinances.infrastructure.loan.admin;

import com.myfinaces.db.SqliteDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import myfinances.infrastructure.loan.jdbc.LoanPersistenceException;

/**
 * Marca como pendientes de sincronización los préstamos archivados cuyo estado
 * administrativo no llegó a Firestore. No publica nada: solo prepara las filas
 * para que pushPending() las entregue por el pipeline normal.
 *
 * Versionado: la versión 1 re-marcó los archivados previos al Sprint 6G. La
 * versión 2 re-marca una vez más porque el publish original usaba un PATCH sin
 * updateMask que reemplazaba el documento y una escritura canónica posterior
 * eliminaba los campos admin, dejando pending_sync=0 sin sincronización real.
 */
public final class LoanAdminStateBackfill {
    private static final int BACKFILL_VERSION = 2;

    private LoanAdminStateBackfill() {}

    public static void run(SqliteDatabase database) {
        LoanAdminStateSchemaV1.initialize(database);
        try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS loan_admin_state_backfill_v1 (" +
                    "id INTEGER PRIMARY KEY CHECK (id = 1)," +
                    "ran_at_epoch_sec INTEGER NOT NULL," +
                    "version INTEGER NOT NULL DEFAULT 1" +
                ")"
            );
            try {
                statement.executeUpdate(
                    "ALTER TABLE loan_admin_state_backfill_v1 ADD COLUMN version INTEGER NOT NULL DEFAULT 1"
                );
            } catch (Exception ignored) {
            }
            int ranVersion = 0;
            try (ResultSet result = statement.executeQuery(
                "SELECT version FROM loan_admin_state_backfill_v1 WHERE id = 1")) {
                if (result.next()) {
                    ranVersion = result.getInt("version");
                }
            }
            if (ranVersion >= BACKFILL_VERSION) {
                return;
            }
            int marked = statement.executeUpdate(
                "UPDATE loan_admin_state_v1 SET pending_sync = 1 WHERE archived = 1 AND pending_sync = 0"
            );
            statement.executeUpdate(
                "INSERT INTO loan_admin_state_backfill_v1 (id, ran_at_epoch_sec, version) VALUES (1, " +
                    (System.currentTimeMillis() / 1000) + ", " + BACKFILL_VERSION + ") " +
                    "ON CONFLICT(id) DO UPDATE SET ran_at_epoch_sec = excluded.ran_at_epoch_sec, " +
                    "version = excluded.version"
            );
            System.out.println("[LoanAdminStateBackfill] version=" + BACKFILL_VERSION + " markedPending=" + marked);
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }
}
