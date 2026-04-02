package dev.bluetext.jdbc;

import com.google.gson.*;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/**
 * ResultSet backed by a parsed N1QL JSON response.
 *
 * N1QL returns results as a JSON array of objects. For explicit column SELECTs
 * (e.g. SELECT `id`, `data` FROM ...), each object has flat key-value pairs.
 * For SELECT *, results are nested under the collection name — this class
 * auto-unwraps single-key object nesting.
 *
 * Extends NoOpResultSet to inherit ~180 no-op method implementations,
 * overriding only the methods Curity actually uses.
 */
public class QueryResultSet extends NoOpPreparedStatement.NoOpResultSet {

    private final List<JsonObject> rows;
    private final List<String> columnNames;
    private int cursor = -1;
    private boolean lastWasNull = false;
    private boolean closed = false;

    /**
     * Parse a N1QL JSON response body into a ResultSet.
     * Throws SQLException if the response indicates an error.
     */
    public QueryResultSet(String jsonResponse) throws SQLException {
        JsonObject root;
        try {
            root = JsonParser.parseString(jsonResponse).getAsJsonObject();
        } catch (Exception e) {
            throw new SQLException("Failed to parse N1QL response: " + e.getMessage());
        }

        // Check status
        String status = root.has("status") ? root.get("status").getAsString() : "unknown";
        if ("fatal".equals(status) || "errors".equals(status)) {
            String msg = root.has("errors") ? root.get("errors").toString() : jsonResponse;
            throw new SQLException("N1QL query failed: " + msg);
        }

        // Parse results array
        this.rows = new ArrayList<>();
        this.columnNames = new ArrayList<>();

        JsonArray results = root.has("results") ? root.getAsJsonArray("results") : new JsonArray();
        for (JsonElement elem : results) {
            if (!elem.isJsonObject()) continue;
            JsonObject row = elem.getAsJsonObject();

            // Auto-unwrap single-key nesting from SELECT * queries
            // e.g. {"sessions": {"id": "abc", "data": "..."}} → {"id": "abc", "data": "..."}
            if (row.size() == 1) {
                Map.Entry<String, JsonElement> entry = row.entrySet().iterator().next();
                if (entry.getValue().isJsonObject()) {
                    row = entry.getValue().getAsJsonObject();
                }
            }

            rows.add(row);
        }

        // Derive column names from first row (preserves insertion order via Gson's LinkedTreeMap)
        if (!rows.isEmpty()) {
            for (String key : rows.get(0).keySet()) {
                columnNames.add(key);
            }
        }
    }

    // --- Cursor navigation ---

    @Override
    public boolean next() throws SQLException {
        checkClosed();
        return ++cursor < rows.size();
    }

    @Override public boolean isBeforeFirst() { return cursor < 0; }
    @Override public boolean isAfterLast() { return cursor >= rows.size(); }
    @Override public boolean isFirst() { return cursor == 0 && !rows.isEmpty(); }
    @Override public boolean isLast() { return cursor == rows.size() - 1 && !rows.isEmpty(); }
    @Override public int getRow() { return cursor >= 0 && cursor < rows.size() ? cursor + 1 : 0; }

