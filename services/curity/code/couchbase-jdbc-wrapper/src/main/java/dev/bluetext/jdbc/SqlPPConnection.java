package dev.bluetext.jdbc;

import java.sql.*;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * Connection wrapper that routes all SQL through the Couchbase N1QL Query service.
 *
 * Translates PostgreSQL SQL to Couchbase SQL++:
 * 1. Double-quoted identifiers → backtick-quoted
 * 2. ON CONFLICT DO UPDATE → UPSERT INTO
 * 3. Columnar INSERT → N1QL UPSERT (KEY, VALUE) format
 * 4. Table names qualified with bucket._default
 * 5. DDL (CREATE TABLE, etc.) → silent no-op
 *
 * All reads and writes go through N1QL HTTP (port 8093) for strong consistency.
 */
public class SqlPPConnection implements Connection {

    private final Connection delegate; // kept for lifecycle methods (isValid, close, etc.)
    final String catalog;   // bucket name, e.g. "curity"
    final String host;      // Couchbase host for N1QL HTTP
    final String user;
    final String password;

    // Match: INSERT INTO "table" (...) VALUES (...) ON CONFLICT ("col") DO UPDATE SET ...
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

    public SqlPPConnection(Connection delegate, String catalog, String host, String user, String password) {
        this.delegate = delegate;
        this.catalog = catalog;
        this.host = host;
        this.user = user;
        this.password = password;
    }

    /**
     * Returns true if the SQL is DDL that should be silently no-op'd.
     */
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
        // Must come after table qualification so the table is already 3-part qualified
        sql = convertColumnarInsert(sql);

        return sql;
    }

    /**
     * Converts INSERT/UPSERT INTO table (`col1`, `col2`) VALUES (?, ?)
     * to N1QL: UPSERT INTO table (KEY, VALUE) VALUES (UUID(), {"col1": ?, "col2": ?})
     *
     * Safe for Curity's usage where VALUES are always ? placeholders.
     */
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

    // --- PreparedStatement creation: all go through QueryPreparedStatement ---

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        String translated = translate(sql);
        if (translated == null) return new NoOpPreparedStatement();
        return new QueryPreparedStatement(host, user, password, translated);
    }

    @Override public PreparedStatement prepareStatement(String sql, int a, int b) throws SQLException { return prepareStatement(sql); }
    @Override public PreparedStatement prepareStatement(String sql, int a, int b, int c) throws SQLException { return prepareStatement(sql); }
    @Override public PreparedStatement prepareStatement(String sql, int a) throws SQLException { return prepareStatement(sql); }
    @Override public PreparedStatement prepareStatement(String sql, int[] a) throws SQLException { return prepareStatement(sql); }
    @Override public PreparedStatement prepareStatement(String sql, String[] a) throws SQLException { return prepareStatement(sql); }

    // --- Statement creation ---

    @Override
    public Statement createStatement() throws SQLException {
        return new SqlPPStatement(this);
    }

    @Override public Statement createStatement(int a, int b) throws SQLException { return createStatement(); }
    @Override public Statement createStatement(int a, int b, int c) throws SQLException { return createStatement(); }

    // --- CallableStatement (stored procedures — not used by Curity) ---

    @Override public CallableStatement prepareCall(String sql) throws SQLException { return delegate.prepareCall("SELECT 1"); }
    @Override public CallableStatement prepareCall(String sql, int a, int b) throws SQLException { return delegate.prepareCall("SELECT 1", a, b); }
    @Override public CallableStatement prepareCall(String sql, int a, int b, int c) throws SQLException { return delegate.prepareCall("SELECT 1", a, b, c); }

    @Override public String nativeSQL(String sql) throws SQLException { return translate(sql); }

    // --- Lifecycle: delegate to the underlying Couchbase connection ---

    @Override public void setAutoCommit(boolean autoCommit) throws SQLException { delegate.setAutoCommit(autoCommit); }
    @Override public boolean getAutoCommit() throws SQLException { return delegate.getAutoCommit(); }
    @Override public void commit() throws SQLException { delegate.commit(); }
    @Override public void rollback() throws SQLException { delegate.rollback(); }
    @Override public void close() throws SQLException { delegate.close(); }
    @Override public boolean isClosed() throws SQLException { return delegate.isClosed(); }
    @Override public DatabaseMetaData getMetaData() throws SQLException { return delegate.getMetaData(); }
    @Override public void setReadOnly(boolean readOnly) throws SQLException { delegate.setReadOnly(readOnly); }
    @Override public boolean isReadOnly() throws SQLException { return delegate.isReadOnly(); }
    @Override public void setCatalog(String catalog) throws SQLException { delegate.setCatalog(catalog); }
    @Override public String getCatalog() throws SQLException { return delegate.getCatalog(); }
    @Override public void setTransactionIsolation(int level) throws SQLException { delegate.setTransactionIsolation(level); }
    @Override public int getTransactionIsolation() throws SQLException { return delegate.getTransactionIsolation(); }
    @Override public SQLWarning getWarnings() throws SQLException { return delegate.getWarnings(); }
    @Override public void clearWarnings() throws SQLException { delegate.clearWarnings(); }
    @Override public Map<String, Class<?>> getTypeMap() throws SQLException { return delegate.getTypeMap(); }
    @Override public void setTypeMap(Map<String, Class<?>> map) throws SQLException { delegate.setTypeMap(map); }
    @Override public void setHoldability(int holdability) throws SQLException { delegate.setHoldability(holdability); }
    @Override public int getHoldability() throws SQLException { return delegate.getHoldability(); }
    @Override public Savepoint setSavepoint() throws SQLException { return delegate.setSavepoint(); }
    @Override public Savepoint setSavepoint(String name) throws SQLException { return delegate.setSavepoint(name); }
    @Override public void rollback(Savepoint savepoint) throws SQLException { delegate.rollback(savepoint); }
    @Override public void releaseSavepoint(Savepoint savepoint) throws SQLException { delegate.releaseSavepoint(savepoint); }
    @Override public Clob createClob() throws SQLException { return delegate.createClob(); }
    @Override public Blob createBlob() throws SQLException { return delegate.createBlob(); }
    @Override public NClob createNClob() throws SQLException { return delegate.createNClob(); }
    @Override public SQLXML createSQLXML() throws SQLException { return delegate.createSQLXML(); }
    @Override public boolean isValid(int timeout) throws SQLException { return delegate.isValid(timeout); }
    @Override public void setClientInfo(String name, String value) throws SQLClientInfoException { delegate.setClientInfo(name, value); }
    @Override public void setClientInfo(Properties properties) throws SQLClientInfoException { delegate.setClientInfo(properties); }
    @Override public String getClientInfo(String name) throws SQLException { return delegate.getClientInfo(name); }
    @Override public Properties getClientInfo() throws SQLException { return delegate.getClientInfo(); }
    @Override public Array createArrayOf(String typeName, Object[] elements) throws SQLException { return delegate.createArrayOf(typeName, elements); }
    @Override public Struct createStruct(String typeName, Object[] attributes) throws SQLException { return delegate.createStruct(typeName, attributes); }
    @Override public void setSchema(String schema) throws SQLException { delegate.setSchema(schema); }
    @Override public String getSchema() throws SQLException { return delegate.getSchema(); }
    @Override public void abort(Executor executor) throws SQLException { delegate.abort(executor); }
    @Override public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException { delegate.setNetworkTimeout(executor, milliseconds); }
    @Override public int getNetworkTimeout() throws SQLException { return delegate.getNetworkTimeout(); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { return delegate.unwrap(iface); }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return delegate.isWrapperFor(iface); }
}
