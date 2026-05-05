# Bluetext Templates

Reusable service, blueprint, and context templates for the [Bluetext CLI](https://github.com/bluetext-dev/bluetext) (`b`).

The CLI auto-fetches this repo from GitHub and caches it at `~/.cache/bluetext/templates/`. Override with `--from <path>` on any command, or set `templates-dir` in `~/.config/bluetext/config.yaml`.

```bash
b service add api couchbase           # add service templates from this repo
b store add couchbase                 # add a store (service + client + cargo feature, atomic)
b client add couchbase                # add a typed client library
b blueprint run auth/curity-couchbase --var deploy_target=auth/development-local
b context run auth-curity-rbac
```

## Repository layout

```
services/<abstract-id>/
  template.yaml                       # metadata: id, name, ports, dev-modes, start_prerequisites
  icon.svg                            # optional CLI/UI icon
  config/<abstract-id>/
    service.yaml                      # contract — interfaces, connection-profiles, links, use:
    <variant-id>.yaml                 # implementation — port, host?, secrets, values, external?
  manifests/<abstract-id>/<variant-id>/
    base/                             # invariant kustomize base
    _default/                         # required fallback overlay
    <run-spec-variant>/               # optional per-rsv overlay (takes precedence over _default)
  code/<abstract-id>/<variant-id>/    # source dir copied to system code/services/...
                                      # single-variant collapses to code/<abstract-id>/

clients/<id>/
  template.yaml                       # metadata: id, language, accepts (upstream interfaces)
  src/...                             # client library source (Rust crate, TS package, ...)

blueprints/<category>/<id>/
  blueprint.yaml                      # prerequisites, variables, steps
  files/                              # static assets referenced by file_write steps
  fragments/                          # config fragments referenced by file_patch steps

contexts/<id>.yaml                    # ordered b commands + blueprint runs

PRINCIPLES.md                         # blueprint + context design rules
```

Every service template is an **abstract + variants** pair under `services/<abstract-id>/`. The abstract names what the service is (interfaces, connection-profiles); each variant names how (image, port, secrets shape, in-cluster vs external).

## Two-layer service model

Every consumer of a service reads from `/etc/bluetext/links/<link>/` and gets the same files regardless of which variant is bound — the deploy pipeline mounts both Opaque and typed (`kubernetes.io/tls`) Secrets uniformly. A consumer discriminates by file presence (`if exists("tls.crt")`), not by build-time branching.

To switch providers, edit one line on the abstract:

```yaml
# config/services/couchbase/service.yaml
use:
  server:  [development, test]        # in-cluster Couchbase Server
  capella: [staging, production]      # external Capella tenant
```

`b deploy app/staging-us-east` then runs against the Capella variant; `b deploy app/development-local` runs against Server.

## services/<id>/template.yaml

Metadata read by `b service add` and the CLI/UI service catalog.

```yaml
id: api
name: Rust API
description: Backend API with Axum, mirrord dev mode
language: rust
ports:
  - 3030
dev-modes:
  development:
    mode: mirrord                     # in-cluster | mirrord | host-forward
    command: cargo watch -x run
start_prerequisites:
  - description: cargo-watch installed for auto-rebuild on file changes
    check: command_on_path
    params:
      name: cargo-watch
    hint: |
      Install cargo-watch so `cargo watch -x run` recompiles on save:
        cargo install cargo-watch
```

`dev-modes:` entries merge into the system's `config/dev-modes.yaml` at `b service add` time, keyed by run-spec-variant. The variant id (`development` here) refers to a run-spec-variant, not a service variant.

`start_prerequisites` are checks the CLI runs before deploy. When a check fails, the `hint:` is printed verbatim — make it actionable (the exact command to run).

## services/<id>/config/<id>/

The abstract + variant contract.

### service.yaml — the abstract

```yaml
id: api
use:
  api: [_default]                     # bind variant `api` to every run-spec-variant
interfaces:
  - http
connection-profiles:
  http:
    interface: http
links:
  database:
    upstream: couchbase               # name of an abstract this service depends on
    profile: data-writer              # connection-profile on that upstream
```

- `use:` maps each defined variant id to a list of run-spec-variants. Use `_default` to mean "every run-spec-variant that no other variant claims."
- `interfaces:` is a bare list — names the network surfaces the service exposes. The variant fills in port + protocol per name.
- `connection-profiles:` declare the consumer-facing views of those interfaces. `secret: variant::<name>` looks up a same-named `secrets:` block on the bound variant — useful when the secret shape differs per variant (Opaque username/password vs typed `kubernetes.io/tls`).
- `links:` (optional) declares upstream dependencies. The deploy pipeline auto-mounts the upstream's connection-profile at `/etc/bluetext/links/<link>/`.

### <variant-id>.yaml — an implementation

```yaml
# Single-variant case: variant id == abstract id
id: api
implements: api
interfaces:
  http:
    port: 3030
    protocol: http
```

```yaml
# Multi-variant: server (in-cluster) + capella (external)
id: server
implements: couchbase
interfaces:
  client: { port: 11210, protocol: couchbase }
  admin:  { port: 8091,  protocol: https }
secrets:
  auth:
    keys:
      username: secrets::couchbase-server-username
      password: secrets::couchbase-server-password
```

```yaml
id: capella
implements: couchbase
external: true                        # no manifests applied; consumer-side ConfigMap + Secret only
host: cb-EDIT-ME.cloud.couchbase.com
interfaces:
  client: { port: 11207,  protocol: couchbases }
  admin:  { port: 18091,  protocol: https }
secrets:
  auth:
    type: kubernetes.io/tls
    keys:
      tls.crt: secrets::api-couchbase-client-cert
      tls.key: secrets::api-couchbase-client-key
```

- `implements:` must match the directory's abstract id.
- `external: true` flips the variant out of in-cluster manifest generation. The deploy pipeline still emits the consumer-side `Secret` + `ConfigMap` so consumers see the same `/etc/bluetext/links/<link>/` shape.
- `host:` is a value-leaf; supply a string literal or a polymorphic form like `{ _: run-spec-variant-id, staging: cb-staging.cloud.couchbase.com, production: cb-prod.cloud.couchbase.com }`.
- `secrets.<name>.keys.<file>: secrets::<value-id>` references a value at `~/.bluetext/secrets/<sys>--<hash>/{fixed,variants/<rsv>}/<value-id>`. The deploy pipeline reads each file and projects it as a Secret entry.

## services/<id>/manifests/<id>/<variant>/

Per-variant kustomize layout:

```
manifests/<abstract>/<variant>/
├── base/                # invariant resources (Deployment, Service, Ingress)
├── _default/            # required fallback overlay
└── <run-spec-variant>/  # optional per-rsv overlay; takes precedence over _default
```

`_default/` is required (PROPOSAL §D17). The deploy pipeline picks the most specific overlay for the active run-spec-variant; absent a per-rsv overlay, `_default/` runs.

### base/ conventions

- Resource names + labels match the **variant id** (the bound variant, not the abstract). The CLI rewrites image references to `<abstract>-<variant>:latest` for k3d or registry-prefixed for remote clusters.
- Use `imagePullPolicy: Never` for the k3d path; the deploy pipeline imports built images into the cluster.
- HostPath volumes for source mount under `/var/mnt/system/` — the CLI rewrites these to `/var/mnt/systems/<system-rel>/` at apply time.
- Cache volumes (Rust `target/`, `node_modules`) under `/var/mnt/workspace/.bluetext/cache/<abstract>/`.
- Namespace placeholders: `{{NAMESPACE}}` in manifest YAML (CLI substitutes); `__NAMESPACE__` in mounted config files (use an initContainer + `sed` for substitution at pod startup).

### Ingress

Add an Ingress in `base/` to expose the service externally:

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: <abstract-id>
spec:
  ingressClassName: traefik
  rules:
    - host: <abstract-id>.{{NAMESPACE}}.bluetext.localhost
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: <abstract-id>
                port:
                  number: 80
```

## services/<id>/code/<id>/<variant>/

Source code copied into the system at `code/services/<abstract>/<variant>/`. For single-variant abstracts, the path collapses to `code/services/<abstract>/`.

Skip build artifacts (`node_modules`, `target`, `build`, `.dart_tool`) — the CLI's copy step ignores them by default.

## Sharing one image across abstracts — `vimage:`

When N abstracts genuinely share one image — multi-tenant deploys (`api-tenant-a`, `api-tenant-b`, …) or bluetext-built sidecars consumed by many services — the variant declares `vimage: <id>` instead of inline `base/code/archs`:

```yaml
# config/services/api-tenant-a/api-tenant-a.yaml
id: api-tenant-a
implements: api-tenant-a
vimage: api-runtime
interfaces: { http: { port: 3030, protocol: http } }
```

The shared image lives at `config/vimages/api-runtime.yaml` (in the system, not in the template repo — vimages are user-side composition). The deploy pipeline builds the image once and tags it for every consumer. The CLI scaffolds a vimage with `b vimage add <id>`. See [`cli/docs/VIMAGE.md`](https://github.com/bluetext-dev/bluetext/blob/development/docs/VIMAGE.md) for the full pattern.

## clients/<id>/

A typed client library a service can use to consume an upstream's connection-profile.

```yaml
# clients/couchbase/template.yaml
id: couchbase
name: Couchbase Client
description: Rust Couchbase client library with generic CRUD entity trait
language: rust
accepts:
  - upstream: couchbase
    profile: data-writer
```

`b client add <id>` copies the library into the system. The `accepts:` list declares which `(upstream, profile)` pairs the client wires against.

For Rust single-file clients, ship the file at `clients/<id>/<id>.rs`; `b client add` slots it into the system's shared `code/clients/src/<id>.rs`. For multi-file clients, ship a directory and the CLI mirrors it under `code/clients/<id>/`.

## blueprints/<category>/<id>/

Atomic configuration steps. Each blueprint configures one service for one capability and ships with prerequisites, typed variables, and ordered steps.

```yaml
name: Curity OAuth profiles
description: Configure Curity OAuth2 profiles, clients, and scopes
tags: [auth, curity, oauth]
prerequisites:
  - description: Curity service exists
    check: service_exists
    params:
      id: curity
variables:
  - name: deploy_target
    alias: d
    type: deploy-target
    description: '<run-spec>/<deploy-name> — context for service restart steps'
    required: true
restarts: [curity]
steps:
  - name: Write OAuth fragment
    tool: file_write
    params:
      file: config/curity/oauth-config.xml
      content_file: files/oauth-config.xml
  - name: Restart curity
    tool: service_restart
    params:
      id: curity
      deploy_target: "{{deploy_target}}"
```

Step tools — see [cli/docs/BLUEPRINTS.md](https://github.com/bluetext-dev/bluetext/blob/development/docs/BLUEPRINTS.md) for the full reference.

## contexts/<id>.yaml

A guided sequence of CLI commands and blueprint runs.

```yaml
name: RBAC Auth with Curity
description: Full authentication stack with role-based access control
caveats:
  - Requires a Curity license key (free at developer.curity.io)
steps:
  - run: b service add curity
  - run: b bp run auth/curity-oauth --var deploy_target=auth/development-local --no-restart
  - run: b service add kong
  - run: b bp run auth/kong-phantom-token --var deploy_target=auth/development-local --no-restart
  - run: b deploy auth/development-local
```

Contexts pass `--no-restart` to blueprint steps to batch config writes, then trigger one explicit `b deploy` at the end.

See `PRINCIPLES.md` for the design rules every blueprint and context follows.
