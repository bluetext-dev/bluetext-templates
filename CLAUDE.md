# Bluetext Templates

This repo contains reusable service templates for [Bluetext CLI](https://github.com/user/bluetext-cli) (`b`). Projects add templates via `b service add <template-name>`.

## Directory Structure

```
services/
  <service-id>/
    config/          # K8s manifests (copied to project's config/)
      k8s.<service-id>.yaml
    code/            # Service source code (copied to project's code/services/<service-id>/)
      ...
```

Each template is a directory under `services/` named by its service id.

### config/

Must contain `k8s.<service-id>.yaml` — a multi-document YAML with Deployment, Service, and optionally Ingress resources. The CLI parses this file to discover the service and determine how to run it.

### code/

Contains the service's source code. This entire directory is copied into the project at `code/services/<service-id>/`. Do NOT include build artifacts (`node_modules`, `target`, `build`, `.dart_tool`) — they are skipped during copy.

## How `b service add` Works

Running `b service add <name>` in a project:

1. Looks for `services/<name>/` in this template repo
2. Copies all files from `services/<name>/config/` into the project's `config/services/` directory
3. Copies `services/<name>/code/` into the project's `code/services/<name>/`
4. The service is immediately discoverable via `b service list` and runnable via `b service start`

The `--from` flag can point to a different templates repo: `b service add <name> --from /path/to/other-templates`

## How the CLI Discovers Services

After a template is added to a project, the CLI discovers it automatically. On every run, the CLI scans the project's `config/services/` directory for files matching the pattern `k8s.<id>.yaml`. Each matching file becomes a service with that `<id>`. The CLI then parses the YAML to extract `target_port` from the Service resource, and annotations + volumes from the Deployment resource (see "How the CLI Extracts Config" below).

This means:
- A service exists if and only if `config/services/k8s.<id>.yaml` exists
- The service id is derived from the filename, not from any field inside the YAML
- The `code/services/<id>/` directory is used at runtime (volume mounts, local dev commands) but is not required for discovery
- `b service list` shows all discovered services and their target ports

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

Add an Ingress to expose the service via `<service-id>.<namespace>.local.bluetext.io`:

```yaml
---
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: <service-id>
spec:
  ingressClassName: traefik
  rules:
    - host: <service-id>.{{NAMESPACE}}.local.bluetext.io
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
- **Value:** The shell command to run locally (e.g. `cargo watch -x run`, `bun run dev --host`). Executed via `zsh -lc` so PATH includes user tools.
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
- **Effect:** When the service starts (in host-forward or mirrord mode), the CLI spawns `kubectl port-forward svc/<service> <local-port>:<service-port>` for each entry, making cluster services available on localhost.
- **Example:** `"couchbase-sync-gateway:4984:80"` — makes Sync Gateway's public API (cluster port 80, which targets container port 4984) available at `localhost:4984` on the host.
- **PIDs:** Saved to `.bluetext/pids/<service-id>/<target-service>.port-forward.pid` and cleaned up on `b service stop`.
- **Use case:** Host-forward services (e.g. Flutter on Android emulator) that need to connect to other cluster services. The emulator can reach forwarded ports via `10.0.2.2:<local-port>`.

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

**1. In-cluster (default)** — No annotations. The full Deployment manifest is applied as-is. The container runs in k8s with hostPath volume mounts for live code.

**2. mirrord** — Set `bluetext.io/dev-command`. The CLI:
  - Deploys a stub pod (pause container) + Service + Ingress
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

| Template | Type | Port | Dev Mode | Description |
|---|---|---|---|---|
| `web-app` | Bun/Vite | 5173 | in-cluster | Frontend with hot reload via hostPath |
| `api` | Rust | 3030 | mirrord | Backend compiled locally, traffic proxied |
| `bluetext-ui` | Bun/Vite | 5175 | in-cluster | Control plane UI |
| `flutter-dev-container` | Flutter | 8080 | host-forward | Mobile app running on host emulator |
| `couchbase` | Couchbase Server | 8091 | in-cluster | Database with persistent data volume |

## Conventions

- Service id = directory name = all K8s resource names = `app` label value
- Use `ClusterIP` services (port 80 → targetPort), not NodePort
- Add `tolerations` for control-plane scheduling if appropriate
- Use hostPath volumes under `/var/mnt/project/` for source code and cache directories
- Cache mounts go under `/var/mnt/project/.bluetext/cache/<service-id>/`
- Vite-based services need `allowedHosts: ['.local.bluetext.io']` in vite.config.js
