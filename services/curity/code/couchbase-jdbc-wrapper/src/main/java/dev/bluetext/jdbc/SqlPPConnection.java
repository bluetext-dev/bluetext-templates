package dev.bluetext.jdbc;

import java.sql.*;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * Connection wrapper that translates PostgreSQL SQL to Couchbase SQL++ on the fly.
 *
 * Intercepts all SQL statements and applies transformations for known
 * PostgreSQL → SQL++ incompatibilities:
 *
 * 1. ON CONFLICT DO UPDATE → UPSERT INTO
 * 2. Double-quoted identifiers → backtick-quoted
 * 3. DDL statements (CREATE TABLE, etc.) → no-op (collections pre-created by SCM)
 */
public class SqlPPConnection implements Connection {

    private final Connection delegate;
    private final String catalog; // bucket name, e.g. "curity"
    private final String host;    // Couchbase host for N1QL HTTP calls
    private final String user;
    private final String password;

    // Match: INSERT INTO "table" (...) VALUES (...) ON CONFLICT ("col") DO UPDATE SET ...
    private static final Pattern ON_CONFLICT = Pattern.compile(
            "(?i)INSERT\\s+INTO\\s+(\\S+)\\s*\\(([^)]+)\\)\\s*VALUES\\s*\\(([^)]+)\\)\\s*ON\\s+CONFLICT\\s*\\([^)]+\\)\\s*DO\\s+UPDATE\\s+SET\\s+(.*)",
            Pattern.DOTALL
    );

    // DDL and session statements that Couchbase Analytics doesn't support.
    // Collections are pre-created by service-config-manager, so these are safe to ignore.
    private static final Pattern DDL_PATTERN = Pattern.compile(
            "(?i)^\\s*(CREATE\\s+(TABLE|INDEX|UNIQUE\\s+INDEX|SEQUENCE)|ALTER\\s+TABLE|DROP\\s+(TABLE|INDEX|SEQUENCE)|TRUNCATE|SET\\s+).*"
    );

    // DML write statements that must go through N1QL Query service (Analytics is read-only)
    private static final Pattern DML_WRITE = Pattern.compile(
            "(?i)^\\s*(INSERT|UPDATE|DELETE|UPSERT|MERGE)\\s+.*"
    );

    // Matches a backtick-quoted table name that is NOT already qualified (no dots before it).
    // Captures: FROM/INTO/UPDATE/JOIN followed by a single backtick-quoted identifier.
    private static final Pattern UNQUALIFIED_TABLE = Pattern.compile(
            "(?i)((?:FROM|INTO|UPDATE|JOIN)\\s+)(`[^`]+`)(?!\\s*\\.)"
    );

    public SqlPPConnection(Connection delegate, String catalog, String host, String user, String password) {
        this.delegate = delegate;
        this.catalog = catalog;
        this.host = host;
        this.user = user;
        this.password = password;
    }

    /**
     * Returns true if the SQL is a DDL/session statement that should be no-op'd.
     * Package-private so SqlPPStatement can reuse it.
     */
    static boolean isDdl(String sql) {
        return sql != null && DDL_PATTERN.matcher(sql).matches();
    }

    /**
     * Returns true if the SQL is a DML write (INSERT/UPDATE/DELETE/UPSERT).
     * These must go through the N1QL Query service since Analytics is read-only.
     */
    static boolean isDmlWrite(String sql) {
        return sql != null && DML_WRITE.matcher(sql).matches();
    }

    /**
     * Translates PostgreSQL SQL to Couchbase SQL++ for Analytics (reads).
     * Returns null for DDL statements (caller should use NoOpPreparedStatement).
     * Package-private so SqlPPStatement can reuse it.
     */
    String translateSql(String sql) {
        if (sql == null) return null;
        if (isDdl(sql)) return null;

        // Replace double-quoted identifiers with backtick-quoted (SQL++ style)
        sql = sql.replace('"', '`');

        // Qualify unqualified table names with bucket._default
        if (catalog != null) {
            sql = UNQUALIFIED_TABLE.matcher(sql).replaceAll(
                    "$1`" + catalog + "`.`_default`.$2"
            );
        }

        return sql;
    }

    /**
     * Translates PostgreSQL SQL to N1QL for writes.
     * Converts columnar INSERT to N1QL UPSERT with KEY/VALUE format.
     */
    String translateForN1ql(String sql) {
        if (sql == null) return null;

        // Replace double-quoted identifiers with backtick-quoted
        sql = sql.replace('"', '`');

        // Transform ON CONFLICT DO UPDATE → UPSERT
        var matcher = ON_CONFLICT.matcher(sql);
        if (matcher.matches()) {
            String table = matcher.group(1);
            String columns = matcher.group(2);
            String values = matcher.group(3);
            sql = "UPSERT INTO " + table + " (" + columns + ") VALUES (" + values + ")";
        }

        // Qualify unqualified table names
        if (catalog != null) {
            sql = UNQUALIFIED_TABLE.matcher(sql).replaceAll(
                    "$1`" + catalog + "`.`_default`.$2"
            );
        }

        // Convert columnar INSERT INTO table (col1, col2) VALUES (v1, v2)
        // to N1QL: UPSERT INTO table (KEY UUID(), VALUE {"col1": v1, "col2": v2})
        sql = convertColumnarInsert(sql);

        return sql;
    }

