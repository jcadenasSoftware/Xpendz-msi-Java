package com.myfinaces.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;

public final class UserRepository {

    private final SqliteDatabase db;

    public UserRepository(SqliteDatabase db) {
        this.db = db;
    }

    public void upsert(String uid, String email) throws SQLException {
        Objects.requireNonNull(uid, "uid");
        Objects.requireNonNull(email, "email");

        long now = Instant.now().getEpochSecond();
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "INSERT INTO users (uid, email, created_at_epoch_sec, updated_at_epoch_sec) " +
            "VALUES (?, ?, ?, ?) " +
            "ON CONFLICT(uid) DO UPDATE SET email=excluded.email, updated_at_epoch_sec=excluded.updated_at_epoch_sec"
        )) {
            ps.setString(1, uid);
            ps.setString(2, email);
            ps.setLong(3, now);
            ps.setLong(4, now);
            ps.executeUpdate();
        }
    }
}
