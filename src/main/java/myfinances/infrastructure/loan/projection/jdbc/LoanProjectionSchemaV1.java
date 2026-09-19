package myfinances.infrastructure.loan.projection.jdbc;

import com.myfinaces.db.SqliteDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class LoanProjectionSchemaV1 {
    private LoanProjectionSchemaV1() {}

    public static void initialize(SqliteDatabase database) {
        try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS loan_payment_projection_v1 (" +
                    "source_event_id TEXT PRIMARY KEY," +
                    "operation_id TEXT NOT NULL," +
                    "owner_id TEXT NOT NULL," +
                    "loan_id TEXT NOT NULL," +
                    "account_id TEXT NULL," +
                    "transaction_id TEXT NULL," +
                    "occurred_at INTEGER NOT NULL," +
                    "amount_cents INTEGER NOT NULL," +
                    "direction TEXT NULL," +
                    "note TEXT NULL" +
                ")"
            );
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_loan_payment_projection_v1_aggregate " +
                    "ON loan_payment_projection_v1(owner_id, loan_id, occurred_at)"
            );
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS loan_summary_projection_v1 (" +
                    "loan_id TEXT NOT NULL," +
                    "owner_id TEXT NOT NULL," +
                    "counterparty TEXT NOT NULL," +
                    "loan_type TEXT NOT NULL," +
                    "currency TEXT NOT NULL," +
                    "default_account_id TEXT," +
                    "notes TEXT," +
                    "principal_cents INTEGER NOT NULL," +
                    "total_paid_cents INTEGER NOT NULL," +
                    "pending_cents INTEGER NOT NULL," +
                    "overpaid_cents INTEGER NOT NULL," +
                    "payment_count INTEGER NOT NULL DEFAULT 0," +
                    "last_payment_at INTEGER," +
                    "progress_percent INTEGER NOT NULL DEFAULT 0," +
                    "status TEXT NOT NULL," +
                    "closed_at INTEGER," +
                    "last_activity INTEGER NOT NULL," +
                    "journal_fingerprint TEXT NOT NULL," +
                    "PRIMARY KEY(owner_id, loan_id)" +
                ")"
            );
        } catch (SQLException ex) {
            throw new LoanProjectionPersistenceException(ex);
        }
    }
}
