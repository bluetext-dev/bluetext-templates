# Blueprint & Context Design Principles

## Concepts

**Blueprints** are atomic configuration steps. Each one configures a single service for a single capability. They are the building blocks.

**Contexts** are guided sequences — ordered sequences of CLI commands and blueprints that together achieve a goal. They provide the story and discoverability. A developer picks a context ("I want RBAC auth with Curity") and follows the steps.

## Principle 1: Services work once their requirements are met
<a id="bp-1"></a>

A service that has been successfully added and has its declared requirements satisfied starts and functions. Services declare their requirements via `start_prerequisites` — verifiable checks that the CLI runs before starting. When a check fails, the error is actionable: it tells the developer exactly which blueprint to run.

Services that have no external requirements work immediately after adding. Services that depend on a capability (e.g., a data store) declare this dependency and guide the developer to the right blueprint.

Blueprints exist to add capabilities, connect services, or wire cross-cutting concerns. A service template ships only what the service itself needs — integration code for specific capabilities belongs with the blueprint that provides that capability.

## Principle 2: Blueprints configure — they never add
<a id="bp-2"></a>

Adding a service is the developer's action via `b service add`. Blueprints only configure, connect, and wire existing services. Prerequisites validate this with `service_exists` checks.

**Why:** The developer must control what exists in their system. If blueprints add services, you lose visibility into what's being pulled in — a configuration step shouldn't silently change your system's service inventory. Adding and configuring are different responsibilities: one is a system structure decision, the other is a capability decision.

## Principle 3: One service, one capability per blueprint
<a id="bp-3"></a>

Each blueprint configures ONE service for ONE capability:
- `auth/curity-oauth` — configures Curity (OAuth)
- `auth/kong-phantom-token` — configures Kong (phantom token)
- `auth/curity-couchbase` — connects Curity to Couchbase

The test: can you name the target service AND the capability from the blueprint name alone?

**Why:** A blueprint that configures multiple services for multiple capabilities can't be reused partially. If OAuth setup lives inside a "full RBAC stack" blueprint, you can't get OAuth without RBAC. Keeping them atomic means each capability is defined once and composed freely — no duplication, no forced bundling.

## Principle 4: Composable, not monolithic
<a id="bp-4"></a>

Blueprints are independent building blocks. No blueprint assumes another was run — it uses prerequisites to check. No blueprint duplicates another's work.

## Principle 5: Blueprints validate and fail early
<a id="bp-5"></a>

Prerequisites are machine-checked before execution (`cluster_running`, `service_exists`). If anything is missing, the blueprint fails with an actionable error ("Run: b service add X").

The git flow (branch → execute → squash merge) stays intact. File changes are auto-committed. On failure, the branch is left for inspection.

## Principle 6: Naming is `<category>/<target>-<capability>`
<a id="bp-6"></a>

- **Category** = developer intent/domain: `auth/`, `database/`, etc.
- **Target** = primary service being configured
- **Capability** = what's being added/enabled

## Principle 7: Contexts provide the story
<a id="bp-7"></a>

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
  - run: b bp run auth/curity-oauth --var deploy_target=auth/development-local --no-restart
  - run: b service add kong
  - run: b bp run auth/kong-phantom-token --var deploy_target=auth/development-local --var targets=api --no-restart
  - run: b service add api
  - run: b bp run auth/api-rbac --var deploy_target=auth/development-local --no-restart
  - run: b deploy auth/development-local
```

Contexts contain: steps, descriptions, caveats, tips, and pitfalls. They are the implementation guide. Discoverable via `b context list`, viewable via `b context show <id>`.

## Principle 8: Tags enable cross-cutting search
<a id="bp-8"></a>

Every blueprint is tagged with the services it touches. Tag queries work across categories:
- `b bp list curity` — everything involving Curity
- `b bp list "curity AND couchbase"` — intersection

## Principle 9: Credentials live in the secret store, not in YAML
<a id="bp-9"></a>

Secret values land at `~/.bluetext/secrets/<sys>--<hash>/{fixed/, variants/<run-spec-variant>/}/<value-id>` — one raw file per value, outside the system tree, never in git. Service variants reference them via `secrets.<name>.keys.<file>: secrets::<value-id>`; the deploy pipeline reads each file and projects it into a K8s `Secret`.

Blueprints never write secret values. When a blueprint needs a credential to exist, it relies on the value being present at the documented path; if the value is missing, deploy fails fast with the path the user must populate.

## Principle 10: Config fragments, not monoliths
<a id="bp-10"></a>

Services that support multiple config files (like Curity's init directory) use separate fragments per blueprint. The service template ships a base config, and each blueprint adds its own fragment:

- Service template → `base-config.xml` (environments + datasource + processing)
- `auth/curity-oauth` → `oauth-config.xml` (profiles, clients, scopes)
- `auth/kong-phantom-token` → `phantom-token-client.xml` (introspection client)
- `auth/api-rbac` → `roles-scope.xml` (roles scope + client config)
- `auth/curity-couchbase` → overwrites `base-config.xml` (switches datasource)

**Why:** Each blueprint should own exactly the config it's responsible for — and nothing else. When a blueprint ships a complete config file that includes other blueprints' concerns, any change to a shared concern (like adding a scope) requires updating every blueprint that embeds it. Fragments give each blueprint a single place to define its contribution, matching the single-responsibility principle.

## Principle 11: Restarts are part of configuration
<a id="bp-11"></a>

Blueprints restart services after writing config files. This is within scope of "configure" — writing a config that isn't picked up is not a complete configuration. A restart is the self-contained final step of making the config live.

**Why:** Configuration means making a capability active, not just writing files to disk. If the config isn't loaded, the blueprint hasn't finished its job. The restart doesn't cross responsibility boundaries — it's the natural completion of the configuration step, not lifecycle management.

## Principle 12: `see_also`, not `next_steps`
<a id="bp-12"></a>

Blueprints show related blueprints after execution via `see_also`. There is no `next_steps` field.

**Why:** A `next_steps` field implies ordering and dependency — "after this, do that." That contradicts composability (principle 4). Blueprints are independent; they don't know or care what comes next. `see_also` is a lateral suggestion ("you might also want"), not a sequential instruction. Ordering belongs in contexts (principle 7), where the full sequence is explicitly designed.

## Principle 13: Cross-service wiring goes through links
<a id="bp-13"></a>

When a service needs to consume another, the consumer's abstract declares a `links:` entry naming the upstream + connection-profile:

```yaml
# config/services/api/service.yaml — the consumer abstract
links:
  database:
    upstream: couchbase
    profile: data-writer
```

The deploy pipeline reads the upstream's `connection-profiles.<profile>` (which itself references `interfaces` and `secrets` on the bound variant), generates a per-link `Secret` + `ConfigMap`, and auto-mounts them at `/etc/bluetext/links/<link>/`. Consumers read `host`, `port`, `protocol`, and credential files — never env vars.

A blueprint that wires services together writes the `links:` entry into the consumer's abstract via `file_patch`. It never injects env vars into Deployments.

**Why:** Variant-aware consumers are a leak. If a consumer reads `COUCHBASE_HOST` env var, switching to a TLS-bearing Capella variant requires the consumer to also read `COUCHBASE_TLS_CRT` — the consumer becomes variant-aware. The link mount projects every variant's secret shape to the same path, so the consumer reads files and discriminates by file presence (`if exists("tls.crt")`) without rebuild.

**Prerequisite checks:** Use `service_exists` to validate the upstream abstract is present before patching the consumer's `links:`.
