# Couchbase JDBC Wrapper for Curity

Thin JDBC driver wrapper that enables Curity Identity Server to use Couchbase as a data store via JDBC.

## Problem

Curity's JDBC plugin detects the SQL dialect by parsing the connection string prefix (e.g., `jdbc:postgresql://` → PostgreSQL dialect). Couchbase's official JDBC driver uses `jdbc:couchbase:analytics://` which Curity doesn't recognize. Additionally, Couchbase SQL++ has minor syntax differences from PostgreSQL SQL.

## Solution

Two components:

### 1. CouchbasePostgresDriver

A JDBC driver that accepts `jdbc:postgresql:couchbase://host/catalog` and delegates to the Couchbase Analytics JDBC driver (`jdbc:couchbase:analytics://host`).

- `jdbc:postgresql:couchbase://` — the second colon-separated token is `postgresql`, which passes Curity's dialect detection
- The real PostgreSQL driver rejects this URL (it expects `jdbc:postgresql://`)
- Our wrapper strips the prefix and passes through to `com.couchbase.client.jdbc.CouchbaseDriver`

### 2. SqlPPConnection

A Connection wrapper that intercepts SQL statements and translates PostgreSQL-specific syntax to Couchbase SQL++:

| PostgreSQL | SQL++ |
|---|---|
| `"identifier"` (double-quoted) | `` `identifier` `` (backtick-quoted) |
| `INSERT INTO ... ON CONFLICT ... DO UPDATE SET ...` | `UPSERT INTO ... VALUES (...)` |

## Connection String Format

```
jdbc:postgresql:couchbase://couchbase-headless/curity?user=Administrator&password=password&ssl=false
```

- `couchbase-headless` — Couchbase cluster hostname (no port — driver auto-discovers)
- `/curity` — bucket name (passed as catalog)
- `user`/`password` — Couchbase credentials
- `ssl` — TLS toggle

## Architecture

Hybrid read/write path: reads go through Analytics JDBC, writes go through N1QL HTTP.

```
Curity JDBC Plugin
  ↓ connection string: jdbc:postgresql:couchbase://host?catalog=curity
  ↓ dialect detected: PostgreSQL ✓
  ↓ driver class: dev.bluetext.jdbc.CouchbasePostgresDriver
  ↓
CouchbasePostgresDriver
  ↓ extracts: catalog, host, user, password from URL
  ↓ delegates to: com.couchbase.client.jdbc.CouchbaseDriver (Analytics)
  ↓ wraps connection in: SqlPPConnection
  ↓
SqlPPConnection (routes by statement type)
  ├── DDL (CREATE TABLE, etc.) → NoOpPreparedStatement (silent no-op)
  ├── SELECT → Analytics JDBC (3-part qualified: bucket.scope.collection)
  └── INSERT/UPDATE/DELETE/UPSERT → QueryPreparedStatement
       ↓ converts columnar INSERT to N1QL UPSERT (KEY, VALUE) format
       ↓ executes via HTTP POST to port 8093 (N1QL Query service)
       ↓ returns mutation count
```

**Why hybrid?** The Couchbase JDBC driver v1.0.5 is Analytics-only (read-only).
Analytics doesn't support INSERT/UPDATE/DELETE. Writes go through the N1QL Query
service via HTTP REST API, which supports full DML with KEY/VALUE document syntax.

## Requirements

- Couchbase Enterprise Edition with Analytics service enabled (`cbas`)
- Analytics memory quota: minimum 1024MB
- Primary indexes on all collections (for N1QL query support)
- Couchbase JDBC driver: `com.couchbase.client:couchbase-jdbc-driver:1.0.5` (Maven Central)

## DDL Interception

Curity's PostgreSQL dialect sends DDL statements (CREATE TABLE, CREATE INDEX, ALTER TABLE, etc.) that Couchbase Analytics doesn't support. Since the 15 required collections are pre-created by service-config-manager:

```
accounts, credentials, sessions, tokens, delegations, nonces,
linked_accounts, devices, audit, dynamically_registered_clients,
database_clients, buckets, database_service_providers, entities,
entity_relations
```

...the wrapper intercepts all DDL and returns no-op results via `NoOpPreparedStatement`. DML (SELECT, INSERT, UPDATE, DELETE, UPSERT) passes through normally with SQL++ translation.

Intercepted statement types: `CREATE TABLE/INDEX/SEQUENCE`, `ALTER TABLE`, `DROP TABLE/INDEX/SEQUENCE`, `TRUNCATE`, `SET`.

## Collection & Analytics Setup

Collections are created by service-config-manager in the `curity` bucket (`_default` scope). Analytics is enabled on the bucket via `ALTER BUCKET ... ENABLE ANALYTICS`, which auto-links all collections as Analytics datasets. This is configured via the `analytics: true` flag in the couchbase.yaml bucket config (set by the `auth/curity-couchbase` blueprint).

## License

Works within Curity's `jdbc[1]` license restriction — counts as a single JDBC datasource.
