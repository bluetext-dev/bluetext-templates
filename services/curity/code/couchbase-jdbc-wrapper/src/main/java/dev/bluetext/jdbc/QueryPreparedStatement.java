package dev.bluetext.jdbc;

import java.io.*;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * PreparedStatement that executes all SQL (reads and writes) via HTTP
 * against the Couchbase N1QL Query service (port 8093).
 *
 * Reads return a QueryResultSet parsed from the N1QL JSON response.
 * Writes return the mutation count from the N1QL metrics.
 */
public class QueryPreparedStatement extends NoOpPreparedStatement {

    private final String host;
    private final String user;
    private final String password;
    private final String sql;
    private final Map<Integer, Object> params = new TreeMap<>();

    // Cached results from last execute()
    private QueryResultSet lastResultSet;
    private int lastUpdateCount = -1;

    public QueryPreparedStatement(String host, String user, String password, String sql) {
        this.host = host;
        this.user = user;
        this.password = password;
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

    // --- Execute: reads ---

    @Override
    public ResultSet executeQuery() throws SQLException {
        String response = executeHttp();
        lastResultSet = new QueryResultSet(response);
        lastUpdateCount = -1;
        return lastResultSet;
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        // Ad-hoc SQL (from Statement interface) — not typically used with PreparedStatement
        return new QueryPreparedStatement(host, user, password, sql).executeQuery();
    }

    // --- Execute: writes ---

    @Override
    public int executeUpdate() throws SQLException {
        String response = executeHttp();
        lastResultSet = null;
        lastUpdateCount = extractMutationCount(response);
        return lastUpdateCount;
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        return new QueryPreparedStatement(host, user, password, sql).executeUpdate();
    }

    // --- Execute: generic ---

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
        return new QueryPreparedStatement(host, user, password, sql).execute();
    }

    // --- Result accessors ---

    @Override
    public ResultSet getResultSet() throws SQLException {
        return lastResultSet;
    }

    @Override
    public int getUpdateCount() throws SQLException {
        return lastUpdateCount;
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        lastResultSet = null;
        lastUpdateCount = -1;
        return false;
    }

    // --- HTTP execution ---

    private String executeHttp() throws SQLException {
        try {
            String n1ql = replacePositionalParams(sql);

            // Build JSON body with statement + args
            StringBuilder json = new StringBuilder();
            json.append("{\"statement\":").append(toJsonValue(n1ql));
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

            java.net.URL url = new java.net.URL("http://" + host + ":8093/query/service");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(75000);
            conn.setRequestProperty("Content-Type", "application/json");
            String auth = Base64.getEncoder().encodeToString(
                    (user + ":" + password).getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + auth);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }

            int httpCode = conn.getResponseCode();
            InputStream is = httpCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String responseBody;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                responseBody = sb.toString();
            }

            // Let QueryResultSet handle error detection for SELECTs.
            // For writes, check here.
            if (httpCode >= 400 && !sql.trim().toUpperCase().startsWith("SELECT")) {
                throw new SQLException("N1QL query failed (HTTP " + httpCode + "): " + responseBody);
            }

            return responseBody;

        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("N1QL HTTP request failed: " + e.getMessage(), e);
        }
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

    /**
     * Replace ? placeholders with $1, $2, ... for N1QL positional parameters.
     */
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

    private String toJsonValue(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return "\"" + ((String) value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        return "\"" + value.toString()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }
}
