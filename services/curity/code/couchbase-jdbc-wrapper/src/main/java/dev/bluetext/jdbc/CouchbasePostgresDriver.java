package dev.bluetext.jdbc;

import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * JDBC driver wrapper that presents Couchbase as a PostgreSQL-compatible datasource.
 *
 * Curity's JDBC plugin detects the SQL dialect from the connection string prefix.
 * This wrapper accepts {@code jdbc:postgresql://host:8093/...} and delegates to
 * the Couchbase JDBC driver ({@code jdbc:couchbase:query://host:8093/...}).
 *
 * It intercepts only connections to port 8093 (Couchbase query service) — standard
 * PostgreSQL connections (port 5432) pass through to the real PostgreSQL driver.
 *
 * Couchbase SQL++ is compatible with PostgreSQL SQL for the operations Curity uses
 * (accounts, tokens, sessions, credentials).
 */
public class CouchbasePostgresDriver implements Driver {

    private static final String PG_PREFIX = "jdbc:postgresql://";
    private static final String CB_PREFIX = "jdbc:couchbase:query://";
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
        return url != null && url.startsWith(PG_PREFIX) && url.contains(":8093");
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        ensureCouchbaseDriver();
        return couchbaseDriver.connect(toCouchbaseUrl(url), info);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        ensureCouchbaseDriver();
        return couchbaseDriver.getPropertyInfo(toCouchbaseUrl(url), info);
    }

    @Override public int getMajorVersion() { return 1; }
    @Override public int getMinorVersion() { return 0; }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public Logger getParentLogger() { return Logger.getLogger("dev.bluetext.jdbc"); }

    private String toCouchbaseUrl(String url) {
        return CB_PREFIX + url.substring(PG_PREFIX.length());
    }

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
