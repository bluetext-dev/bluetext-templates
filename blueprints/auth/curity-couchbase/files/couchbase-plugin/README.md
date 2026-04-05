# Couchbase Data Access Provider for Curity

Native Curity plugin that registers `couchbase` as a data source type via the Data Access Provider (DAP) SDK.

## How It Works

Curity's plugin system uses Java SPI. This plugin implements `DataAccessProviderPluginDescriptor` and registers type `"couchbase"`. On startup, Curity discovers the plugin, generates an admin UI form from the configuration interface, and delegates data operations to the plugin's provider classes. This is a first-class data source type with its own configuration schema.

For a comparison with the JDBC-based approach, see the [JDBC wrapper](../couchbase-jdbc-wrapper/).

## Curity Image and License

The Bluetext Curity template ships with the **Enterprise Edition image** (`curity.azurecr.io/curity/idsvr`). The image is the runtime binary. The **license key** determines which features and data source types are enabled.

Curity licenses specify which **data source types** are available and how many instances of each type. This plugin registers a new data source type (`couchbase`), which requires a license that:

1. **Includes the Plugin SDK** — Curity loads plugin JARs at startup
2. **Includes custom data source types** — the `couchbase` type is available as a data source

For licenses limited to JDBC, see the [JDBC wrapper](../couchbase-jdbc-wrapper/) which fits Couchbase into a standard JDBC slot.

## Build

Requires Java 21. The Gradle wrapper is included:

```bash
./gradlew createPluginDir
```

This compiles the plugin and copies all runtime JARs (plugin + Couchbase client + Jackson modules) into `build/curity-couchbase-plugin/`. Copy this directory into Curity's plugin directory:

```
/opt/idsvr/usr/share/plugins/couchbase/
```

When deployed via Bluetext, the blueprint handles this automatically through a multi-stage Docker build — the developer never builds manually.

## Providers

All `DataAccessProviderPluginDescriptor` provider types are implemented:

| Provider | Collection | Operations |
|----------|-----------|------------|
| Sessions | `curity-sessions` | CRUD with TTL expiry |
| Nonces | `curity-nonces` | Save, consume, expire with TTL |
| Delegations | `curity-delegations` | CRUD, query by owner/hash/status, pagination |
| Tokens | `curity-tokens` | CRUD with expiry, status updates |
| Attributes | configurable | Claim query with placeholder substitution |
| Credentials | `curity-accounts` | Get/store/delete hashed passwords |
| Buckets | `curity-buckets` | Key-value storage by subject+purpose |
| User Accounts | `curity-accounts` | CRUD, search by username/email/phone, pagination |
| Linked Accounts | `curity-linked-accounts` | Link, list, resolve, delete |
| Devices | `curity-devices` | CRUD, query by account, pagination |
| Dynamic Clients | `curity-dynamically-registered-clients` | CRUD for RFC 7591 DCR |
| Database Clients | `curity-database-clients` | Profile-scoped CRUD, filtered pagination |
| Pageable Accounts | `curity-accounts` | Extends user accounts with advanced pagination/filtering |

## Requirements

- Couchbase Server with KV and Query services
- Curity SDK 11.0.2
- Java 21
- Curity license with Plugin SDK and custom data source types
