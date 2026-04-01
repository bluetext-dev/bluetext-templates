package dev.bluetext.jdbc;

import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * JDBC driver wrapper that presents Couchbase as a PostgreSQL-compatible datasource.
 *
 * Curity's JDBC plugin detects the SQL dialect from the connection string prefix.
 * This wrapper accepts {@code jdbc:postgresql+couchbase://host:port/catalog} and
 * delegates to the Couchbase JDBC driver ({@code jdbc:couchbase:query://host:port}).
 *
 * Couchbase SQL++ is compatible with PostgreSQL SQL for the operations Curity uses
 * (accounts, tokens, sessions, credentials).
 */
public class CouchbasePostgresDriver implements Driver {

    private static final String PREFIX = "jdbc:postgresql+couchbase://";
    private Driver couchbaseDriver;

    static {
        try {
            DriverManager.registerDriver(new CouchbasePostgresDriver());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to register CouchbasePostgresDriver", e);
        }
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(PREFIX);
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        ensureCouchbaseDriver();
        String couchbaseUrl = "jdbc:couchbase:query://" + url.substring(PREFIX.length());
        return couchbaseDriver.connect(couchbaseUrl, info);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        ensureCouchbaseDriver();
        String couchbaseUrl = "jdbc:couchbase:query://" + url.substring(PREFIX.length());
        return couchbaseDriver.getPropertyInfo(couchbaseUrl, info);
    }

    @Override public int getMajorVersion() { return 1; }
    @Override public int getMinorVersion() { return 0; }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public Logger getParentLogger() { return Logger.getLogger("dev.bluetext.jdbc"); }

    private synchronized void ensureCouchbaseDriver() throws SQLException {
        if (couchbaseDriver == null) {
            try {
                Class.forName("com.couchbase.client.jdbc.CouchbaseDriver");
            } catch (ClassNotFoundException e) {
                throw new SQLException("Couchbase JDBC driver not found on classpath", e);
            }
            couchbaseDriver = DriverManager.getDriver("jdbc:couchbase:query://localhost");
        }
    }
}
