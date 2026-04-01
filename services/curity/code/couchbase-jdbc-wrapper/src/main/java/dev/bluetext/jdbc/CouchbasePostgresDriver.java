package dev.bluetext.jdbc;

import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * JDBC driver wrapper that presents Couchbase as a PostgreSQL-dialect datasource to Curity.
 *
 * Curity's JDBC plugin detects the SQL dialect by splitting the connection string on ":"
 * and checking if the second token equals "postgresql". This wrapper uses the scheme
 * {@code jdbc:postgresql:couchbase://host:port/catalog} which:
 *
 * 1. Passes Curity's dialect check (second token = "postgresql")
 * 2. Is NOT accepted by the real PostgreSQL driver (which expects jdbc:postgresql://...)
 * 3. Delegates to the Couchbase JDBC driver (jdbc:couchbase:query://...)
 *
 * Couchbase SQL++ is compatible with PostgreSQL SQL for the operations Curity uses
 * (accounts, tokens, sessions, credentials).
 */
public class CouchbasePostgresDriver implements Driver {

    private static final String PREFIX = "jdbc:postgresql:couchbase://";
    private static final String CB_PREFIX = "jdbc:couchbase:analytics://";
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
        String catalog = extractCatalog(url);
        String host = extractHost(url);
        String user = extractParam(url, "user");
        String password = extractParam(url, "password");
        Connection raw = couchbaseDriver.connect(toCouchbaseUrl(url), info);
        return raw != null ? new SqlPPConnection(raw, catalog, host, user, password) : null;
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
        return CB_PREFIX + url.substring(PREFIX.length());
    }

    /**
     * Extract the host from the connection URL.
     * URL format: jdbc:postgresql:couchbase://host?params
     */
    private String extractHost(String url) {
        String after = url.substring(PREFIX.length()); // host?params
        int qIdx = after.indexOf('?');
        return qIdx >= 0 ? after.substring(0, qIdx) : after;
    }

    /**
     * Extract a query parameter from the connection URL.
     */
    private String extractParam(String url, String name) {
        int qIdx = url.indexOf('?');
        if (qIdx < 0) return null;
        for (String param : url.substring(qIdx + 1).split("&")) {
            if (param.startsWith(name + "=")) {
                return param.substring(name.length() + 1);
            }
        }
        return null;
    }

    private String extractCatalog(String url) {
        return extractParam(url, "catalog");
    }

    private synchronized void ensureCouchbaseDriver() throws SQLException {
        if (couchbaseDriver == null) {
            try {
                couchbaseDriver = (Driver) Class.forName("com.couchbase.client.jdbc.CouchbaseDriver")
                        .getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new SQLException("Couchbase JDBC driver not found on classpath", e);
            }
        }
    }
}
