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

```
Curity JDBC Plugin
  ↓ connection string: jdbc:postgresql:couchbase://host
  ↓ dialect detected: PostgreSQL ✓
  ↓ driver class: dev.bluetext.jdbc.CouchbasePostgresDriver
  ↓
CouchbasePostgresDriver
  ↓ transforms URL: jdbc:couchbase:analytics://host
  ↓ delegates to: com.couchbase.client.jdbc.CouchbaseDriver
  ↓ wraps connection in: SqlPPConnection
  ↓
SqlPPConnection
  ↓ translates SQL: PostgreSQL → SQL++
  ↓ double-quotes → backticks
  ↓ ON CONFLICT DO UPDATE → UPSERT
  ↓
Couchbase Analytics Service (port 8095)
```

## Requirements

- Couchbase Enterprise Edition with Analytics service enabled (`cbas`)
- Analytics memory quota: minimum 1024MB
- Couchbase JDBC driver: `com.couchbase.client:couchbase-jdbc-driver:1.0.5` (Maven Central)

## Remaining Work

Curity expects 15 tables (collections) to exist before it can operate:

```
accounts, credentials, sessions, tokens, delegations, nonces,
linked_accounts, devices, audit, dynamically_registered_clients,
database_clients, buckets, database_service_providers, entities,
entity_relations
```

These need to be:
1. Created as Couchbase collections in the `curity` bucket
2. Linked as Analytics datasets so the JDBC driver can query them

This initialization step mirrors HSQLDB's `init-db` initContainer which runs `hsqldb-create_database.sql`.

## License

Works within Curity's `jdbc[1]` license restriction — counts as a single JDBC datasource.
