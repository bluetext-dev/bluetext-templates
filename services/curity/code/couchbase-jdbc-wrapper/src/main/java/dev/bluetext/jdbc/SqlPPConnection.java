package dev.bluetext.jdbc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * Self-contained JDBC Connection that routes all SQL through the Couchbase N1QL
 * Query service via HTTP. No external JDBC driver dependency.
 *
 * Translates PostgreSQL SQL to Couchbase SQL++:
 * 1. Double-quoted identifiers → backtick-quoted
 * 2. ON CONFLICT DO UPDATE → UPSERT INTO
 * 3. Columnar INSERT → N1QL UPSERT (KEY, VALUE) format
 * 4. Table names qualified with bucket._default
 * 5. DDL (CREATE TABLE, etc.) → silent no-op
 */
public class SqlPPConnection implements Connection {

    final String catalog;
    final String host;
    final String user;
    final String password;
    final HttpClient httpClient;
    final String authHeader;
    final URI n1qlUri;

    private boolean closed = false;
    private boolean autoCommit = true;
    private boolean readOnly = false;
    private int transactionIsolation = Connection.TRANSACTION_READ_COMMITTED;

    // --- SQL translation patterns ---

    // INSERT INTO "table" (...) VALUES (...) ON CONFLICT ("col") DO UPDATE SET ...
    private static final Pattern ON_CONFLICT = Pattern.compile(
            "(?i)INSERT\\s+INTO\\s+(\\S+)\\s*\\(([^)]+)\\)\\s*VALUES\\s*\\(([^)]+)\\)\\s*ON\\s+CONFLICT\\s*\\([^)]+\\)\\s*DO\\s+UPDATE\\s+SET\\s+(.*)",
            Pattern.DOTALL
    );

    // DDL and session statements — collections pre-created by service-config-manager
    private static final Pattern DDL_PATTERN = Pattern.compile(
            "(?i)^\\s*(CREATE\\s+(TABLE|INDEX|UNIQUE\\s+INDEX|SEQUENCE)|ALTER\\s+TABLE|DROP\\s+(TABLE|INDEX|SEQUENCE)|TRUNCATE|SET\\s+).*"
    );

    // Unqualified backtick-quoted table name after FROM/INTO/UPDATE/JOIN
    private static final Pattern UNQUALIFIED_TABLE = Pattern.compile(
            "(?i)((?:FROM|INTO|UPDATE|JOIN)\\s+)(`[^`]+`)(?!\\s*\\.)"
    );

    // Columnar INSERT/UPSERT: INSERT INTO table (`col1`, `col2`) VALUES (?, ?)
    private static final Pattern COLUMNAR_INSERT = Pattern.compile(
            "(?i)(INSERT|UPSERT)\\s+INTO\\s+(\\S+)\\s*\\(([^)]+)\\)\\s*VALUES\\s*\\(([^)]+)\\)",
            Pattern.DOTALL
    );

