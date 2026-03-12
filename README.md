# Bluetext Templates

This repo contains reusable service and app templates for [Bluetext CLI](https://github.com/bluetext-dev/bluetext-cli) (`b`). Projects add templates via `b service add <template-name>...` or `b app add <template-name>...` (both accept multiple names).

By default, the CLI auto-fetches this repo from GitHub and caches it at `~/.cache/bluetext/templates/`. Override with `--from` / `-f` flag or `templates_dir` in `~/.config/bluetext/config.yaml`.

## Directory Structure

```
services/
  <service-id>/
    template.yaml    # Template metadata (id, name, description, ports, dev_mode, dependencies)
    icon.svg         # Optional icon for CLI display
    config/          # K8s manifests (copied to project's config/services/)
      k8s.<service-id>.yaml
    code/            # Service source code (copied to project's code/services/<service-id>/)
      ...

apps/
  <app-id>/
    template.yaml    # Template metadata (id, name, description, ports, dev_mode, dependencies)
    icon.svg         # Optional icon for CLI display
    config/          # K8s manifests (copied to project's config/apps/)
      k8s.<app-id>.yaml
    code/            # App source code (copied to project's code/apps/<app-id>/)
      ...
```

Each template is a directory under `services/` or `apps/` named by its id.

### template.yaml

Each template must include a `template.yaml` at its root with metadata used by the CLI for listing and dependency resolution:

```yaml
id: <service-id>
name: Human-readable name
description: Short description of the template
ports:
  - <port>           # List of ports the service exposes (can be empty)
dev_mode: in-cluster # One of: in-cluster, mirrord, host-forward
dependencies:        # Other template ids this template depends on
  - <dependency-id>
```

### config/

Must contain `k8s.<service-id>.yaml` — a multi-document YAML with Deployment, Service, and optionally Ingress resources. The CLI parses this file to discover the service and determine how to run it.

### code/

Contains the service's source code. This entire directory is copied into the project at `code/services/<service-id>/`. Do NOT include build artifacts (`node_modules`, `target`, `build`, `.dart_tool`) — they are skipped during copy.

### config-files/ (optional)

Non-Kubernetes configuration files. When `b service add` runs, files from `config-files/` are copied directly into the project's `config/` directory (preserving subdirectory structure).

## How `b service add` Works

Running `b service add <name>...` in a project (accepts multiple template names):

1. Looks for `services/<name>/` in the templates repo (auto-fetched from GitHub or overridden with `--from` / `-f`)
2. Copies all files from `services/<name>/config/` into the project's `config/services/` directory
3. Copies `services/<name>/code/` into the project's `code/services/<name>/`
4. Copies `services/<name>/config-files/` (if present) into the project's `config/` directory
5. The service is immediately discoverable via `b service list` and runnable via `b service start`

## How `b app add` Works

Running `b app add <name>...` in a project (accepts multiple template names):

1. Looks for `apps/<name>/` in the templates repo (auto-fetched from GitHub or overridden with `--from` / `-f`)
2. Copies all files from `apps/<name>/config/` into the project's `config/apps/` directory
3. Copies `apps/<name>/code/` into the project's `code/apps/<name>/`
4. The app is immediately discoverable via `b app list` and runnable via `b app start`

## How the CLI Discovers Services

After a template is added to a project, the CLI discovers it automatically. On every run, the CLI **recursively** scans the project's `config/services/` directory (including subdirectories) for files matching the pattern `k8s.<id>.yaml`. Each matching file becomes a service with that `<id>`. The CLI then parses the YAML to extract `target_port` from the Service resource, and annotations + volumes from the Deployment resource (see "How the CLI Extracts Config" below).

This means:
- A service exists if and only if `config/services/k8s.<id>.yaml` exists
- The service id is derived from the filename, not from any field inside the YAML
- The `code/services/<id>/` directory is used at runtime (volume mounts, local dev commands) but is not required for discovery
- `b service list` shows all discovered services and their target ports

## How the CLI Discovers Apps

The CLI scans `config/apps/` for files matching `k8s.<id>.yaml`. Each matching file becomes an app. Apps differ from services in that they get an auto-generated namespace (`app-<id>`) and a network policy blocking cluster-internal traffic, enforcing isolation. The CLI commands for apps (`b app start`, `b app stop`, etc.) do not require a `-n` flag since the namespace is derived automatically.

## Apps vs Services

**Services** are backend components that run in shared namespaces and communicate freely with other services via cluster DNS. They require a `-n <namespace>` flag for most commands.

**Apps** are isolated client-facing applications (e.g. Flutter, React Native). Each app gets its own auto-generated namespace (`app-<id>`), a network policy restricting cluster-internal traffic, and simpler CLI commands (no `-n` flag needed). Use app templates for frontends that connect to backend services via ingress rather than direct cluster networking.

## Writing a K8s Manifest (`k8s.<id>.yaml`)

The manifest is a multi-document YAML (`---` separated). The CLI parses it to extract configuration. All resource names and labels must use the service id.

### Required: Deployment + Service

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: <service-id>
spec:
  replicas: 1
  selector:
    matchLabels:
      app: <service-id>
  template:
    metadata:
      labels:
        app: <service-id>
    spec:
      containers:
        - name: app
          image: <image>
          ports:
            - containerPort: <port>
          volumeMounts:
            - name: project
              mountPath: /app
      volumes:
        - name: project
          hostPath:
            path: /var/mnt/project/code/services/<service-id>
            type: Directory
---
apiVersion: v1
kind: Service
metadata:
  name: <service-id>
spec:
  type: ClusterIP
  selector:
    app: <service-id>
  ports:
    - port: 80
      targetPort: <port>
```

### Optional: Ingress

Add an Ingress to expose the service via `<service-id>.<namespace>.bluetext.localhost`:

```yaml
---
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: <service-id>
spec:
  ingressClassName: traefik
  rules:
    - host: <service-id>.{{NAMESPACE}}.bluetext.localhost
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: <service-id>
                port:
                  number: 80
```

`{{NAMESPACE}}` is replaced at deploy time with the target namespace.

## Deployment Annotations

The CLI reads annotations from `metadata.annotations` on the **Deployment** resource (not on the pod template). These annotations control how the service is deployed and run.

### `bluetext.io/dev-command`

- **Parsed from:** `Deployment.metadata.annotations`
- **Effect:** Switches the service out of in-cluster mode. Instead of deploying the full container, the CLI deploys a stub pod and runs this command locally on the host machine.
- **Without `bluetext.io/dev-mode`:** Uses mirrord mode — deploys a pause container as stub, then runs `mirrord exec --steal` to intercept cluster traffic and forward it to the local process.
- **Value:** The shell command to run locally (e.g. `cargo watch -x run`, `./bin/dev`). Executed via `zsh -lc` so PATH includes user tools. **Paths are relative to the service directory** (`code/services/<service-id>/`), not the project root.
- **Requires:** A `project` hostPath volume under `/var/mnt/project/` so the CLI can determine the local source directory (it strips the `/var/mnt/project/` prefix to get the relative path).

### `bluetext.io/dev-mode`

- **Parsed from:** `Deployment.metadata.annotations`
- **Only meaningful when `bluetext.io/dev-command` is also set.**
- **Values:**
  - `host-forward` — Instead of mirrord, deploys an `alpine/socat` proxy pod that forwards `TCP-LISTEN:<port>` to `TCP:host.k3d.internal:<port>`. The dev command runs natively on the host without any interception layer. Use this for tools incompatible with mirrord (e.g. Flutter/Gradle).
- **Omitted (default):** Uses mirrord mode when `dev-command` is present.

### `bluetext.io/port-forwards`

- **Parsed from:** `Deployment.metadata.annotations`
- **Format:** `<service>:<local-port>:<service-port>` — comma-separated for multiple entries.
- **Effect:** When the service starts (in **any deployment mode** — in-cluster, mirrord, or host-forward), the CLI spawns `kubectl port-forward svc/<service> <local-port>:<service-port>` for each entry, making cluster services available on localhost.
- **Example:** `"couchbase-sync-gateway:4984:80"` — makes Sync Gateway's public API (cluster port 80, which targets container port 4984) available at `localhost:4984` on the host.
- **PIDs:** Saved to `.bluetext/pids/<service-id>/<target-service>.port-forward.pid` and cleaned up on `b service stop`.
- **Use case:** Services (e.g. Flutter on Android emulator) that need to connect to other cluster services from the host. The emulator can reach forwarded ports via `10.0.2.2:<local-port>`.

### How the CLI Extracts Config

The CLI parses each `k8s.<id>.yaml` and extracts from the Deployment:

| Field | Source in YAML | ServiceConfig field |
|---|---|---|
| `target_port` | `Service.spec.ports[0].targetPort` | `target_port` |
| `dev_command` | `Deployment.metadata.annotations["bluetext.io/dev-command"]` | `dev_command` |
| `dev_mode` | `Deployment.metadata.annotations["bluetext.io/dev-mode"]` | `dev_mode` |
| `local_dir` | `Deployment.spec.template.spec.volumes[name=project].hostPath.path` (strips `/var/mnt/project/` prefix) | `local_dir` |
| `port_forwards` | `Deployment.metadata.annotations["bluetext.io/port-forwards"]` (parsed as comma-separated `service:localPort:servicePort`) | `port_forwards` |

### Three Deploy Modes

**1. In-cluster (default)** — No annotations. The full Deployment manifest is applied as-is. The container runs in k8s with hostPath volume mounts for live code. Any `bluetext.io/port-forwards` entries are started as background `kubectl port-forward` processes.

**2. mirrord** — Set `bluetext.io/dev-command`. The CLI:
  - Deploys a stub pod (pause container) + Service + Ingress
  - Starts any `bluetext.io/port-forwards` entries as background port-forward processes
  - Runs `mirrord exec --steal -t deployment/<id>` with the dev command locally
  - Saves PID to `.bluetext/pids/<id>.pid`, logs to `.bluetext/logs/<id>.log`

**3. host-forward** — Set both `bluetext.io/dev-command` and `bluetext.io/dev-mode: host-forward`. The CLI:
  - Deploys a socat proxy pod forwarding `<port>` to `host.k3d.internal:<port>` + Service + Ingress
  - Starts any `bluetext.io/port-forwards` entries as `kubectl port-forward` background processes (PIDs in `.bluetext/pids/<id>/`)
  - Runs the dev command natively on the host
  - Requires k3d port mapping for the target port (`-p <port>:<port>@loadbalancer`)
  - Saves PID to `.bluetext/pids/<id>.pid`, logs to `.bluetext/logs/<id>.log`
  - Use `registry.k8s.io/pause:3.9` as the Deployment image — the CLI replaces it with a socat proxy at deploy time, so the heavy runtime image is unnecessary

## Existing Template Examples

### Service Templates

| Template | Type | Port | Dev Mode | Description |
|---|---|---|---|---|
| `web-app` | Bun/Vite | 5173 | in-cluster | Frontend with hot reload via hostPath |
| `api` | Rust | 3030 | mirrord | Backend compiled locally, traffic proxied |
| `couchbase` | Couchbase Server | 8091 | in-cluster | Database with persistent data volume |
| `couchbase-sync-gateway` | Sync Gateway | 4984 | in-cluster | Couchbase Sync Gateway with namespace-templated config |
| `service-config-manager` | Python | — | in-cluster | Init service that configures Couchbase buckets and Sync Gateway databases |

### App Templates

| Template | Type | Port | Dev Mode | Description |
|---|---|---|---|---|
| `flutter` | Flutter | 8080 | host-forward | Mobile app running on host emulator (isolated namespace) |

## Template Extras

### `config-files/` Directory

Templates can include a `config-files/` directory for non-Kubernetes configuration files. When `b service add` runs, files from `config-files/` are copied directly into the project's `config/` directory (preserving subdirectory structure). This is used for:

- `config-files/couchbase-sync-gateway/config.json` — Sync Gateway bootstrap config
- `config-files/service-config-manager/managed-services.yaml` — service-config-manager manifest
- `config-files/service-config-manager/couchbase/couchbase.yaml` — Couchbase bucket definitions
- `config-files/service-config-manager/couchbase-sync-gateway/couchbase-sync-gateway.yaml` — Sync Gateway database definitions

### Config File Namespace Templating

K8s manifest files use `{{NAMESPACE}}` which the CLI replaces at deploy time. However, config files mounted via hostPath are **not** processed by the CLI. For config files that need the namespace, use the `__NAMESPACE__` placeholder and an initContainer to substitute it at pod startup:

```yaml
initContainers:
  - name: config-templater
    image: busybox:stable
    command: ['sh', '-c', 'sed "s/__NAMESPACE__/$POD_NAMESPACE/g" /config-template/config.json > /config/config.json']
    env:
      - name: POD_NAMESPACE
        valueFrom:
          fieldRef:
            fieldPath: metadata.namespace
```

Use `__NAMESPACE__` (not `{{NAMESPACE}}`) to avoid conflict with the CLI's manifest-level replacement.

## Conventions

- Service id = directory name = all K8s resource names = `app` label value
- Use `ClusterIP` services (port 80 → targetPort), not NodePort
- Add `tolerations` for control-plane scheduling if appropriate
- Use hostPath volumes under `/var/mnt/project/` for source code and cache directories
- Cache mounts go under `/var/mnt/project/.bluetext/cache/<service-id>/`
- Vite-based services need `allowedHosts: ['.bluetext.localhost']` in vite.config.js
