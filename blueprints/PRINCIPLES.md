# Blueprint & Context Design Principles

## Concepts

**Blueprints** are atomic configuration steps. Each one configures a single service for a single capability. They are the building blocks.

**Contexts** are guided sequences — ordered sequences of CLI commands and blueprints that together achieve a goal. They provide the story and discoverability. A developer picks a context ("I want RBAC auth with Curity") and follows the steps.

## Principle 1: Services work out of the box

Service templates include sensible defaults. `b service add X && b service start X` gives you a working service. No "init" or "setup" blueprint required.

Blueprints exist to add capabilities, connect services, or wire cross-cutting concerns — not to make a service functional.

## Principle 2: Blueprints configure — they never add

Adding a service is the developer's action via `b service add`. Blueprints only configure, connect, and wire existing services. Prerequisites validate this with `service_exists` checks.

**Why:** The developer must control what exists in their project. If blueprints add services, you lose visibility into what's being pulled in — a configuration step shouldn't silently change your project's service inventory. Adding and configuring are different responsibilities: one is a project structure decision, the other is a capability decision.

## Principle 3: One service, one capability per blueprint

Each blueprint configures ONE service for ONE capability:
- `auth/curity-oauth` — configures Curity (OAuth)
- `auth/kong-phantom-token` — configures Kong (phantom token)
- `auth/curity-couchbase` — connects Curity to Couchbase

The test: can you name the target service AND the capability from the blueprint name alone?

**Why:** A blueprint that configures multiple services for multiple capabilities can't be reused partially. If OAuth setup lives inside a "full RBAC stack" blueprint, you can't get OAuth without RBAC. Keeping them atomic means each capability is defined once and composed freely — no duplication, no forced bundling.

## Principle 4: Composable, not monolithic

Blueprints are independent building blocks. No blueprint assumes another was run — it uses prerequisites to check. No blueprint duplicates another's work.

## Principle 5: Blueprints validate and fail early

Prerequisites are machine-checked before execution (`cluster_running`, `service_exists`, `app_exists`). If anything is missing, the blueprint fails with an actionable error ("Run: b service add X").

The git flow (branch → execute → squash merge) stays intact. File changes are auto-committed. On failure, the branch is left for inspection.

## Principle 6: Naming is `<category>/<target>-<capability>`

- **Category** = developer intent/domain: `auth/`, `database/`, etc.
- **Target** = primary service being configured
- **Capability** = what's being added/enabled

## Principle 7: Contexts provide the story

Blueprints don't know about sequences — they're atomic. Contexts describe the full journey with rich documentation:

```yaml
# contexts/auth-curity-rbac.yaml
name: RBAC Auth with Curity
description: Full authentication stack with role-based access control
caveats:
  - Requires a Curity license key (free at developer.curity.io)
  - Kong adds introspection latency (mitigated by caching)
tips:
  - Register test users at curity.<ns>.bluetext.localhost/authn/registration
  - Use Curity admin UI to inspect tokens and debug flows
pitfalls:
  - Don't skip auth/curity-oauth — phantom-token needs the introspect endpoint
  - The API trusts JWT from Kong without signature verification
steps:
  - run: b service add curity
    description: Add the Curity Identity Server
  - run: b service start curity
    description: Start Curity (HSQLDB, admin UI available immediately)
  - run: b bp run auth/curity-oauth
    description: Configure OAuth2 profiles, clients (flutter-app), and scopes
  - run: b service add kong
  - run: b bp run auth/kong-phantom-token --var target_services=api
    description: Route API traffic through Kong with token introspection
  - run: b service add api
  - run: b bp run auth/api-rbac
    description: Add role-guarded routes to the API
```

Contexts contain: steps, descriptions, caveats, tips, pitfalls, and next steps. They are the implementation guide. Discoverable via `b context list`, viewable via `b context show <id>`.

## Principle 8: Tags enable cross-cutting search

Every blueprint is tagged with the services it touches. Tag queries work across categories:
- `b bp list curity` — everything involving Curity
- `b bp list "curity AND couchbase"` — intersection

