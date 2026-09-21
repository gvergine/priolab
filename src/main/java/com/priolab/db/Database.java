package com.priolab.db;

import com.priolab.model.ItemScore;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;

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
        ensureSchema();
        putMeta("name", name);
        putMeta("schema_version", Integer.toString(SCHEMA_VERSION));
    }

    /**
     * Create the tables PrioLab relies on if they do not already exist. Called
     * for freshly created projects and when opening an arbitrary SQLite file so
     * it can be treated as a project.
     */
    public void ensureSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)");
            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS connector_settings ("
                            + "connector TEXT NOT NULL, key TEXT NOT NULL, value TEXT, "
                            + "PRIMARY KEY (connector, key))");
            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS item_scores ("
                            + "id TEXT PRIMARY KEY, "
                            + "business_value INTEGER, time_criticality INTEGER, "
                            + "risk_reduction INTEGER, job_size INTEGER)");
        }
    }

    /** Read the project name from the {@code meta} table, or {@code null}. */
    public String getProjectName() throws SQLException {
        return getMeta("name");
    }

    /** Read a value from the {@code meta} table, or {@code null} if absent. */
    public String getMeta(String key) throws SQLException {
        if (connection == null) {
            return null;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT value FROM meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Insert or update a value in the {@code meta} table. */
    public void putMeta(String key, String value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO meta(key, value) VALUES(?, ?) "
                        + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    /**
     * Read every stored connector setting, grouped by connector name:
     * {@code connector -> (key -> value)}. Returns an empty map if the database
     * is not open.
     */
    public Map<String, Map<String, String>> getAllConnectorSettings() throws SQLException {
        Map<String, Map<String, String>> out = new HashMap<>();
        if (connection == null) {
            return out;
        }
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT connector, key, value FROM connector_settings")) {
            while (rs.next()) {
                out.computeIfAbsent(rs.getString(1), k -> new HashMap<>())
                        .put(rs.getString(2), rs.getString(3));
            }
        }
        return out;
    }

    /** Insert or update a single connector setting value. */
    public void putConnectorSetting(String connector, String key, String value)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO connector_settings(connector, key, value) VALUES(?, ?, ?) "
                        + "ON CONFLICT(connector, key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, connector);
            ps.setString(2, key);
            ps.setString(3, value);
            ps.executeUpdate();
        }
    }

    /** Remove a stored connector setting, if present. */
    public void deleteConnectorSetting(String connector, String key) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM connector_settings WHERE connector = ? AND key = ?")) {
            ps.setString(1, connector);
            ps.setString(2, key);
            ps.executeUpdate();
        }
    }

    /**
     * Read every stored item score, keyed by item id. Returns an empty map if
     * the database is not open.
     */
    public Map<String, ItemScore> getAllItemScores() throws SQLException {
        Map<String, ItemScore> out = new HashMap<>();
        if (connection == null) {
            return out;
        }
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, business_value, time_criticality, risk_reduction, job_size "
                             + "FROM item_scores")) {
            while (rs.next()) {
                out.put(rs.getString(1), new ItemScore(
                        nullableInt(rs, 2), nullableInt(rs, 3),
                        nullableInt(rs, 4), nullableInt(rs, 5)));
            }
        }
        return out;
    }

    /** Insert or update the stored score for one item id. */
    public void putItemScore(String id, ItemScore score) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO item_scores(id, business_value, time_criticality, "
                        + "risk_reduction, job_size) VALUES(?, ?, ?, ?, ?) "
                        + "ON CONFLICT(id) DO UPDATE SET "
                        + "business_value = excluded.business_value, "
                        + "time_criticality = excluded.time_criticality, "
                        + "risk_reduction = excluded.risk_reduction, "
                        + "job_size = excluded.job_size")) {
            ps.setString(1, id);
            setNullableInt(ps, 2, score.businessValue());
            setNullableInt(ps, 3, score.timeCriticality());
            setNullableInt(ps, 4, score.riskReduction());
            setNullableInt(ps, 5, score.jobSize());
            ps.executeUpdate();
        }
    }

    /** Remove the stored score for one item id, if present. */
    public void deleteItemScore(String id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM item_scores WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    /** Read an INTEGER column that may be SQL NULL. */
    private static Integer nullableInt(ResultSet rs, int column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    /** Bind an INTEGER parameter that may be {@code null}. */
    private static void setNullableInt(PreparedStatement ps, int index, Integer value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
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
