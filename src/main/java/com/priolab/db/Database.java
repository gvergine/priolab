package com.priolab.db;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Thin wrapper around a SQLite database file (via the xerial sqlite-jdbc
 * driver). For now it only manages the connection lifecycle; schema and
 * queries will be added as the prioritization model takes shape.
 */
public class Database implements AutoCloseable {

    private Connection connection;
    private Path path;

    /** Open (creating if necessary) the SQLite database at {@code path}. */
    public void open(Path path) throws SQLException {
        close();
        this.path = path;
        connection = DriverManager.getConnection("jdbc:sqlite:" + path.toString());
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
