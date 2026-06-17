# Couchbase JDBC Wrapper for Curity

Self-contained JDBC driver that enables Curity Identity Server to use Couchbase as a data store. All SQL goes through the N1QL Query service via HTTP — no external JDBC driver dependency.

## How It Works

Curity's JDBC plugin detects the SQL dialect by parsing the connection string prefix. This wrapper uses `jdbc:postgresql:couchbase://` which passes Curity's PostgreSQL dialect check while routing all queries to Couchbase N1QL.

```
Curity JDBC Plugin
  ↓ dialect detected: PostgreSQL ✓
  ↓
CouchbasePostgresDriver
  ↓ extracts host, catalog (bucket), user, password from URL
  ↓
SqlPPConnection (translates SQL, routes to N1QL)
  ├── DDL (CREATE TABLE, etc.) → NoOpPreparedStatement (silent no-op)
  └── All other SQL → QueryPreparedStatement
       ↓ translates PostgreSQL → SQL++
       ↓ resolves document KEY from table primary key metadata
       ↓ executes via HTTP POST to N1QL (port 8093)
       ↓ SELECT → QueryResultSet (parsed from N1QL JSON)
       ↓ INSERT/UPDATE/DELETE → mutation count
```

## SQL Translation

| PostgreSQL | Couchbase SQL++ |
|---|---|
| `"identifier"` | `` `identifier` `` |
| `FROM "sessions"` | ``FROM `curity`.`_default`.`sessions` `` |
| `INSERT INTO t (c1, c2) VALUES (?, ?)` | `INSERT INTO t (KEY, VALUE) VALUES ("key", {"c1": ?, "c2": ?})` |
| `INSERT ... ON CONFLICT ... DO UPDATE` | `UPSERT INTO ... (KEY, VALUE) VALUES (...)` |
| `CREATE TABLE`, `ALTER TABLE`, `SET` | Silent no-op (collections pre-created) |
| `UPDATE`, `DELETE`, `SELECT` | Passed through with backtick quoting + table qualification |

## Document KEY Resolution

Each of Curity's 17 tables has a primary key strategy that determines the Couchbase document KEY:

| Strategy | Tables | Behavior |
|---|---|---|
| PROVIDED | sessions, delegations, tokens, nonces, devices, audit, dynamically_registered_clients, entities, entity_relations, account_resource_relations, database_client_resource_relations | PK column value used as document KEY |
| COALESCE_UUID | accounts, buckets | Use provided PK if non-null, generate UUID if null |
| ALWAYS_UUID | credentials | Always generate UUID (matches HSQLDB trigger) |
| COMPOSITE | linked_accounts, database_clients, database_service_providers | PK columns concatenated with `::` |

The KEY is resolved at execution time from actual parameter values — no extra `?` placeholders are added.

## Uniqueness Enforcement

HSQLDB triggers enforce unique constraints on non-PK fields (e.g., `tenant_id + username` on accounts). Before INSERT, the wrapper runs a SELECT COUNT(*) check against each constraint and throws SQLSTATE 23505 on violation.

## Connection String

```
jdbc:postgresql:couchbase://couchbase-headless?catalog=curity&user=Administrator&password=password
```

## Build and Test

```bash
# Run unit tests (88 tests covering translation, ResultSet parsing, JSON serialization)
docker run --rm -v "$(pwd):/system" -w /system gradle:8-jdk21 gradle test

# Build the wrapper JAR (includes Gson, no external JDBC driver needed)
docker run --rm -v "$(pwd):/system" -w /system gradle:8-jdk21 gradle wrapperJar
```

The `wrapperJar` task produces `build/libs/couchbase-postgres-wrapper-<version>.jar` (version comes from `build.gradle`).

## Publishing

The blueprint pulls a pre-built jar from a public GCS bucket rather than compiling on every system add. This source tree is authoritative; the published jar is its build output.

**Publish target:**
```
gs://bluetext-cli-releases/plugins/couchbase-postgres-wrapper-<version>.jar
```

**Publishing a new version:**
1. Bump `version` in `build.gradle` (semver).
2. Run the test + jar tasks above.
3. Upload the jar:
   ```bash
   gcloud storage cp build/libs/couchbase-postgres-wrapper-<version>.jar \
     gs://bluetext-cli-releases/plugins/
   ```
4. Update the pinned version in the blueprint's `files/Dockerfile` (the `ADD` URL).
5. Commit the source change, the blueprint Dockerfile change, and verify the published jar all together.

**Why a GCS-published jar instead of in-Dockerfile compilation:** the wrapper is small (single JAR + Gson), iteration is rare, and pulling a pinned URL keeps system Docker builds fast and deterministic. The native plugin variant (sibling blueprint `auth/curity-couchbase`) takes the opposite tradeoff — it compiles from source in a multi-stage Docker build because the plugin pulls in the full Couchbase Java SDK plus Jackson modules, is iterated more actively, and pinning each system to the source-tree state in the blueprint is safer than pinning to a published artifact.

## Requirements

- Couchbase Enterprise Edition with Query service (`n1ql`)
- Primary indexes on all collections (provisioned via the couchbase api-config bundle at deploy time)
- JDK 21 (matching Curity's runtime)

## License Compatibility

Fits Curity licenses that allow JDBC data sources (e.g. `jdbc[1]`) — Curity sees a standard JDBC data source. For licenses that allow custom data-source types, see the native Couchbase data-source plugin in the sibling blueprint `auth/curity-couchbase`.
