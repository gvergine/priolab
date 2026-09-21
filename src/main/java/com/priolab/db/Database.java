package com.priolab.db;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Thin wrapper around a SQLite database file (via the xerial sqlite-jdbc
 * driver). For now it only manages the connection lifecycle; schema and
 * queries will be added as the prioritization model takes shape.
 */
public class Database implements AutoCloseable {

    /** Current on-disk schema version written into new projects. */
    public static final int SCHEMA_VERSION = 1;

    private Connection connection;
    private Path path;

    /** Open (creating if necessary) the SQLite database at {@code path}. */
    public void open(Path path) throws SQLException {
        close();
        this.path = path;
        connection = DriverManager.getConnection("jdbc:sqlite:" + path.toString());
    }

    /**
     * Create a new project database at {@code path}: open the file and
     * initialise the {@code meta} table with the project name and schema
     * version. Safe to call on an existing file (the metadata is upserted).
     */
    public void createProject(Path path, String name) throws SQLException {
        open(path);
        try (Statement st = connection.createStatement()) {
            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)");
        }
        putMeta("name", name);
        putMeta("schema_version", Integer.toString(SCHEMA_VERSION));
    }

    /** Read the project name from the {@code meta} table, or {@code null}. */
    public String getProjectName() throws SQLException {
        if (connection == null) {
            return null;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT value FROM meta WHERE key = 'name'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private void putMeta(String key, String value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO meta(key, value) VALUES(?, ?) "
                        + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    public boolean isOpen() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    public Path getPath() {
        return path;
    }

    public Connection getConnection() {
        return connection;
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // best effort
            }
            connection = null;
        }
    }
}
