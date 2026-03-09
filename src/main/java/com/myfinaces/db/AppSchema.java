package com.myfinaces.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class AppSchema {

    private AppSchema() {
    }

    public static void init(SqliteDatabase db) throws SQLException {
        try (Connection c = db.openConnection(); Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");

            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS users (" +
                "  uid TEXT PRIMARY KEY," +
                "  email TEXT NOT NULL," +
                "  created_at_epoch_sec INTEGER NOT NULL," +
                "  updated_at_epoch_sec INTEGER NOT NULL" +
                ")"
            );

            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS accounts (" +
                "  id TEXT PRIMARY KEY," +
                "  user_uid TEXT NOT NULL," +
                "  name TEXT NOT NULL," +
                "  type TEXT NOT NULL," +
                "  currency TEXT NOT NULL," +
                "  created_at_epoch_sec INTEGER NOT NULL," +
                "  updated_at_epoch_sec INTEGER NOT NULL," +
                "  FOREIGN KEY(user_uid) REFERENCES users(uid) ON DELETE CASCADE" +
                ")"
            );
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_accounts_user ON accounts(user_uid)");

            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS categories (" +
                "  id TEXT PRIMARY KEY," +
                "  user_uid TEXT NOT NULL," +
                "  name TEXT NOT NULL," +
                "  parent_id TEXT NULL," +
                "  created_at_epoch_sec INTEGER NOT NULL," +
                "  updated_at_epoch_sec INTEGER NOT NULL," +
                "  FOREIGN KEY(user_uid) REFERENCES users(uid) ON DELETE CASCADE," +
                "  FOREIGN KEY(parent_id) REFERENCES categories(id) ON DELETE CASCADE" +
                ")"
            );

            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_categories_user ON categories(user_uid)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_categories_parent ON categories(parent_id)");

            // transactions: si ya existe una DB previa, puede faltar la columna account_id.
            // En ese caso, la agregamos por migración.
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS transactions (" +
                "  id TEXT PRIMARY KEY," +
                "  user_uid TEXT NOT NULL," +
                "  account_id TEXT NOT NULL," +
                "  category_id TEXT NOT NULL," +
                "  kind TEXT NOT NULL," +
                "  amount_cents INTEGER NOT NULL," +
                "  occurred_at_epoch_sec INTEGER NOT NULL," +
                "  note TEXT NULL," +
                "  created_at_epoch_sec INTEGER NOT NULL," +
                "  updated_at_epoch_sec INTEGER NOT NULL," +
                "  FOREIGN KEY(user_uid) REFERENCES users(uid) ON DELETE CASCADE," +
                "  FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE RESTRICT," +
                "  FOREIGN KEY(category_id) REFERENCES categories(id) ON DELETE RESTRICT" +
                ")"
            );

            if (!columnExists(c, "transactions", "account_id")) {
                // NOTA: SQLite no permite agregar FK por ALTER; asumimos que aún no hay data importante.
                st.executeUpdate("ALTER TABLE transactions ADD COLUMN account_id TEXT");
            }

            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tx_user ON transactions(user_uid)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tx_account ON transactions(account_id)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tx_category ON transactions(category_id)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tx_occurred ON transactions(occurred_at_epoch_sec)");

            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS transfers (" +
                "  id TEXT PRIMARY KEY," +
                "  user_uid TEXT NOT NULL," +
                "  from_account_id TEXT NOT NULL," +
                "  to_account_id TEXT NOT NULL," +
                "  amount_cents INTEGER NOT NULL," +
                "  occurred_at_epoch_sec INTEGER NOT NULL," +
                "  note TEXT NULL," +
                "  created_at_epoch_sec INTEGER NOT NULL," +
                "  updated_at_epoch_sec INTEGER NOT NULL," +
                "  FOREIGN KEY(user_uid) REFERENCES users(uid) ON DELETE CASCADE," +
                "  FOREIGN KEY(from_account_id) REFERENCES accounts(id) ON DELETE RESTRICT," +
                "  FOREIGN KEY(to_account_id) REFERENCES accounts(id) ON DELETE RESTRICT" +
                ")"
            );

            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_transfers_user ON transfers(user_uid)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_transfers_from ON transfers(from_account_id)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_transfers_to ON transfers(to_account_id)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_transfers_occurred ON transfers(occurred_at_epoch_sec)");

            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS budgets (" +
                "  id TEXT PRIMARY KEY," +
                "  user_uid TEXT NOT NULL," +
                "  month TEXT NOT NULL," +
                "  category_id TEXT NOT NULL," +
                "  limit_cents INTEGER NOT NULL," +
                "  currency TEXT NOT NULL," +
                "  created_at_epoch_sec INTEGER NOT NULL," +
                "  updated_at_epoch_sec INTEGER NOT NULL," +
                "  updated_by TEXT NULL," +
                "  FOREIGN KEY(user_uid) REFERENCES users(uid) ON DELETE CASCADE," +
                "  FOREIGN KEY(category_id) REFERENCES categories(id) ON DELETE RESTRICT" +
                ")"
            );
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_budgets_user_month ON budgets(user_uid, month)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_budgets_category ON budgets(category_id)");
            st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS ux_budgets_user_month_currency_category ON budgets(user_uid, month, currency, category_id)");

            if (!columnExists(c, "budgets", "updated_by")) {
                st.executeUpdate("ALTER TABLE budgets ADD COLUMN updated_by TEXT");
            }

            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS goals (" +
                "  id TEXT PRIMARY KEY," +
                "  user_uid TEXT NOT NULL," +
                "  name TEXT NOT NULL," +
                "  currency TEXT NOT NULL," +
                "  target_cents INTEGER NOT NULL," +
                "  target_date_epoch_sec INTEGER NOT NULL," +
                "  account_id TEXT NOT NULL," +
                "  status TEXT NOT NULL," +
                "  created_at_epoch_sec INTEGER NOT NULL," +
                "  updated_at_epoch_sec INTEGER NOT NULL," +
                "  FOREIGN KEY(user_uid) REFERENCES users(uid) ON DELETE CASCADE," +
                "  FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE RESTRICT" +
                ")"
            );
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_goals_user ON goals(user_uid)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_goals_account ON goals(account_id)");

            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS loans (" +
                "  id TEXT PRIMARY KEY," +
                "  user_uid TEXT NOT NULL," +
                "  type TEXT NOT NULL," +
                "  counterparty_name TEXT NOT NULL," +
                "  account_id TEXT NULL," +
                "  principal_cents INTEGER NOT NULL," +
                "  currency TEXT NOT NULL," +
                "  status TEXT NOT NULL," +
                "  notes TEXT NULL," +
                "  occurred_at_epoch_sec INTEGER NULL," +
                "  created_at_epoch_sec INTEGER NOT NULL," +
                "  updated_at_epoch_sec INTEGER NOT NULL," +
                "  updated_by TEXT NULL," +
                "  FOREIGN KEY(user_uid) REFERENCES users(uid) ON DELETE CASCADE" +
                ")"
            );
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_loans_user_status ON loans(user_uid, status)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_loans_user_type ON loans(user_uid, type)");

            if (!columnExists(c, "loans", "account_id")) {
                st.executeUpdate("ALTER TABLE loans ADD COLUMN account_id TEXT");
            }
            if (!columnExists(c, "loans", "occurred_at_epoch_sec")) {
                st.executeUpdate("ALTER TABLE loans ADD COLUMN occurred_at_epoch_sec INTEGER");
            }
            if (!columnExists(c, "loans", "updated_by")) {
                st.executeUpdate("ALTER TABLE loans ADD COLUMN updated_by TEXT");
            }

            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS loan_payments (" +
                "  id TEXT PRIMARY KEY," +
                "  loan_id TEXT NOT NULL," +
                "  user_uid TEXT NOT NULL," +
                "  account_id TEXT NOT NULL," +
                "  principal_cents INTEGER NOT NULL," +
                "  occurred_at_epoch_sec INTEGER NOT NULL," +
                "  linked_transaction_id TEXT NULL," +
                "  note TEXT NULL," +
                "  created_at_epoch_sec INTEGER NOT NULL," +
                "  updated_at_epoch_sec INTEGER NOT NULL," +
                "  updated_by TEXT NULL," +
                "  FOREIGN KEY(user_uid) REFERENCES users(uid) ON DELETE CASCADE," +
                "  FOREIGN KEY(loan_id) REFERENCES loans(id) ON DELETE CASCADE," +
                "  FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE RESTRICT" +
                ")"
            );
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_loan_payments_user ON loan_payments(user_uid)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_loan_payments_loan ON loan_payments(loan_id)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_loan_payments_occurred ON loan_payments(occurred_at_epoch_sec)");

            if (!columnExists(c, "loan_payments", "updated_by")) {
                st.executeUpdate("ALTER TABLE loan_payments ADD COLUMN updated_by TEXT");
            }

            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS outbox (" +
                "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  user_uid TEXT NOT NULL," +
                "  entity_type TEXT NOT NULL," +
                "  entity_id TEXT NOT NULL," +
                "  operation TEXT NOT NULL," +
                "  payload_json TEXT NOT NULL," +
                "  status TEXT NOT NULL," +
                "  attempt_count INTEGER NOT NULL," +
                "  last_error TEXT NULL," +
                "  created_at_epoch_sec INTEGER NOT NULL," +
                "  updated_at_epoch_sec INTEGER NOT NULL," +
                "  FOREIGN KEY(user_uid) REFERENCES users(uid) ON DELETE CASCADE" +
                ")"
            );

            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_outbox_user_status ON outbox(user_uid, status)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_outbox_entity ON outbox(entity_type, entity_id)");
        }
    }

    private static boolean columnExists(Connection c, String tableName, String columnName) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("PRAGMA table_info(" + tableName + ")")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    if (columnName.equalsIgnoreCase(name)) {
                        return true;
                    }
                }
                return false;
            }
        }
    }
}
