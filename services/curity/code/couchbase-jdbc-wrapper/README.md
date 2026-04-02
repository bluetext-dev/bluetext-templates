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

All SQL goes through the N1QL Query service (port 8093) via HTTP for strong consistency.

```
Curity JDBC Plugin
  ↓ connection string: jdbc:postgresql:couchbase://host?catalog=curity
  ↓ dialect detected: PostgreSQL ✓
  ↓ driver class: dev.bluetext.jdbc.CouchbasePostgresDriver
  ↓
CouchbasePostgresDriver
  ↓ extracts: catalog, host, user, password from URL
  ↓ wraps connection in: SqlPPConnection
  ↓
SqlPPConnection (translates SQL, routes to N1QL)
  ├── DDL (CREATE TABLE, etc.) → NoOpPreparedStatement (silent no-op)
  └── All other SQL → QueryPreparedStatement
       ↓ translates PostgreSQL → SQL++ (backticks, table qualification)
       ↓ converts columnar INSERT to N1QL UPSERT (KEY, VALUE) format
       ↓ executes via HTTP POST to port 8093 (N1QL Query service)
       ↓ SELECT returns QueryResultSet (parsed from N1QL JSON)
       ↓ INSERT/UPDATE/DELETE returns mutation count
```

All reads and writes use the same N1QL path — no eventual consistency issues.

## Requirements

- Couchbase Enterprise Edition with Query service enabled (`n1ql`)
- Primary indexes on all collections (created by service-config-manager)
- Couchbase JDBC driver: `com.couchbase.client:couchbase-jdbc-driver:1.0.5` (used for connection lifecycle only)
- Gson: `com.google.code.gson:gson:2.11.0` (bundled in fat JAR for JSON parsing)

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

## Curity Image and License

The Bluetext Curity template ships with the **Enterprise Edition image** (`curity.azurecr.io/curity/idsvr`). The image is the runtime binary. The **license key** determines which features and data source types are enabled.

Curity licenses specify which **data source types** are available and how many instances of each type. A `jdbc[1]` license grants one data source of type JDBC. This wrapper fits Couchbase into that single JDBC slot — Curity sees a standard JDBC data source.

For licenses that include the Plugin SDK and custom data source types, see the native [Couchbase DAP plugin](../couchbase-plugin/).
