package myfinances.infrastructure.loan.admin;

import com.myfinaces.db.SqliteDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import myfinances.infrastructure.loan.jdbc.LoanPersistenceException;

public final class LoanAdminStateSchemaV1 {
    private LoanAdminStateSchemaV1() {}

    public static void initialize(SqliteDatabase database) {
        try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS loan_admin_state_v1 (" +
                    "loan_id TEXT NOT NULL," +
                    "owner_id TEXT NOT NULL," +
                    "archived INTEGER NOT NULL," +
                    "archived_at_epoch_sec INTEGER NULL," +
                    "updated_at_epoch_sec INTEGER NOT NULL," +
                    "updated_by TEXT NULL," +
                    "pending_sync INTEGER NOT NULL DEFAULT 0," +
                    "PRIMARY KEY(owner_id, loan_id)" +
                ")"
            );
            try {
                statement.executeUpdate(
                    "ALTER TABLE loan_admin_state_v1 ADD COLUMN pending_sync INTEGER NOT NULL DEFAULT 0"
                );
            } catch (Exception ignored) {
            }
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_loan_admin_state_v1_owner_archived " +
                    "ON loan_admin_state_v1(owner_id, archived)"
            );
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }
}
