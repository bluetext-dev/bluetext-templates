package dev.bluetext.jdbc;

import java.math.BigDecimal;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * PreparedStatement that executes all SQL via HTTP against the Couchbase N1QL
 * Query service using the Connection's shared HttpClient for connection reuse.
 */
public class QueryPreparedStatement extends NoOpPreparedStatement {

    private final SqlPPConnection connection;
    private final String sql;
    private final Map<Integer, Object> params = new TreeMap<>();

    private QueryResultSet lastResultSet;
    private int lastUpdateCount = -1;

    public QueryPreparedStatement(SqlPPConnection connection, String sql) {
        this.connection = connection;
        this.sql = sql;
    }

    // --- Parameter capture ---
    @Override public void setString(int i, String v) { params.put(i, v); }
    @Override public void setInt(int i, int v) { params.put(i, v); }
    @Override public void setLong(int i, long v) { params.put(i, v); }
    @Override public void setDouble(int i, double v) { params.put(i, v); }
    @Override public void setFloat(int i, float v) { params.put(i, v); }
    @Override public void setBoolean(int i, boolean v) { params.put(i, v); }
    @Override public void setShort(int i, short v) { params.put(i, v); }
    @Override public void setByte(int i, byte v) { params.put(i, v); }
    @Override public void setBigDecimal(int i, BigDecimal v) { params.put(i, v); }
    @Override public void setNull(int i, int t) { params.put(i, null); }
    @Override public void setObject(int i, Object v) { params.put(i, v); }
    @Override public void setObject(int i, Object v, int t) { params.put(i, v); }
    @Override public void setBytes(int i, byte[] v) {
        params.put(i, v != null ? Base64.getEncoder().encodeToString(v) : null);
    }
    @Override public void setTimestamp(int i, Timestamp v) {
        params.put(i, v != null ? v.getTime() : null);
    }
    @Override public void setDate(int i, Date v) {
        params.put(i, v != null ? v.toString() : null);
    }
    @Override public void setTime(int i, Time v) {
        params.put(i, v != null ? v.toString() : null);
    }

    @Override public void clearParameters() { params.clear(); }

    // --- Execute ---

    @Override
    public ResultSet executeQuery() throws SQLException {
        String response = executeHttp();
        lastResultSet = new QueryResultSet(response);
        lastUpdateCount = -1;
        return lastResultSet;
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        return new QueryPreparedStatement(connection, sql).executeQuery();
    }

    @Override
    public int executeUpdate() throws SQLException {
        String response = executeHttp();
        lastResultSet = null;
        lastUpdateCount = extractMutationCount(response);
        return lastUpdateCount;
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        return new QueryPreparedStatement(connection, sql).executeUpdate();
    }

    @Override
    public boolean execute() throws SQLException {
        String response = executeHttp();
        boolean isSelect = sql.trim().toUpperCase().startsWith("SELECT");
        if (isSelect) {
            lastResultSet = new QueryResultSet(response);
            lastUpdateCount = -1;
            return true;
        } else {
            lastResultSet = null;
            lastUpdateCount = extractMutationCount(response);
            return false;
        }
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        return new QueryPreparedStatement(connection, sql).execute();
    }

    @Override public ResultSet getResultSet() { return lastResultSet; }
    @Override public int getUpdateCount() { return lastUpdateCount; }
    @Override public boolean getMoreResults() { lastResultSet = null; lastUpdateCount = -1; return false; }
    @Override public Connection getConnection() { return connection; }

    // --- N1QL HTTP execution via shared HttpClient ---

    private String executeHttp() throws SQLException {
        try {
            String n1ql = replacePositionalParams(sql);
            String jsonBody = buildJsonBody(n1ql);

            HttpRequest request = HttpRequest.newBuilder(connection.n1qlUri)
                    .timeout(Duration.ofSeconds(75))
                    .header("Content-Type", "application/json")
                    .header("Authorization", connection.authHeader)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = connection.httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            String body = response.body();

            // For non-SELECT: fail on HTTP errors
            if (response.statusCode() >= 400 && !sql.trim().toUpperCase().startsWith("SELECT")) {
                throw new SQLException("N1QL query failed (HTTP " + response.statusCode() + "): " + body);
            }

            return body;

        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("N1QL HTTP request failed: " + e.getMessage(), e);
        }
    }

    private String buildJsonBody(String n1ql) {
        StringBuilder json = new StringBuilder();
        json.append("{\"statement\":").append(jsonString(n1ql));
        if (!params.isEmpty()) {
            json.append(",\"args\":[");
            boolean first = true;
            for (Map.Entry<Integer, Object> entry : params.entrySet()) {
                if (!first) json.append(",");
                json.append(toJsonValue(entry.getValue()));
                first = false;
            }
            json.append("]");
        }
        json.append("}");
        return json.toString();
    }

    private int extractMutationCount(String response) {
        if (response.contains("\"mutationCount\"")) {
            int idx = response.indexOf("\"mutationCount\":");
            if (idx >= 0) {
                String after = response.substring(idx + "\"mutationCount\":".length()).trim();
                int end = after.indexOf(',');
                if (end < 0) end = after.indexOf('}');
                if (end > 0) {
                    try { return Integer.parseInt(after.substring(0, end).trim()); }
                    catch (NumberFormatException ignored) {}
                }
            }
        }
        return 1;
    }

    private String replacePositionalParams(String sql) {
        StringBuilder result = new StringBuilder();
        int paramIndex = 1;
        boolean inQuote = false;
        boolean inBacktick = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' && !inBacktick) inQuote = !inQuote;
            else if (c == '`' && !inQuote) inBacktick = !inBacktick;
            else if (c == '?' && !inQuote && !inBacktick) {
                result.append("$").append(paramIndex++);
                continue;
            }
            result.append(c);
        }
        return result.toString();
    }

    // --- JSON serialization (RFC 8259 compliant) ---

    static String toJsonValue(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        return jsonString(value.toString());
    }

    /**
     * Encode a string as a JSON string literal with full RFC 8259 escaping.
     */
    static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
