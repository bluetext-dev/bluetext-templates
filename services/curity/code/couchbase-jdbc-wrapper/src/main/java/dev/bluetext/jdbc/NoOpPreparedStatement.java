package dev.bluetext.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.util.Calendar;

/**
 * A no-op PreparedStatement that silently succeeds for DDL statements
 * (CREATE TABLE, CREATE INDEX, ALTER TABLE, etc.) that Couchbase Analytics
 * doesn't support. Collections are pre-created by service-config-manager,
 * so these DDL statements from Curity's PostgreSQL dialect can be safely ignored.
 */
public class NoOpPreparedStatement implements PreparedStatement {

    private boolean closed = false;

    // --- Execute methods return no-op results ---
    @Override public ResultSet executeQuery() throws SQLException { return new NoOpResultSet(); }
    @Override public int executeUpdate() throws SQLException { return 0; }
    @Override public boolean execute() throws SQLException { return false; }
    @Override public ResultSet executeQuery(String sql) throws SQLException { return new NoOpResultSet(); }
    @Override public int executeUpdate(String sql) throws SQLException { return 0; }
    @Override public boolean execute(String sql) throws SQLException { return false; }
    @Override public int executeUpdate(String sql, int k) throws SQLException { return 0; }
    @Override public int executeUpdate(String sql, int[] i) throws SQLException { return 0; }
    @Override public int executeUpdate(String sql, String[] c) throws SQLException { return 0; }
    @Override public boolean execute(String sql, int k) throws SQLException { return false; }
    @Override public boolean execute(String sql, int[] i) throws SQLException { return false; }
    @Override public boolean execute(String sql, String[] c) throws SQLException { return false; }
    @Override public int[] executeBatch() throws SQLException { return new int[0]; }

    // --- Parameter setters are all no-ops ---
    @Override public void setNull(int i, int t) throws SQLException {}
    @Override public void setBoolean(int i, boolean v) throws SQLException {}
    @Override public void setByte(int i, byte v) throws SQLException {}
    @Override public void setShort(int i, short v) throws SQLException {}
    @Override public void setInt(int i, int v) throws SQLException {}
    @Override public void setLong(int i, long v) throws SQLException {}
    @Override public void setFloat(int i, float v) throws SQLException {}
    @Override public void setDouble(int i, double v) throws SQLException {}
    @Override public void setBigDecimal(int i, BigDecimal v) throws SQLException {}
    @Override public void setString(int i, String v) throws SQLException {}
    @Override public void setBytes(int i, byte[] v) throws SQLException {}
    @Override public void setDate(int i, Date v) throws SQLException {}
    @Override public void setTime(int i, Time v) throws SQLException {}
    @Override public void setTimestamp(int i, Timestamp v) throws SQLException {}
    @Override public void setAsciiStream(int i, InputStream v, int len) throws SQLException {}
    @SuppressWarnings("deprecation")
    @Override public void setUnicodeStream(int i, InputStream v, int len) throws SQLException {}
    @Override public void setBinaryStream(int i, InputStream v, int len) throws SQLException {}
    @Override public void setObject(int i, Object v, int t) throws SQLException {}
    @Override public void setObject(int i, Object v) throws SQLException {}
    @Override public void setCharacterStream(int i, Reader v, int len) throws SQLException {}
    @Override public void setRef(int i, Ref v) throws SQLException {}
    @Override public void setBlob(int i, Blob v) throws SQLException {}
    @Override public void setClob(int i, Clob v) throws SQLException {}
    @Override public void setArray(int i, Array v) throws SQLException {}
    @Override public void setDate(int i, Date v, Calendar c) throws SQLException {}
    @Override public void setTime(int i, Time v, Calendar c) throws SQLException {}
    @Override public void setTimestamp(int i, Timestamp v, Calendar c) throws SQLException {}
    @Override public void setNull(int i, int t, String tn) throws SQLException {}
    @Override public void setURL(int i, URL v) throws SQLException {}
    @Override public void setRowId(int i, RowId v) throws SQLException {}
    @Override public void setNString(int i, String v) throws SQLException {}
    @Override public void setNCharacterStream(int i, Reader v, long len) throws SQLException {}
    @Override public void setNClob(int i, NClob v) throws SQLException {}
    @Override public void setClob(int i, Reader v, long len) throws SQLException {}
    @Override public void setBlob(int i, InputStream v, long len) throws SQLException {}
    @Override public void setNClob(int i, Reader v, long len) throws SQLException {}
    @Override public void setSQLXML(int i, SQLXML v) throws SQLException {}
    @Override public void setObject(int i, Object v, int t, int s) throws SQLException {}
    @Override public void setAsciiStream(int i, InputStream v, long len) throws SQLException {}
    @Override public void setBinaryStream(int i, InputStream v, long len) throws SQLException {}
    @Override public void setCharacterStream(int i, Reader v, long len) throws SQLException {}
    @Override public void setAsciiStream(int i, InputStream v) throws SQLException {}
    @Override public void setBinaryStream(int i, InputStream v) throws SQLException {}
    @Override public void setCharacterStream(int i, Reader v) throws SQLException {}
    @Override public void setNCharacterStream(int i, Reader v) throws SQLException {}
    @Override public void setClob(int i, Reader v) throws SQLException {}
    @Override public void setBlob(int i, InputStream v) throws SQLException {}
    @Override public void setNClob(int i, Reader v) throws SQLException {}

