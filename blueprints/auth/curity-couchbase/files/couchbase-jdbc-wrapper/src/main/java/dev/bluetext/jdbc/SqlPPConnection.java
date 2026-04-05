package dev.bluetext.jdbc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.util.*;
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
    final String authHeader;
    final URI n1qlUri;

    /** Shared HttpClient across all connections — thread-safe, connection pooling. */
    static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private boolean closed = false;
    private boolean autoCommit = true;
    private boolean readOnly = false;
    private int transactionIsolation = Connection.TRANSACTION_READ_COMMITTED;

    // --- SQL translation patterns ---

    // INSERT INTO "table" (...) VALUES (...) ON CONFLICT ("col") [WHERE ...] DO UPDATE SET ...
    private static final Pattern ON_CONFLICT = Pattern.compile(
            "(?i)INSERT\\s+INTO\\s+(\\S+)\\s*\\(([^)]+)\\)\\s*VALUES\\s*\\(([^)]+)\\)\\s*ON\\s+CONFLICT\\s*\\([^)]+\\)(?:\\s+WHERE\\s+.*?)?\\s*DO\\s+UPDATE\\s+SET\\s+(.*)",
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

    // Unqualified backtick-quoted column name: `col` NOT preceded by `.`
    // Matches standalone `col` but not `table`.`col`
    // Covers both sides of comparisons, ON clauses, WHERE, AND/OR conditions
    private static final Pattern UNQUALIFIED_COLUMN = Pattern.compile(
            "(?<!\\.\\s{0,0})(?<!\\.)(`[^`]+`)(?![`.])"
    );

    // Columnar INSERT/UPSERT: INSERT INTO table (`col1`, `col2`) VALUES (?, ?)
    private static final Pattern COLUMNAR_INSERT = Pattern.compile(
            "(?i)(INSERT|UPSERT)\\s+INTO\\s+(\\S+)\\s*\\(([^)]+)\\)\\s*VALUES\\s*\\(([^)]+)\\)",
            Pattern.DOTALL
    );

    // -----------------------------------------------------------------------
    // Table metadata — maps collection names to their primary key strategy.
    // This is the complete Curity HSQLDB schema (17 tables).
    // -----------------------------------------------------------------------

    enum PkStrategy { PROVIDED, COALESCE_UUID, ALWAYS_UUID, COMPOSITE }

    record TableMeta(PkStrategy strategy, String[] pkColumns) {}

    static final Map<String, TableMeta> TABLE_META = Map.ofEntries(
        // Single PK, always provided by Curity
        Map.entry("delegations",    new TableMeta(PkStrategy.PROVIDED, new String[]{"id"})),
        Map.entry("tokens",         new TableMeta(PkStrategy.PROVIDED, new String[]{"token_hash"})),
        Map.entry("nonces",         new TableMeta(PkStrategy.PROVIDED, new String[]{"token"})),
        Map.entry("sessions",       new TableMeta(PkStrategy.PROVIDED, new String[]{"id"})),
        Map.entry("devices",        new TableMeta(PkStrategy.PROVIDED, new String[]{"id"})),
        Map.entry("audit",          new TableMeta(PkStrategy.PROVIDED, new String[]{"id"})),
        Map.entry("dynamically_registered_clients", new TableMeta(PkStrategy.PROVIDED, new String[]{"client_id"})),
        Map.entry("entities",       new TableMeta(PkStrategy.PROVIDED, new String[]{"id"})),
        Map.entry("entity_relations", new TableMeta(PkStrategy.PROVIDED, new String[]{"id"})),
        Map.entry("account_resource_relations", new TableMeta(PkStrategy.PROVIDED, new String[]{"id"})),
        Map.entry("database_client_resource_relations", new TableMeta(PkStrategy.PROVIDED, new String[]{"id"})),

        // Single PK, auto-UUID if null (COALESCE)
        Map.entry("accounts",       new TableMeta(PkStrategy.COALESCE_UUID, new String[]{"account_id"})),
        Map.entry("buckets",        new TableMeta(PkStrategy.COALESCE_UUID, new String[]{"id"})),

        // Single PK, always auto-generated
        Map.entry("credentials",    new TableMeta(PkStrategy.ALWAYS_UUID, new String[]{"id"})),

        // Composite PK
        Map.entry("linked_accounts", new TableMeta(PkStrategy.COMPOSITE, new String[]{"account_id", "linked_account_id", "linked_account_domain_name"})),
        Map.entry("database_clients", new TableMeta(PkStrategy.COMPOSITE, new String[]{"client_id", "profile_id"})),
        Map.entry("database_service_providers", new TableMeta(PkStrategy.COMPOSITE, new String[]{"id", "profile_id"}))
    );

    // -----------------------------------------------------------------------
    // Uniqueness constraints (non-PK field combinations that must be unique)
    // -----------------------------------------------------------------------

    record UniqueConstraint(String name, String[] columns) {}

    static final Map<String, List<UniqueConstraint>> UNIQUE_CONSTRAINTS = Map.of(
        "accounts", List.of(
            new UniqueConstraint("IDX_ACCOUNTS_TENANT_USERNAME", new String[]{"tenant_id", "username"}),
            new UniqueConstraint("IDX_ACCOUNTS_TENANT_EMAIL", new String[]{"tenant_id", "email"}),
            new UniqueConstraint("IDX_ACCOUNTS_TENANT_PHONE", new String[]{"tenant_id", "phone"})
        ),
        "credentials", List.of(
            new UniqueConstraint("IDX_CREDENTIALS_TENANT_SUBJECT", new String[]{"tenant_id", "subject"})
        ),
        "devices", List.of(
            new UniqueConstraint("IDX_DEVICES_TENANT_ACCOUNT_ID_DEVICE_ID", new String[]{"tenant_id", "account_id", "device_id"})
        ),
        "buckets", List.of(
            new UniqueConstraint("IDX_BUCKETS_TENANT_SUBJECT_PURPOSE", new String[]{"tenant_id", "subject", "purpose"})
        ),
        "entities", List.of(
            new UniqueConstraint("IDX_ENTITIES_BUSINESS_KEY", new String[]{"tenant_id", "context_id", "type", "value"})
        )
    );

    // -----------------------------------------------------------------------
    // Column → table mapping for qualifying unqualified column refs in JOINs.
    // Only columns unique to one table — shared columns (tenant_id, id, created,
    // updated, attributes, status, expires, type) are already qualified by Curity.
    // -----------------------------------------------------------------------

    static final Map<String, String> COLUMN_TABLE = Map.ofEntries(
        // accounts
        Map.entry("account_id", "accounts"), Map.entry("username", "accounts"),
        Map.entry("email", "accounts"), Map.entry("phone", "accounts"),
        Map.entry("active", "accounts"),
        // credentials
        Map.entry("subject", "credentials"), Map.entry("password", "credentials"),
        // sessions
        Map.entry("session_data", "sessions"),
        // delegations
        Map.entry("owner", "delegations"), Map.entry("scope", "delegations"),
        Map.entry("scope_claims", "delegations"), Map.entry("client_id", "delegations"),
        Map.entry("redirect_uri", "delegations"), Map.entry("claims", "delegations"),
        Map.entry("authentication_attributes", "delegations"),
        Map.entry("authorization_code_hash", "delegations"),
        // tokens
        Map.entry("token_hash", "tokens"), Map.entry("delegations_id", "tokens"),
        Map.entry("purpose", "tokens"), Map.entry("usage", "tokens"),
        Map.entry("format", "tokens"), Map.entry("issuer", "tokens"),
        Map.entry("audience", "tokens"), Map.entry("not_before", "tokens"),
        Map.entry("meta_data", "tokens"),
        // nonces
        Map.entry("token", "nonces"), Map.entry("reference_data", "nonces"),
        Map.entry("ttl", "nonces"), Map.entry("consumed", "nonces"),
        // devices
        Map.entry("device_id", "devices"), Map.entry("external_id", "devices"),
        Map.entry("alias", "devices"), Map.entry("form_factor", "devices"),
        Map.entry("device_type", "devices"),
        // audit
        Map.entry("instant", "audit"), Map.entry("event_instant", "audit"),
        Map.entry("server", "audit"), Map.entry("message", "audit"),
        Map.entry("event_type", "audit"), Map.entry("endpoint", "audit"),
        Map.entry("session", "audit"), Map.entry("resource", "audit"),
        Map.entry("authenticated_subject", "audit"), Map.entry("authenticated_client", "audit"),
        Map.entry("acr", "audit"),
        // dynamically_registered_clients
        Map.entry("client_secret", "dynamically_registered_clients"),
        Map.entry("instance_of_client", "dynamically_registered_clients"),
        Map.entry("initial_client", "dynamically_registered_clients"),
        Map.entry("authenticated_user", "dynamically_registered_clients"),
        Map.entry("grant_types", "dynamically_registered_clients"),
        Map.entry("redirect_uris", "dynamically_registered_clients"),
        // database_clients
        Map.entry("profile_id", "database_clients"),
        Map.entry("client_name", "database_clients"),
        Map.entry("client_metadata", "database_clients"),
        Map.entry("configuration_references", "database_clients"),
        // entities
        Map.entry("context_id", "entities"), Map.entry("value", "entities"),
        Map.entry("display_name", "entities"), Map.entry("versions", "entities"),
        Map.entry("deleted", "entities"), Map.entry("version", "entities"),
        // linked_accounts
        Map.entry("linked_account_id", "linked_accounts"),
        Map.entry("linked_account_domain_name", "linked_accounts"),
        Map.entry("linking_account_manager", "linked_accounts"),
        // entity_relations
        Map.entry("source_entity_id", "entity_relations"),
        Map.entry("target_entity_id", "entity_relations"),
        // buckets
        Map.entry("purpose_bucket", "buckets") // "purpose" shared with tokens — use alias
    );

    public SqlPPConnection(String catalog, String host, String user, String password) throws SQLException {
        this.catalog = catalog;
        this.host = host;
        this.user = user;
        this.password = password;
        this.n1qlUri = URI.create("http://" + host + ":8093/query/service");
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));

        // Verify connectivity
        try {
            HttpRequest ping = HttpRequest.newBuilder(n1qlUri)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Authorization", authHeader)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"statement\":\"SELECT 1\"}"))
                    .build();
            HttpResponse<String> resp = HTTP_CLIENT.send(ping, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new SQLException("Cannot connect to Couchbase N1QL at " + host + ":8093 (HTTP " + resp.statusCode() + ")");
            }
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("Cannot connect to Couchbase N1QL at " + host + ":8093: " + e.getMessage(), e);
        }
    }

    // --- Translation result carrying PK metadata ---

    record TranslatedSql(String sql, PkInfo pkInfo) {}

    /**
     * PK and column info for document KEY computation and uniqueness checks at execution time.
     * pkParamIndices: 1-based indices into the original parameter list for PK columns.
     * colParamMap: column name → 1-based param index (for all columns in an INSERT).
     */
    record PkInfo(PkStrategy strategy, int[] pkParamIndices, Map<String, Integer> colParamMap) {
        static final PkInfo NONE = new PkInfo(PkStrategy.PROVIDED, new int[0], Map.of());
    }

    // --- SQL translation ---

    static boolean isDdl(String sql) {
        return sql != null && DDL_PATTERN.matcher(sql).matches();
    }

    /**
     * Qualify unqualified column references in JOIN queries.
     * N1QL requires explicit table.column when multiple tables are in scope.
     *
     * Scans for backtick-quoted identifiers that are known column names and NOT
     * already preceded by a dot (which indicates table.column qualification).
     */
    /**
     * Add explicit AS aliases to 3-part table names in JOINs and qualify
     * unqualified column references. N1QL requires explicit aliases for
     * multi-part keyspace names when multiple tables are in scope.
     */
    static String qualifyColumns(String sql) {
        // Step 1: Add AS aliases to 3-part table names that don't have one.
        // `bucket`.`scope`.`collection` → `bucket`.`scope`.`collection` AS `collection`
        sql = sql.replaceAll(
                "(`[^`]+`\\.`[^`]+`\\.`([^`]+)`)(?!\\s+AS\\b)",
                "$1 AS `$2`"
        );

        // Step 2: Qualify unqualified column references using COLUMN_TABLE map.
        // Standalone `col` → `table`.`col` (only if not already preceded by `.`)
        // Process all columns in a single pass to avoid interference between replacements.
        var result = new StringBuilder();
        int i = 0;
        while (i < sql.length()) {
            if (sql.charAt(i) == '`') {
                int end = sql.indexOf('`', i + 1);
                if (end < 0) { result.append(sql, i, sql.length()); break; }
                String ident = sql.substring(i + 1, end); // content between backticks
                boolean precededByDot = i > 0 && sql.charAt(i - 1) == '.';
                if (!precededByDot) {
                    String table = COLUMN_TABLE.get(ident);
                    if (table != null) {
                        result.append('`').append(table).append("`.`").append(ident).append('`');
                        i = end + 1;
                        continue;
                    }
                }
                result.append(sql, i, end + 1);
                i = end + 1;
            } else {
                result.append(sql.charAt(i));
                i++;
            }
        }
        return result.toString();
    }

    // N1QL reserved words that Curity uses as column aliases
    private static final java.util.Set<String> RESERVED_ALIASES = java.util.Set.of(
            "value", "type", "key", "index", "offset", "limit", "order",
            "group", "having", "where", "from", "select", "insert",
            "update", "delete", "set", "all", "any", "some", "join"
    );

    /**
     * Quote unquoted column aliases that are N1QL reserved words.
     * `AS value` → `AS \`value\``
     */
    static String quoteReservedAliases(String sql) {
        for (String word : RESERVED_ALIASES) {
            // Match: AS <word> (case-insensitive, not already backtick-quoted)
            sql = sql.replaceAll("(?i)\\bAS\\s+" + word + "\\b", "AS `" + word + "`");
        }
        return sql;
    }

    /** Instance method for convenience — delegates to static. */
    TranslatedSql translate(String sql) {
        return translateSql(sql, catalog);
    }

    /**
     * Translates PostgreSQL SQL to Couchbase SQL++ for N1QL execution.
     * Returns null for DDL. Pure function — testable without a connection.
     */
    static TranslatedSql translateSql(String sql, String catalog) {
        if (sql == null) return null;
        if (isDdl(sql)) return null;

        // Double-quoted identifiers → backtick-quoted (SQL++ style)
        sql = sql.replace('"', '`');

        // Track if original was ON CONFLICT (→ UPSERT semantics)
        boolean isUpsert = false;
        var matcher = ON_CONFLICT.matcher(sql);
        if (matcher.matches()) {
            String table = matcher.group(1);
            String columns = matcher.group(2);
            String values = matcher.group(3);
            sql = "UPSERT INTO " + table + " (" + columns + ") VALUES (" + values + ")";
            isUpsert = true;
        }

        // Qualify unqualified table names: `sessions` → `curity`.`_default`.`sessions`
        if (catalog != null) {
            sql = UNQUALIFIED_TABLE.matcher(sql).replaceAll(
                    "$1`" + catalog + "`.`_default`.$2"
            );
        }

        // Qualify unqualified column refs in JOIN queries (N1QL requires explicit table.column)
        if (sql.toUpperCase().contains("JOIN")) {
            sql = qualifyColumns(sql);
        }

        // Quote N1QL reserved words used as column aliases
        // Curity uses `AS value`, `AS type` etc. which are reserved in SQL++
        sql = quoteReservedAliases(sql);

        // Columnar INSERT/UPSERT → N1QL KEY/VALUE format with proper document KEY
        return convertColumnarInsert(sql, isUpsert);
    }

    /**
     * Converts columnar INSERT/UPSERT to N1QL KEY/VALUE format.
     *
     * The KEY is a __KEY__ placeholder that QueryPreparedStatement resolves
     * at execution time from the actual parameter values. This avoids adding
     * extra ? placeholders that would shift parameter indices.
     *
     * Uses INSERT (not UPSERT) for plain INSERTs to get duplicate key errors.
     * Uses UPSERT only when the original SQL had ON CONFLICT.
     */
    private static TranslatedSql convertColumnarInsert(String sql, boolean isUpsert) {
        var m = COLUMNAR_INSERT.matcher(sql);
        if (!m.matches()) return new TranslatedSql(sql, PkInfo.NONE);

        String verb = isUpsert ? "UPSERT" : m.group(1).toUpperCase();
        String table = m.group(2);
        String[] cols = m.group(3).split("\\s*,\\s*");
        String[] vals = m.group(4).split("\\s*,\\s*");

        // Extract collection name from qualified table
        String collection = table;
        int lastDot = table.lastIndexOf('.');
        if (lastDot >= 0) collection = table.substring(lastDot + 1).replace("`", "");

        TableMeta meta = TABLE_META.get(collection);

        // Build column name list (stripped of backticks)
        String[] colNames = new String[cols.length];
        for (int i = 0; i < cols.length; i++) colNames[i] = cols[i].trim().replace("`", "");

        // Build JSON object for VALUE (same ? placeholders as original)
        // For COALESCE_UUID/ALWAYS_UUID: add the PK column with __PK_VAL__ placeholder
        // if Curity didn't include it in the INSERT columns (DB would auto-generate it)
        StringBuilder obj = new StringBuilder("{");
        boolean pkIncluded = false;
        for (int i = 0; i < colNames.length && i < vals.length; i++) {
            if (i > 0) obj.append(", ");
            obj.append("\"").append(colNames[i]).append("\": ").append(vals[i].trim());
            if (meta != null && meta.pkColumns().length > 0 && colNames[i].equals(meta.pkColumns()[0])) {
                pkIncluded = true;
            }
        }
        if (!pkIncluded && meta != null
                && (meta.strategy() == PkStrategy.COALESCE_UUID || meta.strategy() == PkStrategy.ALWAYS_UUID)) {
            obj.append(", \"").append(meta.pkColumns()[0]).append("\": \"__PK_VAL__\"");
        }
        obj.append("}");

        // KEY is __KEY__ placeholder — resolved by QueryPreparedStatement at execution time
        String result = verb + " INTO " + table + " (KEY, VALUE) VALUES (\"__KEY__\", " + obj + ")";

        // Build full column→param index mapping (1-based)
        Map<String, Integer> colParamMap = new LinkedHashMap<>();
        for (int i = 0; i < colNames.length; i++) {
            colParamMap.put(colNames[i], i + 1);
        }

        if (meta == null) {
            return new TranslatedSql(result, new PkInfo(PkStrategy.ALWAYS_UUID, new int[0], colParamMap));
        }

        // Find 1-based parameter indices for PK columns
        int[] pkIndices = new int[meta.pkColumns.length];
        for (int p = 0; p < meta.pkColumns.length; p++) {
            pkIndices[p] = -1;
            for (int c = 0; c < colNames.length; c++) {
                if (colNames[c].equals(meta.pkColumns[p])) {
                    pkIndices[p] = c + 1;
                    break;
                }
            }
        }

        return new TranslatedSql(result, new PkInfo(meta.strategy, pkIndices, colParamMap));
    }

    /**
     * Extract the collection name from a translated SQL statement.
     * Looks for the table after INTO/FROM/UPDATE keywords.
     */
    static String extractCollection(String sql) {
        if (sql == null) return null;
        var m = Pattern.compile("(?i)(?:INTO|FROM|UPDATE)\\s+(`[^`]+`\\.`[^`]+`\\.`([^`]+)`)").matcher(sql);
        if (m.find()) return m.group(2);
        return null;
    }

    // --- Statement/PreparedStatement creation ---

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        checkClosed();
        TranslatedSql translated = translate(sql);
        if (translated == null) return new NoOpPreparedStatement();
        return new QueryPreparedStatement(this, translated.sql(), translated.pkInfo());
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

    @Override public String nativeSQL(String sql) { var t = translate(sql); return t != null ? t.sql() : null; }

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
            HttpResponse<String> resp = HTTP_CLIENT.send(ping, HttpResponse.BodyHandlers.ofString());
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