    /**
     * Converts columnar INSERT/UPSERT INTO table (`col1`, `col2`) VALUES (?, ?)
     * to N1QL: UPSERT INTO table (KEY UUID(), VALUE {"col1": ?, "col2": ?})
     */
    private static final Pattern COLUMNAR_INSERT = Pattern.compile(
            "(?i)(INSERT|UPSERT)\\s+INTO\\s+(\\S+)\\s*\\(([^)]+)\\)\\s*VALUES\\s*\\(([^)]+)\\)",
            Pattern.DOTALL
    );

    private String convertColumnarInsert(String sql) {
        var m = COLUMNAR_INSERT.matcher(sql);
        if (!m.matches()) return sql;

        String table = m.group(2);
        String[] cols = m.group(3).split("\\s*,\\s*");
        String[] vals = m.group(4).split("\\s*,\\s*");

        // Build JSON object: {"col1": val1, "col2": val2, ...}
        StringBuilder obj = new StringBuilder("{");
        for (int i = 0; i < cols.length && i < vals.length; i++) {
            if (i > 0) obj.append(", ");
            // Strip backticks for JSON key
            String key = cols[i].trim().replace("`", "");
            obj.append("\"").append(key).append("\": ").append(vals[i].trim());
        }
        obj.append("}");

        return "UPSERT INTO " + table + " (KEY, VALUE) VALUES (UUID(), " + obj + ")";
    }

    @Override
    public Statement createStatement() throws SQLException {
        return new SqlPPStatement(delegate.createStatement(), this);
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        if (isDdl(sql)) return new NoOpPreparedStatement();
        if (isDmlWrite(sql)) return new QueryPreparedStatement(host, user, password, translateForN1ql(sql));
        return delegate.prepareStatement(translateSql(sql));
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        String translated = translateSql(sql);
        if (translated == null) return delegate.prepareCall("SELECT 1");
        return delegate.prepareCall(translated);
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        return delegate.nativeSQL(translateSql(sql));
    }

    @Override public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException { return new SqlPPStatement(delegate.createStatement(resultSetType, resultSetConcurrency), this); }
    @Override public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException { if (isDdl(sql)) return new NoOpPreparedStatement(); if (isDmlWrite(sql)) return new QueryPreparedStatement(host, user, password, translateForN1ql(sql)); return delegate.prepareStatement(translateSql(sql), resultSetType, resultSetConcurrency); }
    @Override public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException { String t = translateSql(sql); if (t == null) return delegate.prepareCall("SELECT 1", resultSetType, resultSetConcurrency); return delegate.prepareCall(t, resultSetType, resultSetConcurrency); }
    @Override public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException { return new SqlPPStatement(delegate.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability), this); }
    @Override public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException { if (isDdl(sql)) return new NoOpPreparedStatement(); if (isDmlWrite(sql)) return new QueryPreparedStatement(host, user, password, translateForN1ql(sql)); return delegate.prepareStatement(translateSql(sql), resultSetType, resultSetConcurrency, resultSetHoldability); }
    @Override public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException { String t = translateSql(sql); if (t == null) return delegate.prepareCall("SELECT 1", resultSetType, resultSetConcurrency, resultSetHoldability); return delegate.prepareCall(t, resultSetType, resultSetConcurrency, resultSetHoldability); }
    @Override public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException { if (isDdl(sql)) return new NoOpPreparedStatement(); if (isDmlWrite(sql)) return new QueryPreparedStatement(host, user, password, translateForN1ql(sql)); return delegate.prepareStatement(translateSql(sql), autoGeneratedKeys); }
    @Override public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException { if (isDdl(sql)) return new NoOpPreparedStatement(); if (isDmlWrite(sql)) return new QueryPreparedStatement(host, user, password, translateForN1ql(sql)); return delegate.prepareStatement(translateSql(sql), columnIndexes); }
    @Override public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException { if (isDdl(sql)) return new NoOpPreparedStatement(); if (isDmlWrite(sql)) return new QueryPreparedStatement(host, user, password, translateForN1ql(sql)); return delegate.prepareStatement(translateSql(sql), columnNames); }

    // All other Connection methods delegate directly
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