## Principle 9: Credentials are variables with dev defaults

Configurable (close to prod) with sensible defaults (easy collab). Secrets in gitignored files (`config/services/secret.*.yaml`). Committed config has dev defaults with caveats.

## Principle 10: Config fragments, not monoliths

Services that support multiple config files (like Curity's init directory) use separate fragments per blueprint. The service template ships a base config, and each blueprint adds its own fragment:

- Service template → `base-config.xml` (environments + datasource + processing)
- `auth/curity-oauth` → `oauth-config.xml` (profiles, clients, scopes)
- `auth/kong-phantom-token` → `phantom-token-client.xml` (introspection client)
- `auth/api-rbac` → `roles-scope.xml` (roles scope + client config)
- `auth/curity-couchbase` → overwrites `base-config.xml` (switches datasource)

**Why:** Each blueprint should own exactly the config it's responsible for — and nothing else. When a blueprint ships a complete config file that includes other blueprints' concerns, any change to a shared concern (like adding a scope) requires updating every blueprint that embeds it. Fragments give each blueprint a single place to define its contribution, matching the single-responsibility principle.

## Principle 11: Restarts are part of configuration

Blueprints restart services after writing config files. This is within scope of "configure" — writing a config that isn't picked up is not a complete configuration. A restart is the self-contained final step of making the config live.

**Why:** Configuration means making a capability active, not just writing files to disk. If the config isn't loaded, the blueprint hasn't finished its job. The restart doesn't cross responsibility boundaries — it's the natural completion of the configuration step, not lifecycle management.

## Principle 12: `see_also`, not `next_steps`

Blueprints show related blueprints after execution via `see_also`. There is no `next_steps` field.

**Why:** A `next_steps` field implies ordering and dependency — "after this, do that." That contradicts composability (principle 4). Blueprints are independent; they don't know or care what comes next. `see_also` is a lateral suggestion ("you might also want"), not a sequential instruction. Ordering belongs in contexts (principle 7), where the full sequence is explicitly designed.

## Principle 13: `service_wire` for cross-service wiring

When a blueprint needs to wire one service to another, use the `service_wire` step type. The upstream service's `connection_profiles` are the single source of truth for *what information is needed* to reach it — host, port, credentials, protocol. They define the *what*, not the *how*. How that information is used (SDK initialization, HTTP client setup, connection pooling, retry logic) is the responsibility of client libraries, application code, or config files.

```yaml
steps:
  - name: Connect service-config-manager to Couchbase
    tool: service_wire
    params:
      target: service-config-manager
      upstream: couchbase
      profile: admin
```

This reads the upstream's connection profile and injects the right env vars into the target's Deployment. Works for both internal services and external services.

**What `service_wire` is for:** Cross-service env var injection — when one service needs to know how to reach another. The connection profile defines the env vars (HOST, USERNAME, PASSWORD, etc.) and the blueprint declares which profile to use.

**What it's NOT for:** Self-contained env vars that are about the service itself (`ENVIRONMENT`, `POD_NAMESPACE`, `PASSWORD` for an admin UI) are fine hardcoded in the service template's k8s manifest. These don't describe a connection to another service.

**Services that read config files (not env vars):** Some services (e.g., Curity) read connection details from config files (XML, JSON) rather than env vars. For these, use the initContainer + `__PLACEHOLDER__` substitution pattern: the blueprint writes config files with `__COUCHBASE_HOST__` placeholders, and an initContainer does sed substitution from env vars at pod startup. The env var values come from the k8s manifest (dev defaults matching the connection profile).

**Why:** Connection information belongs with the service that exposes it, not duplicated across every blueprint that needs it. If Couchbase changes its connection surface, one update to its `connection_profiles` fixes all blueprints that use `service_wire`. Hardcoding connection details in blueprints creates silent drift.

**Prerequisite checks:** Use `upstream_exists` (checks both services and external services) or `external_service_exists` (checks external services only) to validate before connecting.
