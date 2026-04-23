package com.myfinaces.db;

import com.myfinaces.auth.AuthSession;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;

public final class SessionRepository {

    private final SqliteDatabase db;

    public SessionRepository(SqliteDatabase db) {
        this.db = db;
    }

    public void init() throws SQLException {
        try (Connection c = db.openConnection(); Statement st = c.createStatement()) {
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS user_session (" +
                "  id INTEGER PRIMARY KEY CHECK (id = 1)," +
                "  uid TEXT NOT NULL," +
                "  email TEXT NOT NULL," +
                "  display_name TEXT NULL," +
                "  id_token TEXT NOT NULL," +
                "  refresh_token TEXT NOT NULL," +
                "  expires_at_epoch_sec INTEGER NOT NULL," +
                "  created_at_epoch_sec INTEGER NOT NULL" +
                ")"
            );

            try {
                st.executeUpdate("ALTER TABLE user_session ADD COLUMN display_name TEXT");
            } catch (Exception ignored) {
            }
        }
    }

    public void save(AuthSession s) throws SQLException {
        long now = Instant.now().getEpochSecond();
        try (Connection c = db.openConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO user_session (id, uid, email, display_name, id_token, refresh_token, expires_at_epoch_sec, created_at_epoch_sec) " +
                "VALUES (1, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(id) DO UPDATE SET " +
                "uid=excluded.uid, email=excluded.email, display_name=excluded.display_name, id_token=excluded.id_token, refresh_token=excluded.refresh_token, " +
                "expires_at_epoch_sec=excluded.expires_at_epoch_sec, created_at_epoch_sec=excluded.created_at_epoch_sec"
            )) {
                ps.setString(1, s.uid());
                ps.setString(2, s.email());
                ps.setString(3, s.displayName());
                ps.setString(4, s.idToken());
                ps.setString(5, s.refreshToken());
                ps.setLong(6, s.expiresAtEpochSec());
                ps.setLong(7, now);
                ps.executeUpdate();
            }
        }
    }

    public Optional<AuthSession> load() throws SQLException {
        try (Connection c = db.openConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT uid, email, display_name, id_token, refresh_token, expires_at_epoch_sec FROM user_session WHERE id = 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new AuthSession(
                    rs.getString("uid"),
                    rs.getString("email"),
                    rs.getString("display_name"),
                    rs.getString("id_token"),
                    rs.getString("refresh_token"),
                    rs.getLong("expires_at_epoch_sec")
                ));
            }
        }
    }

    public void clear() throws SQLException {
        try (Connection c = db.openConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM user_session");
        }
    }
}
