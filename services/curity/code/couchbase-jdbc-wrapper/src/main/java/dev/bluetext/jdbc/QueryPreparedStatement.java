package dev.bluetext.jdbc;

import java.io.*;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * PreparedStatement that executes DML (INSERT, UPDATE, DELETE, UPSERT) via HTTP
 * against the Couchbase N1QL Query service (port 8093).
 *
 * Analytics is read-only, so writes must go through the Query service directly.
 * This statement builds parameterized N1QL queries and executes them via REST API.
 */
public class QueryPreparedStatement extends NoOpPreparedStatement {

    private final String host;
    private final String user;
    private final String password;
    private final String sql;
    private final Map<Integer, Object> params = new TreeMap<>();

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
        // Curity stores session data as byte arrays — encode as base64 string
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

    @Override
    public void clearParameters() { params.clear(); }

    // --- Execute via N1QL HTTP ---
    @Override
    public int executeUpdate() throws SQLException {
        return executeN1QL();
    }

    @Override
    public boolean execute() throws SQLException {
        executeN1QL();
        return false;
    }

    private int executeN1QL() throws SQLException {
        try {
            // Replace positional ? params with $1, $2, ... for N1QL
            String n1ql = replacePositionalParams(sql);

            // Build JSON body
            StringBuilder json = new StringBuilder();
            json.append("{\"statement\":").append(toJsonValue(n1ql));

            // Add named parameters as "args" array (positional: $1, $2, ...)
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

            // Execute via HTTP
            java.net.URL url = new java.net.URL("http://" + host + ":8093/query/service");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            // Basic auth
            String auth = Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + auth);

            // Send
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }

            // Read response
            int httpCode = conn.getResponseCode();
            String responseBody;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    httpCode >= 400 ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                responseBody = sb.toString();
            }

            if (httpCode >= 400 || responseBody.contains("\"status\":\"fatal\"") || responseBody.contains("\"status\":\"errors\"")) {
                throw new SQLException("N1QL query failed (HTTP " + httpCode + "): " + responseBody);
            }

            // Extract mutation count if available
            if (responseBody.contains("\"mutationCount\"")) {
                int idx = responseBody.indexOf("\"mutationCount\":");
                if (idx >= 0) {
                    String after = responseBody.substring(idx + "\"mutationCount\":".length()).trim();
                    int end = after.indexOf(',');
                    if (end < 0) end = after.indexOf('}');
                    if (end > 0) {
                        try { return Integer.parseInt(after.substring(0, end).trim()); } catch (NumberFormatException ignored) {}
                    }
                }
            }
            return 1;

        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("Failed to execute N1QL query: " + e.getMessage(), e);
        }
    }

    /**
     * Replace ? placeholders with $1, $2, ... for N1QL positional parameters.
     * When used with the "args" JSON array, $1 maps to args[0], $2 to args[1], etc.
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
        if (value instanceof String) return "\"" + ((String) value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        return "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