    public SqlPPConnection(String catalog, String host, String user, String password) throws SQLException {
        this.catalog = catalog;
        this.host = host;
        this.user = user;
        this.password = password;
        this.n1qlUri = URI.create("http://" + host + ":8093/query/service");
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // Verify connectivity
        try {
            HttpRequest ping = HttpRequest.newBuilder(n1qlUri)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Authorization", authHeader)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"statement\":\"SELECT 1\"}"))
                    .build();
            HttpResponse<String> resp = httpClient.send(ping, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new SQLException("Cannot connect to Couchbase N1QL at " + host + ":8093 (HTTP " + resp.statusCode() + ")");
            }
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("Cannot connect to Couchbase N1QL at " + host + ":8093: " + e.getMessage(), e);
        }
    }

    // --- SQL translation ---

    static boolean isDdl(String sql) {
        return sql != null && DDL_PATTERN.matcher(sql).matches();
    }

    /**
     * Translates PostgreSQL SQL to Couchbase SQL++ for N1QL execution.
     * Returns null for DDL (caller uses NoOpPreparedStatement).
     */
    String translate(String sql) {
        if (sql == null) return null;
        if (isDdl(sql)) return null;

        // Double-quoted identifiers → backtick-quoted (SQL++ style)
        sql = sql.replace('"', '`');

        // ON CONFLICT DO UPDATE → UPSERT
        var matcher = ON_CONFLICT.matcher(sql);
        if (matcher.matches()) {
            String table = matcher.group(1);
            String columns = matcher.group(2);
            String values = matcher.group(3);
            sql = "UPSERT INTO " + table + " (" + columns + ") VALUES (" + values + ")";
        }

        // Qualify unqualified table names: `sessions` → `curity`.`_default`.`sessions`
        if (catalog != null) {
            sql = UNQUALIFIED_TABLE.matcher(sql).replaceAll(
                    "$1`" + catalog + "`.`_default`.$2"
            );
        }

        // Columnar INSERT → N1QL UPSERT (KEY, VALUE) format
        sql = convertColumnarInsert(sql);

        return sql;
    }

    private String convertColumnarInsert(String sql) {
        var m = COLUMNAR_INSERT.matcher(sql);
        if (!m.matches()) return sql;

        String table = m.group(2);
        String[] cols = m.group(3).split("\\s*,\\s*");
        String[] vals = m.group(4).split("\\s*,\\s*");

        StringBuilder obj = new StringBuilder("{");
        for (int i = 0; i < cols.length && i < vals.length; i++) {
            if (i > 0) obj.append(", ");
            String key = cols[i].trim().replace("`", "");
            obj.append("\"").append(key).append("\": ").append(vals[i].trim());
        }
        obj.append("}");

        return "UPSERT INTO " + table + " (KEY, VALUE) VALUES (UUID(), " + obj + ")";
    }

    // --- Statement/PreparedStatement creation ---

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        checkClosed();
        String translated = translate(sql);
        if (translated == null) return new NoOpPreparedStatement();
        return new QueryPreparedStatement(this, translated);
    }

    @Override public PreparedStatement prepareStatement(String sql, int a, int b) throws SQLException { return prepareStatement(sql); }
    @Override public PreparedStatement prepareStatement(String sql, int a, int b, int c) throws SQLException { return prepareStatement(sql); }
    @Override public PreparedStatement prepareStatement(String sql, int a) throws SQLException { return prepareStatement(sql); }
    @Override public PreparedStatement prepareStatement(String sql, int[] a) throws SQLException { return prepareStatement(sql); }
    @Override public PreparedStatement prepareStatement(String sql, String[] a) throws SQLException { return prepareStatement(sql); }

    @Override public Statement createStatement() throws SQLException { checkClosed(); return new SqlPPStatement(this); }
    @Override public Statement createStatement(int a, int b) throws SQLException { return createStatement(); }
    @Override public Statement createStatement(int a, int b, int c) throws SQLException { return createStatement(); }

    @Override public CallableStatement prepareCall(String sql) throws SQLException { throw new SQLFeatureNotSupportedException("Stored procedures not supported"); }
    @Override public CallableStatement prepareCall(String sql, int a, int b) throws SQLException { throw new SQLFeatureNotSupportedException("Stored procedures not supported"); }
    @Override public CallableStatement prepareCall(String sql, int a, int b, int c) throws SQLException { throw new SQLFeatureNotSupportedException("Stored procedures not supported"); }

    @Override public String nativeSQL(String sql) { return translate(sql); }

    // --- Connection lifecycle (self-contained, no delegate) ---

    @Override
    public boolean isValid(int timeout) {
        if (closed) return false;
        try {
            HttpRequest ping = HttpRequest.newBuilder(n1qlUri)
                    .timeout(Duration.ofSeconds(Math.max(timeout, 1)))
                    .header("Content-Type", "application/json")
                    .header("Authorization", authHeader)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"statement\":\"SELECT 1\"}"))
                    .build();
            HttpResponse<String> resp = httpClient.send(ping, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() < 400;
        } catch (Exception e) {
            return false;
        }
    }

    @Override public void close() { closed = true; }
    @Override public boolean isClosed() { return closed; }

    @Override public void setAutoCommit(boolean ac) { autoCommit = ac; }
    @Override public boolean getAutoCommit() { return autoCommit; }
    @Override public void commit() {}
    @Override public void rollback() {}
    @Override public void rollback(Savepoint sp) {}

    @Override public void setReadOnly(boolean ro) { readOnly = ro; }
    @Override public boolean isReadOnly() { return readOnly; }

    @Override public void setTransactionIsolation(int level) { transactionIsolation = level; }
    @Override public int getTransactionIsolation() { return transactionIsolation; }

    @Override public void setCatalog(String c) {}
    @Override public String getCatalog() { return catalog; }
    @Override public void setSchema(String s) {}
    @Override public String getSchema() { return "_default"; }

    @Override public DatabaseMetaData getMetaData() { return null; }
    @Override public SQLWarning getWarnings() { return null; }
    @Override public void clearWarnings() {}
    @Override public Map<String, Class<?>> getTypeMap() { return Map.of(); }
    @Override public void setTypeMap(Map<String, Class<?>> map) {}
    @Override public void setHoldability(int h) {}
    @Override public int getHoldability() { return ResultSet.HOLD_CURSORS_OVER_COMMIT; }
    @Override public Savepoint setSavepoint() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public Savepoint setSavepoint(String name) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void releaseSavepoint(Savepoint sp) {}
    @Override public Clob createClob() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public Blob createBlob() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public NClob createNClob() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public SQLXML createSQLXML() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void setClientInfo(String name, String value) {}
    @Override public void setClientInfo(Properties properties) {}
    @Override public String getClientInfo(String name) { return null; }
    @Override public Properties getClientInfo() { return new Properties(); }
    @Override public Array createArrayOf(String typeName, Object[] elements) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public Struct createStruct(String typeName, Object[] attributes) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void abort(Executor executor) { closed = true; }
    @Override public void setNetworkTimeout(Executor executor, int milliseconds) {}
    @Override public int getNetworkTimeout() { return 0; }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("Not a wrapper"); }
    @Override public boolean isWrapperFor(Class<?> iface) { return false; }

    private void checkClosed() throws SQLException {
        if (closed) throw new SQLException("Connection is closed");
    }
}
