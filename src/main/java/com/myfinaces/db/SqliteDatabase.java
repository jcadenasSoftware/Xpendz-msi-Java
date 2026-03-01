package com.myfinaces.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class SqliteDatabase {

    private final String jdbcUrl;

    public SqliteDatabase(Path dbFile) {
        this.jdbcUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath();
    }

    public Connection openConnection() throws SQLException {
        Connection c = DriverManager.getConnection(jdbcUrl);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
        }
        return c;
    }

    public static SqliteDatabase defaultDatabase() throws Exception {
        Path dir = Path.of(System.getProperty("user.home"), ".myfinances");
        Files.createDirectories(dir);
        Path dbFile = dir.resolve("myfinances.db");
        return new SqliteDatabase(dbFile);
    }
}