    // --- Column lookup ---

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        checkClosed();
        int idx = columnNames.indexOf(columnLabel);
        if (idx < 0) throw new SQLException("Column not found: " + columnLabel);
        return idx + 1; // JDBC is 1-based
    }

    // --- String getters ---

    @Override
    public String getString(int columnIndex) throws SQLException {
        return getString(getColumnName(columnIndex));
    }

    @Override
    public String getString(String columnLabel) throws SQLException {
        checkClosed();
        JsonElement val = getCurrentValue(columnLabel);
        if (val == null || val.isJsonNull()) {
            lastWasNull = true;
            return null;
        }
        lastWasNull = false;
        if (val.isJsonPrimitive()) return val.getAsString();
        return val.toString(); // For objects/arrays, return JSON string
    }

    // --- Numeric getters ---

    @Override public int getInt(int i) throws SQLException { return getInt(getColumnName(i)); }
    @Override
    public int getInt(String columnLabel) throws SQLException {
        JsonElement val = getCurrentValue(columnLabel);
        if (val == null || val.isJsonNull()) { lastWasNull = true; return 0; }
        lastWasNull = false;
        return val.getAsInt();
    }

    @Override public long getLong(int i) throws SQLException { return getLong(getColumnName(i)); }
    @Override
    public long getLong(String columnLabel) throws SQLException {
        JsonElement val = getCurrentValue(columnLabel);
        if (val == null || val.isJsonNull()) { lastWasNull = true; return 0; }
        lastWasNull = false;
        return val.getAsLong();
    }

    @Override public double getDouble(int i) throws SQLException { return getDouble(getColumnName(i)); }
    @Override
    public double getDouble(String columnLabel) throws SQLException {
        JsonElement val = getCurrentValue(columnLabel);
        if (val == null || val.isJsonNull()) { lastWasNull = true; return 0; }
        lastWasNull = false;
        return val.getAsDouble();
    }

    @Override public float getFloat(int i) throws SQLException { return getFloat(getColumnName(i)); }
    @Override
    public float getFloat(String columnLabel) throws SQLException {
        JsonElement val = getCurrentValue(columnLabel);
        if (val == null || val.isJsonNull()) { lastWasNull = true; return 0; }
        lastWasNull = false;
        return val.getAsFloat();
    }

    @Override public short getShort(int i) throws SQLException { return getShort(getColumnName(i)); }
    @Override
    public short getShort(String columnLabel) throws SQLException {
        JsonElement val = getCurrentValue(columnLabel);
        if (val == null || val.isJsonNull()) { lastWasNull = true; return 0; }
        lastWasNull = false;
        return val.getAsShort();
    }

    @Override public BigDecimal getBigDecimal(int i) throws SQLException { return getBigDecimal(getColumnName(i)); }
    @Override
    public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        JsonElement val = getCurrentValue(columnLabel);
        if (val == null || val.isJsonNull()) { lastWasNull = true; return null; }
        lastWasNull = false;
        return val.getAsBigDecimal();
    }

    // --- Boolean ---

    @Override public boolean getBoolean(int i) throws SQLException { return getBoolean(getColumnName(i)); }
    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        JsonElement val = getCurrentValue(columnLabel);
        if (val == null || val.isJsonNull()) { lastWasNull = true; return false; }
        lastWasNull = false;
        if (val.isJsonPrimitive()) {
            JsonPrimitive p = val.getAsJsonPrimitive();
            // JDBC spec: getBoolean on numeric column returns true for non-zero
            if (p.isNumber()) return p.getAsDouble() != 0;
            if (p.isBoolean()) return p.getAsBoolean();
            // String: "true"/"1"/"yes" → true
            String s = p.getAsString();
            return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
        }
        return false;
    }

    // --- Bytes (Base64 decode for binary data like session_data) ---

    @Override public byte[] getBytes(int i) throws SQLException { return getBytes(getColumnName(i)); }
    @Override
    public byte[] getBytes(String columnLabel) throws SQLException {
        String val = getString(columnLabel);
        if (val == null) return null;
        try {
            return Base64.getDecoder().decode(val);
        } catch (IllegalArgumentException e) {
            // Not Base64 — return raw string bytes
            return val.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    // --- Object ---

    @Override public Object getObject(int i) throws SQLException { return getObject(getColumnName(i)); }
    @Override
    public Object getObject(String columnLabel) throws SQLException {
        JsonElement val = getCurrentValue(columnLabel);
        if (val == null || val.isJsonNull()) { lastWasNull = true; return null; }
        lastWasNull = false;
        if (val.isJsonPrimitive()) {
            JsonPrimitive p = val.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) return p.getAsNumber();
            return p.getAsString();
        }
        return val.toString();
    }

    // --- Timestamp/Date ---

    @Override public Timestamp getTimestamp(int i) throws SQLException { return getTimestamp(getColumnName(i)); }
    @Override
    public Timestamp getTimestamp(String columnLabel) throws SQLException {
        long val = getLong(columnLabel);
        if (lastWasNull) return null;
        return new Timestamp(val);
    }

    // --- Null tracking ---

    @Override
    public boolean wasNull() throws SQLException {
        return lastWasNull;
    }

    // --- Metadata ---

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        return new QueryResultSetMetaData(columnNames, rows.isEmpty() ? null : rows.get(0));
    }

    // --- Lifecycle ---

    @Override
    public void close() throws SQLException {
        closed = true;
    }

    @Override
    public boolean isClosed() throws SQLException {
        return closed;
    }

    // --- Internal helpers ---

    private JsonElement getCurrentValue(String columnLabel) throws SQLException {
        checkClosed();
        if (cursor < 0 || cursor >= rows.size()) {
            throw new SQLException("Cursor not on a valid row");
        }
        JsonObject row = rows.get(cursor);
        return row.has(columnLabel) ? row.get(columnLabel) : null;
    }

    private String getColumnName(int columnIndex) throws SQLException {
        if (columnIndex < 1 || columnIndex > columnNames.size()) {
            throw new SQLException("Column index out of range: " + columnIndex);
        }
        return columnNames.get(columnIndex - 1);
    }

    private void checkClosed() throws SQLException {
        if (closed) throw new SQLException("ResultSet is closed");
    }
}
