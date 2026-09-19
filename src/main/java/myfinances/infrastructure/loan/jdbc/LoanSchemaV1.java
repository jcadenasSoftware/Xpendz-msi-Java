package myfinances.infrastructure.loan.jdbc;

import com.myfinaces.db.SqliteDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class LoanSchemaV1 {
    private LoanSchemaV1() {}

    public static void initialize(SqliteDatabase database) {
        try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS loan_journal_v1 (" +
                    "event_id TEXT PRIMARY KEY," +
                    "operation_id TEXT NOT NULL," +
                    "loan_id TEXT NOT NULL," +
                    "owner_id TEXT NOT NULL," +
                    "event_type TEXT NOT NULL," +
                    "event_schema_version INTEGER NOT NULL," +
                    "amount_cents INTEGER NULL," +
                    "account_id TEXT NULL," +
                    "transaction_id TEXT NULL," +
                    "note TEXT NULL," +
                    "occurred_at INTEGER NOT NULL," +
                    "recorded_at INTEGER NOT NULL," +
                    "actor_id TEXT NULL," +
                    "origin_id TEXT NULL," +
                    "payload_loan_type TEXT NULL," +
                    "payload_counterparty_name TEXT NULL," +
                    "payload_currency TEXT NULL," +
                    "payload_default_account_id TEXT NULL," +
                    "payload_notes TEXT NULL," +
                    "payload_legacy_direction TEXT NULL," +
                    "payload_legacy_source TEXT NULL," +
                    "payload_reason TEXT NULL," +
                    "payload_target_event_id TEXT NULL," +
                    "metadata_counterparty_present INTEGER NOT NULL," +
                    "metadata_counterparty_value TEXT NULL," +
                    "metadata_account_present INTEGER NOT NULL," +
                    "metadata_account_value TEXT NULL," +
                    "metadata_notes_present INTEGER NOT NULL," +
                    "metadata_notes_value TEXT NULL," +
                    "UNIQUE(owner_id, operation_id)" +
                ")"
            );
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_loan_journal_v1_aggregate " +
                    "ON loan_journal_v1(owner_id, loan_id, occurred_at, recorded_at, event_id)"
            );
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_loan_journal_v1_event " +
                    "ON loan_journal_v1(owner_id, event_id)"
            );
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS loan_snapshots_v1 (" +
                    "loan_id TEXT NOT NULL," +
                    "owner_id TEXT NOT NULL," +
                    "loan_type TEXT NOT NULL," +
                    "counterparty_name TEXT NOT NULL," +
                    "currency TEXT NOT NULL," +
                    "default_account_id TEXT NULL," +
                    "notes TEXT NULL," +
                    "principal_cents INTEGER NOT NULL," +
                    "total_paid_cents INTEGER NOT NULL," +
                    "net_balance_cents INTEGER NOT NULL," +
                    "pending_cents INTEGER NOT NULL," +
                    "overpaid_cents INTEGER NOT NULL," +
                    "status TEXT NOT NULL," +
                    "closed_at INTEGER NULL," +
                    "last_activity_at INTEGER NOT NULL," +
                    "journal_event_count INTEGER NOT NULL," +
                    "journal_fingerprint TEXT NOT NULL," +
                    "reducer_version INTEGER NOT NULL," +
                    "PRIMARY KEY(owner_id, loan_id)" +
                ")"
            );
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }
}
