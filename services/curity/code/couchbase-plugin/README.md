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

## Implementation Status

Fully implemented: sessions, nonces, delegations, attributes, tokens, buckets, credentials, user accounts (core CRUD + pagination).

Remaining: devices, dynamic client registration, database clients, linked accounts.

## Requirements

- Couchbase Server with KV and Query services
- Curity license with Plugin SDK and custom data source types
