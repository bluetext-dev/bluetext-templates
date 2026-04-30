# Couchbase Data Access Provider for Curity

Native Curity plugin that registers `couchbase` as a first-class data-source type via the Data Access Provider (DAP) SDK. Curity stores accounts, credentials, sessions, tokens, delegations, nonces, devices, dynamic clients, database clients, linked accounts, and bucket-style key/value records directly in Couchbase.

The sibling blueprint `auth/curity-couchbase-jdbc` exposes Couchbase through Curity's JDBC plugin slot — useful where the license restricts data sources to `jdbc[*]`.

## How Curity Loads It

Curity's plugin system uses Java SPI. This plugin's `META-INF/services/se.curity.identityserver.sdk.plugin.descriptor.DataAccessProviderPluginDescriptor` points at `CouchbaseDataAccessProviderDescriptor`, whose `getPluginImplementationType()` returns `"couchbase"`. At boot, Curity:

1. Discovers the plugin JAR under `/opt/idsvr/usr/share/plugins/couchbase/`.
2. Compiles a config schema from the `CouchbaseDataAccessProviderConfiguration` interface (see [Configuration](#configuration)).
3. Validates the license against the registered type (see [License Requirements](#license-requirements)).
4. Instantiates a `CouchbaseExecutor` per `<data-source>` of type `couchbase` declared in init XML or admin config.
5. Routes data-access calls (account CRUD, token lookup, session storage, …) to the matching provider class.

## License Requirements

The Curity Enterprise Edition image (`curity.azurecr.io/curity/idsvr`) is the runtime binary; the license key gates which features and data-source types you may use.

For this plugin to instantiate a working `<couchbase>` data source:

- The license's **`data-sources` feature** must allow the `couchbase` type — either unrestricted (`restrictions: []`) or with `couchbase` explicitly listed in the restrictions array. A license that restricts data sources to `jdbc[*]` does not allow it.
- The license must be the **full signed JWT** — three base64url segments separated by dots: `<header>.<payload>.<signature>`. The Curity portal sometimes ships only the payload segment; that decodes as readable JSON but is rejected at runtime with `LicenseKeyValidationCallback - License was the wrong issuer or had not subject` followed by ConfDStarter aborting CDB validation.
- The official Curity-issued JSON file (e.g. `cillers.com_Trial_<date>.json`) carries the full JWT in its `License` field. Use that file's `License` value as the license key — the bluetext blueprint wraps it as `{"License":"<JWT>"}` into the `curity-license` Secret automatically.

To inspect what a license entitles, base64url-decode the payload segment and look for the `Features` entry whose `feature` is `data-sources`.

## Configuration

The plugin's config schema is generated from `CouchbaseDataAccessProviderConfiguration`. Curity translates camelCase Java getters to kebab-case XML elements under namespace `https://curity.se/ns/ext-conf/couchbase`.

| XML element | Type | Default | Description |
|---|---|---|---|
| `<host>` | string | required | Couchbase host (DNS name or IP). Cluster discovery via DNS SRV is supported. |
| `<use-tls>` | boolean | `true` | When `true`, connects via `couchbases://`; otherwise `couchbase://`. |
| `<user-name>` | string | required | Couchbase user with read/write on the configured bucket. |
| `<password>` | string | required | Password for the Couchbase user. |
| `<bucket>` | string | `curity` | Bucket holding all Curity collections. Auto-created on first connect if missing. |
| `<bucket-ram-quota-mb>` | long | `256` | RAM quota used when the plugin creates the bucket on first connect. No effect if the bucket already exists. |
| `<scope>` | string | `_default` | Scope inside the bucket. Auto-created if missing. |
| `<claim-query>` | string | required | N1QL query for the attribute provider. Supports the placeholders below. |
| `<use-scim-parameter-names>` | boolean | `true` | When `true`, account-attribute lookups use SCIM names (`emails`, `phoneNumbers`, `userName`); otherwise their flat counterparts (`email`, `phone`, `username`). |
| `<sessions-ttl-retain-duration>` | long (seconds) | `86400` | Extra retain time past `sessions` TTL. |
| `<nonces-ttl-retain-duration>` | long (seconds) | `86400` | Extra retain time past `nonces` TTL. |
| `<delegations-ttl-retain-duration>` | long (seconds) | `31536000` | Extra retain time past `delegations` TTL. |
| `<tokens-ttl-retain-duration>` | long (seconds) | `172800` | Extra retain time past `tokens` TTL. |
| `<devices-ttl-retain-duration>` | long (seconds) | `2592000` | Extra retain time past `devices` TTL. |

### Claim Query Placeholders

The `<claim-query>` is a literal N1QL query with four string-replace tokens, applied in this order: `:bucket`, `:scope`, `:collection`, `:subject`. There is no parameter binding — they are textual substitution markers.

A working default (matching the doc-key convention below):

```
SELECT `:collection`.* FROM `:bucket`.`:scope`.`:collection` WHERE META().id = "node::user::personal_info:::subject"
```

After substitution at request time:

```
SELECT `curity-accounts`.* FROM `curity`.`_default`.`curity-accounts` WHERE META().id = "node::user::personal_info::alice"
```

## Auto-Provisioning

The plugin owns its full data-store lifecycle. On first connect, `CouchbaseExecutor.init()` runs the following sequence against the configured `<host>`/credentials:

1. **Wait for cluster manager + global config** — `cluster.waitUntilReady(MANAGER, 2 min)` blocks until Couchbase has finished bootstrap and the management endpoint can accept config requests. Without this step the SDK races the cluster's `GLOBAL_CONFIG_LOAD_IN_PROGRESS` state.
2. **Create the bucket** if missing — `cluster.buckets().createBucket(...)` with the configured `<bucket-ram-quota-mb>` and `flushEnabled=true`. If the bucket already exists, `BucketExistsException` is caught and the existing bucket is used as-is (settings are not reconciled).
3. **Wait for bucket KV/Query/GSI** — `bucket.waitUntilReady(60s)` blocks until the new (or existing) bucket's services are responsive. Without this step subsequent collection-management calls would race `FeatureNotAvailableException`.
4. **Create the ten Curity collections** under `<scope>` if missing (collisions handled idempotently).
5. **Create a primary index on each collection** and wait for it to come online, with bounded exponential retry against transient `InternalServerFailureException` from the GSI service.

What this means for deployment: the plugin needs an **initialized Couchbase cluster** with credentials matching `<user-name>` / `<password>`, but no external bucket-provisioning step. Bucket sizing for production should be handled either by pre-creating the bucket with proper RAM quota and replica settings (the plugin will detect it and skip creation) or by tuning `<bucket-ram-quota-mb>` in the data-source config.

## Collections

| Provider | Collection | Operations |
|---|---|---|
| Sessions | `curity-sessions` | CRUD with TTL expiry |
| Nonces | `curity-nonces` | Save, consume, expire with TTL |
| Delegations | `curity-delegations` | CRUD, query by owner/hash/status, pagination |
| Tokens | `curity-tokens` | CRUD with expiry, status updates |
| Attributes | configurable via `<claim-query>` | Claim lookup with placeholder substitution |
| Credentials | `curity-accounts` | Get/store/delete hashed passwords |
| Buckets | `curity-buckets` | Key-value storage by subject + purpose |
| User Accounts | `curity-accounts` | CRUD, search by username/email/phone, pagination |
| Linked Accounts | `curity-linked-accounts` | Link, list, resolve, delete |
| Devices | `curity-devices` | CRUD, query by account, pagination |
| Dynamic Clients | `curity-dynamically-registered-clients` | CRUD for RFC 7591 DCR |
| Database Clients | `curity-database-clients` | Profile-scoped CRUD, filtered pagination |
| Pageable Accounts | `curity-accounts` | Extends user accounts with offset/limit pagination |

## Document Key Convention

User accounts are stored at the doc key `node::user::personal_info::<username>`. The `CouchbaseExecutor` builds this key directly from the account's `userName` on insert/update/delete and on direct-key reads (used by `findByUsername`, `findByAccountId`, `updatePassword`). Anything bypassing the plugin (data seeders, migrations, ad-hoc N1QL) must follow the same convention or those calls will return empty results.

Other provider classes use their own key shapes — most are simple single-segment keys derived from the entity's primary identifier (session id, token hash, delegation id, etc.).

## Build

Java 21 with the bundled Gradle wrapper, or via Docker:

```bash
# Local Java 21
./gradlew createPluginDir

# Or via Docker (matches the publish pipeline)
docker run --rm -v "$(pwd):/system" -w /system gradle:8-jdk21 gradle createPluginDir
```

Output lands in `build/curity-couchbase-plugin/` and contains the plugin jar plus its runtime dependencies (Couchbase client, Jackson, Reactor). The contents go at `/opt/idsvr/usr/share/plugins/couchbase/` inside the Curity image.

When deployed via the bluetext blueprint, the Curity Dockerfile pulls a pre-built tarball from GCS — see Publishing below. Developers using the blueprint don't compile anything.

## Publishing

The blueprint pulls a pre-built tarball from a public GCS bucket rather than compiling per-system. This source tree is authoritative; the published tarball is its build output.

**Publish target:**
```
gs://bluetext-cli-releases/plugins/curity-couchbase-plugin-<version>.tar.gz
```

**Publishing a new version:**
1. Bump `version` in `build.gradle` (semver).
2. Build the plugin directory:
   ```bash
   docker run --rm -v "$(pwd):/system" -w /system gradle:8-jdk21 gradle createPluginDir
   ```
3. Tarball it (entries at the root, no top-level dir, no macOS AppleDouble sidecars):
   ```bash
   COPYFILE_DISABLE=1 tar -czf build/curity-couchbase-plugin-<version>.tar.gz \
     -C build/curity-couchbase-plugin .
   ```
   `COPYFILE_DISABLE=1` is critical on macOS: BSD `tar` otherwise embeds 163-byte `._<filename>` HFS+ metadata stubs alongside each jar, and Curity's plugin loader tries to read every file in the plugin dir as a JAR and fails on those with `ZipException: zip END header not found`.
4. Upload:
   ```bash
   gcloud storage cp build/curity-couchbase-plugin-<version>.tar.gz \
     gs://bluetext-cli-releases/plugins/
   ```
5. Update the pinned version in the blueprint's `files/Dockerfile` (the `ADD` URL).
6. Commit the source change, the blueprint Dockerfile change, and confirm the published tarball all together.

**Why a published tarball:** the plugin pulls in the full Couchbase Java SDK plus Jackson and Reactor (~16MB compressed). Pinning each system to a specific published artifact keeps Docker builds fast and deterministic, and gives the plugin a real release surface independent of any single user's checkout. Compile-from-source remains supported for plugin development — see the Build instructions above — and the source itself is what's pinned in this directory.

## Verified Against

- Curity Identity Server 11.1.1 (image `curity.azurecr.io/curity/idsvr:latest`)
- Couchbase Server 7.6.6 Enterprise Edition (KV + Query + GSI services)
- `com.couchbase.client:java-client` 3.4.2
- Java 21
- Curity Plugin SDK 11.0.2

## Requirements

- Couchbase Server with KV, Query, and GSI services reachable from the Curity pod, and an initialized cluster (i.e. an admin/RBAC user matching `<user-name>` / `<password>`).
- A Curity license whose `data-sources` feature allows the `couchbase` type (see [License Requirements](#license-requirements)).
- The configured user must have privileges to create buckets, scopes, collections, and primary indexes if those do not already exist. (For pre-provisioned environments, lower-privileged credentials are sufficient.)
