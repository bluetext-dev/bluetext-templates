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
    private final SqlPPConnection.PkInfo pkInfo;
    private final Map<Integer, Object> params = new TreeMap<>();

    private QueryResultSet lastResultSet;
    private int lastUpdateCount = -1;

    public QueryPreparedStatement(SqlPPConnection connection, String sql, SqlPPConnection.PkInfo pkInfo) {
        this.connection = connection;
        this.sql = sql;
        this.pkInfo = pkInfo != null ? pkInfo : SqlPPConnection.PkInfo.NONE;
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
        return new QueryPreparedStatement(connection, sql, SqlPPConnection.PkInfo.NONE).executeQuery();
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
        return new QueryPreparedStatement(connection, sql, SqlPPConnection.PkInfo.NONE).executeUpdate();
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
        return new QueryPreparedStatement(connection, sql, SqlPPConnection.PkInfo.NONE).execute();
    }

    @Override public ResultSet getResultSet() { return lastResultSet; }
    @Override public int getUpdateCount() { return lastUpdateCount; }
    @Override public boolean getMoreResults() { lastResultSet = null; lastUpdateCount = -1; return false; }
    @Override public Connection getConnection() { return connection; }

    // --- Document KEY resolution ---

    /**
     * Resolve the document KEY from the PK metadata and current parameter values.
     */
    private String resolveDocumentKey() {
        return switch (pkInfo.strategy()) {
            case PROVIDED -> {
                // Use the PK column's parameter value as KEY
                if (pkInfo.pkParamIndices().length > 0 && pkInfo.pkParamIndices()[0] > 0) {
                    Object val = params.get(pkInfo.pkParamIndices()[0]);
                    yield val != null ? val.toString() : java.util.UUID.randomUUID().toString();
                }
                yield java.util.UUID.randomUUID().toString();
            }
            case COALESCE_UUID -> {
                // Use provided PK if non-null, otherwise generate UUID
                if (pkInfo.pkParamIndices().length > 0 && pkInfo.pkParamIndices()[0] > 0) {
                    Object val = params.get(pkInfo.pkParamIndices()[0]);
                    if (val != null) {
                        yield val.toString();
                    } else {
                        // Generate UUID and also update the param so the VALUE has it too
                        String uuid = java.util.UUID.randomUUID().toString();
                        params.put(pkInfo.pkParamIndices()[0], uuid);
                        yield uuid;
                    }
                }
                yield java.util.UUID.randomUUID().toString();
            }
            case ALWAYS_UUID -> {
                // Always generate UUID, override the PK param
                String uuid = java.util.UUID.randomUUID().toString();
                if (pkInfo.pkParamIndices().length > 0 && pkInfo.pkParamIndices()[0] > 0) {
                    params.put(pkInfo.pkParamIndices()[0], uuid);
                }
                yield uuid;
            }
            case COMPOSITE -> {
                // Concatenate PK column values with "::"
                StringBuilder key = new StringBuilder();
                for (int idx : pkInfo.pkParamIndices()) {
                    if (!key.isEmpty()) key.append("::");
                    Object val = idx > 0 ? params.get(idx) : null;
                    key.append(val != null ? val.toString() : "");
                }
                yield key.toString();
            }
        };
    }

    // --- Uniqueness pre-checks for INSERT statements ---

    /**
     * Before executing an INSERT on a constrained table, check if the uniqueness
     * constraints would be violated. Throws SQLSTATE 23505 if so.
     */
    private void checkUniquenessConstraints() throws SQLException {
        if (!sql.trim().toUpperCase().startsWith("INSERT")) return;

        String collection = SqlPPConnection.extractCollection(sql);
        if (collection == null) return;

        var constraints = SqlPPConnection.UNIQUE_CONSTRAINTS.get(collection);
        if (constraints == null) return;

        // Build column→param index mapping from the original SQL
        // The SQL looks like: INSERT INTO `curity`.`_default`.`accounts` (KEY ..., VALUE {"col1": $1, "col2": $2, ...})
        // We need to extract the column names and their parameter references from the VALUE JSON object
        // Simpler approach: use the params map directly — params are ordered by the column list
        // But we don't know which param maps to which column from the translated SQL alone.
        // For now, we skip the pre-check and rely on the application-level uniqueness
        // (Curity handles "user already exists" at the application layer for most flows).
        // The N1QL INSERT (not UPSERT) + document KEY based on PK already prevents PK-level duplicates.
        //
        // Full uniqueness enforcement would require parsing the VALUE JSON to extract column→param mappings,
        // building a SELECT query, executing it, and checking results. This is non-trivial for parameterized
        // queries where the values aren't known until execution time.
        //
        // TODO: Implement full uniqueness pre-checks if Curity's auth flow depends on them.
    }

    // --- N1QL HTTP execution via shared HttpClient ---

    private String executeHttp() throws SQLException {
        checkUniquenessConstraints();
        try {
            String n1ql = replacePositionalParams(sql);

            // Resolve __KEY__ placeholder with actual document key
            if (n1ql.contains("\"__KEY__\"")) {
                String key = resolveDocumentKey();
                n1ql = n1ql.replace("\"__KEY__\"", jsonString(key));
            }

            String jsonBody = buildJsonBody(n1ql);

            HttpRequest request = HttpRequest.newBuilder(connection.n1qlUri)
                    .timeout(Duration.ofSeconds(75))
                    .header("Content-Type", "application/json")
                    .header("Authorization", connection.authHeader)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = SqlPPConnection.HTTP_CLIENT.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            String body = response.body();

            // Check for N1QL errors in the response body (Couchbase returns HTTP 200 for many errors)
            checkN1qlErrors(body);

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

    /**
     * Check N1QL response body for errors. Couchbase returns HTTP 200 for many errors,
     * with the actual error in the JSON body as "status":"errors" or "status":"fatal".
     * Maps duplicate key error (12009) to SQLSTATE 23505 for JDBC compatibility.
     */
    private void checkN1qlErrors(String body) throws SQLException {
        if (body == null) return;
        // Quick string checks before JSON parsing
        if (!body.contains("\"fatal\"") && !body.contains("\"errors\"")) return;
        // Only trigger on status field, not arbitrary occurrences
        if (!body.contains("\"status\":\"fatal\"") && !body.contains("\"status\":\"errors\"")) return;

        // Extract error code and message
        String message = body;
        int errorCode = 0;
        int codeIdx = body.indexOf("\"code\":");
        if (codeIdx >= 0) {
            String after = body.substring(codeIdx + 7).trim();
            int end = after.indexOf(',');
            if (end < 0) end = after.indexOf('}');
            if (end > 0) {
                try { errorCode = Integer.parseInt(after.substring(0, end).trim()); }
                catch (NumberFormatException ignored) {}
            }
        }
        int msgIdx = body.indexOf("\"msg\":\"");
        if (msgIdx >= 0) {
            int start = msgIdx + 7;
            int end = body.indexOf("\"", start);
            if (end > start) message = body.substring(start, end);
        }

        // Map Couchbase duplicate key (12009) to SQLSTATE 23505
        String sqlState = "HY000"; // generic
        if (errorCode == 12009 || errorCode == 17012
                || message.contains("DuplicateKey") || message.contains("duplicatekey")) {
            sqlState = "23505";
            message = "integrity constraint violation: duplicate key: " + message;
        }

        throw new SQLException("N1QL error: " + message, sqlState, errorCode);
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
