package dev.bluetext.jdbc;

import java.sql.*;

/**
 * Statement wrapper that routes all SQL through N1QL via QueryPreparedStatement.
 * DDL statements return no-op results.
 */
public class SqlPPStatement implements Statement {

    private final SqlPPConnection connection;
    private ResultSet lastResultSet;
    private int lastUpdateCount = -1;
    private boolean closed = false;

    public SqlPPStatement(SqlPPConnection connection) {
        this.connection = connection;
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        String translated = connection.translate(sql);
        if (translated == null) return new NoOpPreparedStatement.NoOpResultSet();
        QueryPreparedStatement ps = new QueryPreparedStatement(connection, translated);
        lastResultSet = ps.executeQuery();
        lastUpdateCount = -1;
        return lastResultSet;
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        String translated = connection.translate(sql);
        if (translated == null) return 0;
        QueryPreparedStatement ps = new QueryPreparedStatement(connection, translated);
        lastUpdateCount = ps.executeUpdate();
        lastResultSet = null;
        return lastUpdateCount;
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        String translated = connection.translate(sql);
        if (translated == null) return false;
        QueryPreparedStatement ps = new QueryPreparedStatement(connection, translated);
        boolean hasResultSet = ps.execute();
        if (hasResultSet) {
            lastResultSet = ps.getResultSet();
            lastUpdateCount = -1;
        } else {
            lastResultSet = null;
            lastUpdateCount = ps.getUpdateCount();
        }
        return hasResultSet;
    }

    @Override public int executeUpdate(String sql, int a) throws SQLException { return executeUpdate(sql); }
    @Override public int executeUpdate(String sql, int[] a) throws SQLException { return executeUpdate(sql); }
    @Override public int executeUpdate(String sql, String[] a) throws SQLException { return executeUpdate(sql); }
    @Override public boolean execute(String sql, int a) throws SQLException { return execute(sql); }
    @Override public boolean execute(String sql, int[] a) throws SQLException { return execute(sql); }
    @Override public boolean execute(String sql, String[] a) throws SQLException { return execute(sql); }

    @Override public ResultSet getResultSet() throws SQLException { return lastResultSet; }
    @Override public int getUpdateCount() throws SQLException { return lastUpdateCount; }
    @Override public boolean getMoreResults() throws SQLException { lastResultSet = null; lastUpdateCount = -1; return false; }
    @Override public boolean getMoreResults(int current) throws SQLException { return getMoreResults(); }
    @Override public ResultSet getGeneratedKeys() throws SQLException { return new NoOpPreparedStatement.NoOpResultSet(); }

    // --- Lifecycle ---
    @Override public void close() throws SQLException { closed = true; }
    @Override public boolean isClosed() throws SQLException { return closed; }
    @Override public Connection getConnection() throws SQLException { return connection; }

    // --- No-op bookkeeping ---
    @Override public int getMaxFieldSize() throws SQLException { return 0; }
    @Override public void setMaxFieldSize(int max) throws SQLException {}
    @Override public int getMaxRows() throws SQLException { return 0; }
    @Override public void setMaxRows(int max) throws SQLException {}
    @Override public void setEscapeProcessing(boolean enable) throws SQLException {}
    @Override public int getQueryTimeout() throws SQLException { return 0; }
    @Override public void setQueryTimeout(int seconds) throws SQLException {}
    @Override public void cancel() throws SQLException {}
    @Override public SQLWarning getWarnings() throws SQLException { return null; }
    @Override public void clearWarnings() throws SQLException {}
    @Override public void setCursorName(String name) throws SQLException {}
    @Override public void setFetchDirection(int direction) throws SQLException {}
    @Override public int getFetchDirection() throws SQLException { return ResultSet.FETCH_FORWARD; }
    @Override public void setFetchSize(int rows) throws SQLException {}
    @Override public int getFetchSize() throws SQLException { return 0; }
    @Override public int getResultSetConcurrency() throws SQLException { return ResultSet.CONCUR_READ_ONLY; }
    @Override public int getResultSetType() throws SQLException { return ResultSet.TYPE_FORWARD_ONLY; }
    @Override public void addBatch(String sql) throws SQLException {}
    @Override public void clearBatch() throws SQLException {}
    @Override public int[] executeBatch() throws SQLException { return new int[0]; }
    @Override public int getResultSetHoldability() throws SQLException { return ResultSet.HOLD_CURSORS_OVER_COMMIT; }
    @Override public void setPoolable(boolean poolable) throws SQLException {}
    @Override public boolean isPoolable() throws SQLException { return false; }
    @Override public void closeOnCompletion() throws SQLException {}
    @Override public boolean isCloseOnCompletion() throws SQLException { return false; }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("Not a wrapper"); }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return false; }
}