    // --- Metadata ---
    @Override public ResultSetMetaData getMetaData() throws SQLException { return null; }
    @Override public ParameterMetaData getParameterMetaData() throws SQLException { return null; }

    // --- Statement bookkeeping ---
    @Override public void clearParameters() throws SQLException {}
    @Override public void addBatch() throws SQLException {}
    @Override public void addBatch(String sql) throws SQLException {}
    @Override public void clearBatch() throws SQLException {}
    @Override public void close() throws SQLException { closed = true; }
    @Override public boolean isClosed() throws SQLException { return closed; }
    @Override public void cancel() throws SQLException {}

    // --- Result state ---
    @Override public ResultSet getResultSet() throws SQLException { return null; }
    @Override public int getUpdateCount() throws SQLException { return -1; }
    @Override public boolean getMoreResults() throws SQLException { return false; }
    @Override public boolean getMoreResults(int current) throws SQLException { return false; }
    @Override public ResultSet getGeneratedKeys() throws SQLException { return new NoOpResultSet(); }
    @Override public int getMaxFieldSize() throws SQLException { return 0; }
    @Override public void setMaxFieldSize(int max) throws SQLException {}
    @Override public int getMaxRows() throws SQLException { return 0; }
    @Override public void setMaxRows(int max) throws SQLException {}
    @Override public void setEscapeProcessing(boolean enable) throws SQLException {}
    @Override public int getQueryTimeout() throws SQLException { return 0; }
    @Override public void setQueryTimeout(int seconds) throws SQLException {}
    @Override public SQLWarning getWarnings() throws SQLException { return null; }
    @Override public void clearWarnings() throws SQLException {}
    @Override public void setCursorName(String name) throws SQLException {}
    @Override public void setFetchDirection(int direction) throws SQLException {}
    @Override public int getFetchDirection() throws SQLException { return ResultSet.FETCH_FORWARD; }
    @Override public void setFetchSize(int rows) throws SQLException {}
    @Override public int getFetchSize() throws SQLException { return 0; }
    @Override public int getResultSetConcurrency() throws SQLException { return ResultSet.CONCUR_READ_ONLY; }
    @Override public int getResultSetType() throws SQLException { return ResultSet.TYPE_FORWARD_ONLY; }
    @Override public Connection getConnection() throws SQLException { return null; }
    @Override public int getResultSetHoldability() throws SQLException { return ResultSet.HOLD_CURSORS_OVER_COMMIT; }
    @Override public void setPoolable(boolean poolable) throws SQLException {}
    @Override public boolean isPoolable() throws SQLException { return false; }
    @Override public void closeOnCompletion() throws SQLException {}
    @Override public boolean isCloseOnCompletion() throws SQLException { return false; }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("Not a wrapper"); }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return false; }

    /**
     * Empty ResultSet for DDL queries that shouldn't return data.
     */
    static class NoOpResultSet implements ResultSet {
        private boolean closed = false;
        @Override public boolean next() throws SQLException { return false; }
        @Override public void close() throws SQLException { closed = true; }
        @Override public boolean isClosed() throws SQLException { return closed; }
        @Override public boolean wasNull() throws SQLException { return false; }

        // All getters return defaults
        @Override public String getString(int i) throws SQLException { return null; }
        @Override public boolean getBoolean(int i) throws SQLException { return false; }
        @Override public byte getByte(int i) throws SQLException { return 0; }
        @Override public short getShort(int i) throws SQLException { return 0; }
        @Override public int getInt(int i) throws SQLException { return 0; }
        @Override public long getLong(int i) throws SQLException { return 0; }
        @Override public float getFloat(int i) throws SQLException { return 0; }
        @Override public double getDouble(int i) throws SQLException { return 0; }
        @SuppressWarnings("deprecation")
        @Override public BigDecimal getBigDecimal(int i, int s) throws SQLException { return null; }
        @Override public byte[] getBytes(int i) throws SQLException { return null; }
        @Override public Date getDate(int i) throws SQLException { return null; }
        @Override public Time getTime(int i) throws SQLException { return null; }
        @Override public Timestamp getTimestamp(int i) throws SQLException { return null; }
        @Override public InputStream getAsciiStream(int i) throws SQLException { return null; }
        @SuppressWarnings("deprecation")
        @Override public InputStream getUnicodeStream(int i) throws SQLException { return null; }
        @Override public InputStream getBinaryStream(int i) throws SQLException { return null; }
        @Override public String getString(String c) throws SQLException { return null; }
        @Override public boolean getBoolean(String c) throws SQLException { return false; }
        @Override public byte getByte(String c) throws SQLException { return 0; }
        @Override public short getShort(String c) throws SQLException { return 0; }
        @Override public int getInt(String c) throws SQLException { return 0; }
        @Override public long getLong(String c) throws SQLException { return 0; }
        @Override public float getFloat(String c) throws SQLException { return 0; }
        @Override public double getDouble(String c) throws SQLException { return 0; }
        @SuppressWarnings("deprecation")
        @Override public BigDecimal getBigDecimal(String c, int s) throws SQLException { return null; }
        @Override public byte[] getBytes(String c) throws SQLException { return null; }
        @Override public Date getDate(String c) throws SQLException { return null; }
        @Override public Time getTime(String c) throws SQLException { return null; }
        @Override public Timestamp getTimestamp(String c) throws SQLException { return null; }
        @Override public InputStream getAsciiStream(String c) throws SQLException { return null; }
        @SuppressWarnings("deprecation")
        @Override public InputStream getUnicodeStream(String c) throws SQLException { return null; }
        @Override public InputStream getBinaryStream(String c) throws SQLException { return null; }
        @Override public SQLWarning getWarnings() throws SQLException { return null; }
        @Override public void clearWarnings() throws SQLException {}
        @Override public String getCursorName() throws SQLException { return null; }
        @Override public ResultSetMetaData getMetaData() throws SQLException { return null; }
        @Override public Object getObject(int i) throws SQLException { return null; }
        @Override public Object getObject(String c) throws SQLException { return null; }
        @Override public int findColumn(String c) throws SQLException { throw new SQLException("Empty result set"); }
        @Override public Reader getCharacterStream(int i) throws SQLException { return null; }
        @Override public Reader getCharacterStream(String c) throws SQLException { return null; }
        @Override public BigDecimal getBigDecimal(int i) throws SQLException { return null; }
        @Override public BigDecimal getBigDecimal(String c) throws SQLException { return null; }
        @Override public boolean isBeforeFirst() throws SQLException { return false; }
        @Override public boolean isAfterLast() throws SQLException { return false; }
        @Override public boolean isFirst() throws SQLException { return false; }
        @Override public boolean isLast() throws SQLException { return false; }
        @Override public void beforeFirst() throws SQLException {}
        @Override public void afterLast() throws SQLException {}
        @Override public boolean first() throws SQLException { return false; }
        @Override public boolean last() throws SQLException { return false; }
        @Override public int getRow() throws SQLException { return 0; }
        @Override public boolean absolute(int row) throws SQLException { return false; }
        @Override public boolean relative(int rows) throws SQLException { return false; }
        @Override public boolean previous() throws SQLException { return false; }
        @Override public void setFetchDirection(int d) throws SQLException {}
        @Override public int getFetchDirection() throws SQLException { return FETCH_FORWARD; }
        @Override public void setFetchSize(int r) throws SQLException {}
        @Override public int getFetchSize() throws SQLException { return 0; }
        @Override public int getType() throws SQLException { return TYPE_FORWARD_ONLY; }
        @Override public int getConcurrency() throws SQLException { return CONCUR_READ_ONLY; }
        @Override public boolean rowUpdated() throws SQLException { return false; }
        @Override public boolean rowInserted() throws SQLException { return false; }
        @Override public boolean rowDeleted() throws SQLException { return false; }
        @Override public void updateNull(int i) throws SQLException {}
        @Override public void updateBoolean(int i, boolean v) throws SQLException {}
        @Override public void updateByte(int i, byte v) throws SQLException {}
        @Override public void updateShort(int i, short v) throws SQLException {}
        @Override public void updateInt(int i, int v) throws SQLException {}
        @Override public void updateLong(int i, long v) throws SQLException {}
        @Override public void updateFloat(int i, float v) throws SQLException {}
        @Override public void updateDouble(int i, double v) throws SQLException {}
        @Override public void updateBigDecimal(int i, BigDecimal v) throws SQLException {}
        @Override public void updateString(int i, String v) throws SQLException {}
        @Override public void updateBytes(int i, byte[] v) throws SQLException {}
        @Override public void updateDate(int i, Date v) throws SQLException {}
        @Override public void updateTime(int i, Time v) throws SQLException {}
        @Override public void updateTimestamp(int i, Timestamp v) throws SQLException {}
        @Override public void updateAsciiStream(int i, InputStream v, int l) throws SQLException {}
        @Override public void updateBinaryStream(int i, InputStream v, int l) throws SQLException {}
        @Override public void updateCharacterStream(int i, Reader v, int l) throws SQLException {}
        @Override public void updateObject(int i, Object v, int s) throws SQLException {}
        @Override public void updateObject(int i, Object v) throws SQLException {}
        @Override public void updateNull(String c) throws SQLException {}
        @Override public void updateBoolean(String c, boolean v) throws SQLException {}
        @Override public void updateByte(String c, byte v) throws SQLException {}
        @Override public void updateShort(String c, short v) throws SQLException {}
        @Override public void updateInt(String c, int v) throws SQLException {}
        @Override public void updateLong(String c, long v) throws SQLException {}
        @Override public void updateFloat(String c, float v) throws SQLException {}
        @Override public void updateDouble(String c, double v) throws SQLException {}
        @Override public void updateBigDecimal(String c, BigDecimal v) throws SQLException {}
        @Override public void updateString(String c, String v) throws SQLException {}
        @Override public void updateBytes(String c, byte[] v) throws SQLException {}
        @Override public void updateDate(String c, Date v) throws SQLException {}
        @Override public void updateTime(String c, Time v) throws SQLException {}
        @Override public void updateTimestamp(String c, Timestamp v) throws SQLException {}
        @Override public void updateAsciiStream(String c, InputStream v, int l) throws SQLException {}
        @Override public void updateBinaryStream(String c, InputStream v, int l) throws SQLException {}
        @Override public void updateCharacterStream(String c, Reader v, int l) throws SQLException {}
        @Override public void updateObject(String c, Object v, int s) throws SQLException {}
        @Override public void updateObject(String c, Object v) throws SQLException {}
        @Override public void insertRow() throws SQLException {}
        @Override public void updateRow() throws SQLException {}
        @Override public void deleteRow() throws SQLException {}
        @Override public void refreshRow() throws SQLException {}
        @Override public void cancelRowUpdates() throws SQLException {}
        @Override public void moveToInsertRow() throws SQLException {}
        @Override public void moveToCurrentRow() throws SQLException {}
        @Override public Statement getStatement() throws SQLException { return null; }
        @Override public Object getObject(int i, java.util.Map<String, Class<?>> m) throws SQLException { return null; }
        @Override public Ref getRef(int i) throws SQLException { return null; }
        @Override public Blob getBlob(int i) throws SQLException { return null; }
        @Override public Clob getClob(int i) throws SQLException { return null; }
        @Override public Array getArray(int i) throws SQLException { return null; }
        @Override public Object getObject(String c, java.util.Map<String, Class<?>> m) throws SQLException { return null; }
        @Override public Ref getRef(String c) throws SQLException { return null; }
        @Override public Blob getBlob(String c) throws SQLException { return null; }
        @Override public Clob getClob(String c) throws SQLException { return null; }
        @Override public Array getArray(String c) throws SQLException { return null; }
        @Override public Date getDate(int i, Calendar cal) throws SQLException { return null; }
        @Override public Date getDate(String c, Calendar cal) throws SQLException { return null; }
        @Override public Time getTime(int i, Calendar cal) throws SQLException { return null; }
        @Override public Time getTime(String c, Calendar cal) throws SQLException { return null; }
        @Override public Timestamp getTimestamp(int i, Calendar cal) throws SQLException { return null; }
        @Override public Timestamp getTimestamp(String c, Calendar cal) throws SQLException { return null; }
        @Override public URL getURL(int i) throws SQLException { return null; }
        @Override public URL getURL(String c) throws SQLException { return null; }
        @Override public void updateRef(int i, Ref v) throws SQLException {}
        @Override public void updateRef(String c, Ref v) throws SQLException {}
        @Override public void updateBlob(int i, Blob v) throws SQLException {}
        @Override public void updateBlob(String c, Blob v) throws SQLException {}
        @Override public void updateClob(int i, Clob v) throws SQLException {}
        @Override public void updateClob(String c, Clob v) throws SQLException {}
        @Override public void updateArray(int i, Array v) throws SQLException {}
        @Override public void updateArray(String c, Array v) throws SQLException {}
        @Override public RowId getRowId(int i) throws SQLException { return null; }
        @Override public RowId getRowId(String c) throws SQLException { return null; }
        @Override public void updateRowId(int i, RowId v) throws SQLException {}
        @Override public void updateRowId(String c, RowId v) throws SQLException {}
        @Override public int getHoldability() throws SQLException { return HOLD_CURSORS_OVER_COMMIT; }
        @Override public void updateNString(int i, String v) throws SQLException {}
        @Override public void updateNString(String c, String v) throws SQLException {}
        @Override public void updateNClob(int i, NClob v) throws SQLException {}
        @Override public void updateNClob(String c, NClob v) throws SQLException {}
        @Override public NClob getNClob(int i) throws SQLException { return null; }
        @Override public NClob getNClob(String c) throws SQLException { return null; }
        @Override public SQLXML getSQLXML(int i) throws SQLException { return null; }
        @Override public SQLXML getSQLXML(String c) throws SQLException { return null; }
        @Override public void updateSQLXML(int i, SQLXML v) throws SQLException {}
        @Override public void updateSQLXML(String c, SQLXML v) throws SQLException {}
        @Override public String getNString(int i) throws SQLException { return null; }
        @Override public String getNString(String c) throws SQLException { return null; }
        @Override public Reader getNCharacterStream(int i) throws SQLException { return null; }
        @Override public Reader getNCharacterStream(String c) throws SQLException { return null; }
        @Override public void updateNCharacterStream(int i, Reader v, long l) throws SQLException {}
        @Override public void updateNCharacterStream(String c, Reader v, long l) throws SQLException {}
        @Override public void updateAsciiStream(int i, InputStream v, long l) throws SQLException {}
        @Override public void updateBinaryStream(int i, InputStream v, long l) throws SQLException {}
        @Override public void updateCharacterStream(int i, Reader v, long l) throws SQLException {}
        @Override public void updateAsciiStream(String c, InputStream v, long l) throws SQLException {}
        @Override public void updateBinaryStream(String c, InputStream v, long l) throws SQLException {}
        @Override public void updateCharacterStream(String c, Reader v, long l) throws SQLException {}
        @Override public void updateBlob(int i, InputStream v, long l) throws SQLException {}
        @Override public void updateBlob(String c, InputStream v, long l) throws SQLException {}
        @Override public void updateClob(int i, Reader v, long l) throws SQLException {}
        @Override public void updateClob(String c, Reader v, long l) throws SQLException {}
        @Override public void updateNClob(int i, Reader v, long l) throws SQLException {}
        @Override public void updateNClob(String c, Reader v, long l) throws SQLException {}
        @Override public void updateNCharacterStream(int i, Reader v) throws SQLException {}
        @Override public void updateNCharacterStream(String c, Reader v) throws SQLException {}
        @Override public void updateAsciiStream(int i, InputStream v) throws SQLException {}
        @Override public void updateBinaryStream(int i, InputStream v) throws SQLException {}
        @Override public void updateCharacterStream(int i, Reader v) throws SQLException {}
        @Override public void updateAsciiStream(String c, InputStream v) throws SQLException {}
        @Override public void updateBinaryStream(String c, InputStream v) throws SQLException {}
        @Override public void updateCharacterStream(String c, Reader v) throws SQLException {}
        @Override public void updateBlob(int i, InputStream v) throws SQLException {}
        @Override public void updateBlob(String c, InputStream v) throws SQLException {}
        @Override public void updateClob(int i, Reader v) throws SQLException {}
        @Override public void updateClob(String c, Reader v) throws SQLException {}
        @Override public void updateNClob(int i, Reader v) throws SQLException {}
        @Override public void updateNClob(String c, Reader v) throws SQLException {}
        @Override public <T> T getObject(int i, Class<T> t) throws SQLException { return null; }
        @Override public <T> T getObject(String c, Class<T> t) throws SQLException { return null; }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("Not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return false; }
    }
}
