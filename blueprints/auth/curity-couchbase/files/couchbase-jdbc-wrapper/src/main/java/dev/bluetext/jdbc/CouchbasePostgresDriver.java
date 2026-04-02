package dev.bluetext.jdbc;

import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Self-contained JDBC driver that presents Couchbase as a PostgreSQL-dialect datasource.
 *
 * Curity's JDBC plugin detects the SQL dialect by splitting the connection string on ":"
 * and checking if the second token equals "postgresql". This driver uses the scheme
 * {@code jdbc:postgresql:couchbase://host?catalog=bucket&user=X&password=Y} which:
 *
 * 1. Passes Curity's dialect check (second token = "postgresql")
 * 2. Is NOT accepted by the real PostgreSQL driver (which expects jdbc:postgresql://...)
 * 3. Routes all SQL through N1QL HTTP — no Couchbase JDBC driver needed
 */
public class CouchbasePostgresDriver implements Driver {

    private static final String PREFIX = "jdbc:postgresql:couchbase://";

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
        if (!acceptsURL(url)) return null;

        String host = extractHost(url);
        String catalog = extractParam(url, "catalog");
        String user = extractParam(url, "user");
        String password = extractParam(url, "password");

        if (host == null || host.isEmpty()) throw new SQLException("Missing host in URL: " + url);
        if (user == null) throw new SQLException("Missing user parameter in URL");
        if (password == null) throw new SQLException("Missing password parameter in URL");

        return new SqlPPConnection(catalog, host, user, password);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override public int getMajorVersion() { return 2; }
    @Override public int getMinorVersion() { return 0; }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public Logger getParentLogger() { return Logger.getLogger("dev.bluetext.jdbc"); }

    private String extractHost(String url) {
        String after = url.substring(PREFIX.length());
        int qIdx = after.indexOf('?');
        return qIdx >= 0 ? after.substring(0, qIdx) : after;
    }

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
}
