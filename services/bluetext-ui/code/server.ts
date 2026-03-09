import { readFileSync, readdirSync, existsSync } from "fs";
import { join } from "path";
import { parseAllDocuments } from "yaml";

const PORT = 3100;

// ---------------------------------------------------------------------------
// Kubernetes API helpers
// ---------------------------------------------------------------------------

const K8S_API = "https://kubernetes.default.svc";
const TOKEN_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/token";

function getToken(): string | null {
  try {
    return readFileSync(TOKEN_PATH, "utf-8").trim();
  } catch {
    return null;
  }
}

async function k8s(
  method: string,
  path: string,
  body?: any
): Promise<{ ok: boolean; status: number; data: any }> {
  const token = getToken();
  if (!token) return { ok: false, status: 0, data: { error: "No service account token" } };

  const headers: Record<string, string> = {
    Authorization: `Bearer ${token}`,
  };
  const opts: any = { method, headers, tls: { rejectUnauthorized: false } };

  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    opts.body = JSON.stringify(body);
  }

  const resp = await fetch(`${K8S_API}${path}`, opts);
  const data = await resp.json();
  return { ok: resp.ok, status: resp.status, data };
}

// ---------------------------------------------------------------------------
// Status collection
// ---------------------------------------------------------------------------

const SYSTEM_NAMESPACES = new Set([
  "kube-system",
  "kube-public",
  "kube-node-lease",
  "default",
]);

function timeAgo(dateStr: string): string {
  const diff = Date.now() - new Date(dateStr).getTime();
  const s = Math.floor(diff / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h`;
  return `${Math.floor(h / 24)}d`;
}

async function getStatus() {
  const [nodesR, nsR, podsR, svcsR, ingR, depsR] = await Promise.all([
    k8s("GET", "/api/v1/nodes"),
    k8s("GET", "/api/v1/namespaces"),
    k8s("GET", "/api/v1/pods"),
    k8s("GET", "/api/v1/services"),
    k8s("GET", "/apis/networking.k8s.io/v1/ingresses"),
    k8s("GET", "/apis/apps/v1/deployments"),
  ]);

  const nodes = (nodesR.data.items || []).map((n: any) => ({
    name: n.metadata.name,
    status:
      n.status?.conditions?.find((c: any) => c.type === "Ready")?.status === "True"
        ? "Ready"
        : "NotReady",
    roles:
      Object.keys(n.metadata?.labels || {})
        .filter((l) => l.startsWith("node-role.kubernetes.io/"))
        .map((l) => l.replace("node-role.kubernetes.io/", ""))
        .join(",") || "worker",
  }));

  const namespaces = (nsR.data.items || [])
    .map((ns: any) => ns.metadata.name as string)
    .filter((ns: string) => !SYSTEM_NAMESPACES.has(ns));

  const pods = (podsR.data.items || []).map((p: any) => {
    const cs = p.status?.containerStatuses || [];
    const readyCount = cs.filter((c: any) => c.ready).length;
    const totalCount = cs.length || p.spec?.containers?.length || 0;
    const restarts = cs.reduce((s: number, c: any) => s + (c.restartCount || 0), 0);
    return {
      name: p.metadata.name,
      namespace: p.metadata.namespace,
      status: p.status?.phase || "Unknown",
      ready: `${readyCount}/${totalCount}`,
      restarts,
      age: timeAgo(p.metadata.creationTimestamp),
    };
  });

  const services = (svcsR.data.items || []).map((s: any) => ({
    name: s.metadata.name,
    namespace: s.metadata.namespace,
    type: s.spec?.type || "",
    clusterIP: s.spec?.clusterIP || "",
    ports: (s.spec?.ports || [])
      .map((p: any) => `${p.port}${p.targetPort ? "\u2192" + p.targetPort : ""}`)
      .join(", "),
  }));

  const ingresses = (ingR.data.items || []).map((i: any) => ({
    name: i.metadata.name,
    namespace: i.metadata.namespace,
    hosts: (i.spec?.rules || []).map((r: any) => r.host).filter(Boolean),
  }));

  const deployments = (depsR.data.items || []).map((d: any) => ({
    name: d.metadata.name,
    namespace: d.metadata.namespace,
    ready: d.status?.readyReplicas || 0,
    desired: d.spec?.replicas || 0,
  }));

  return {
    timestamp: new Date().toISOString(),
    cluster: { reachable: !nodesR.data.error, nodes },
    namespaces,
    pods,
    services,
    ingresses,
    deployments,
  };
}

// ---------------------------------------------------------------------------
// Service config discovery (reads /config/k8s.*.yaml)
// ---------------------------------------------------------------------------

interface ServiceConfigInfo {
  id: string;
  targetPort: number;
  requiresCli: boolean;
  hasImage: boolean;
}

function getServiceConfigs(): ServiceConfigInfo[] {
  try {
    const files = readdirSync("/config");
    const configs: ServiceConfigInfo[] = [];
    for (const file of files) {
      const match = file.match(/^k8s\.(.+)\.yaml$/);
      if (!match) continue;
      const id = match[1];
      if (id === "bluetext-ui") continue;

      const content = readFileSync(`/config/${file}`, "utf-8");
      let targetPort = 0;

      for (const raw of content.split(/\n---\n/)) {
        if (raw.includes("kind: Service")) {
          const m = raw.match(/targetPort:\s*(\d+)/);
          if (m) targetPort = parseInt(m[1], 10);
        }
      }
      const hasImage = content.includes("imagePullPolicy: Never");
      configs.push({ id, targetPort, requiresCli: false, hasImage });
    }
    configs.sort((a, b) => a.id.localeCompare(b.id));
    return configs;
  } catch {
    return [];
  }
}

// ---------------------------------------------------------------------------
// Apply / delete service configs via K8s API
// ---------------------------------------------------------------------------

interface K8sResource {
  apiVersion: string;
  kind: string;
  metadata: { name: string; [k: string]: any };
  [k: string]: any;
}

function apiPathForResource(
  r: K8sResource,
  namespace: string
): { basePath: string; namePath: string } {
  const name = r.metadata.name;
  if (r.apiVersion === "v1" && r.kind === "Service") {
    return {
      basePath: `/api/v1/namespaces/${namespace}/services`,
      namePath: `/api/v1/namespaces/${namespace}/services/${name}`,
    };
  }
  if (r.apiVersion === "apps/v1" && r.kind === "Deployment") {
    return {
      basePath: `/apis/apps/v1/namespaces/${namespace}/deployments`,
      namePath: `/apis/apps/v1/namespaces/${namespace}/deployments/${name}`,
    };
  }
  if (r.apiVersion === "networking.k8s.io/v1" && r.kind === "Ingress") {
    return {
      basePath: `/apis/networking.k8s.io/v1/namespaces/${namespace}/ingresses`,
      namePath: `/apis/networking.k8s.io/v1/namespaces/${namespace}/ingresses/${name}`,
    };
  }
  throw new Error(`Unsupported resource: ${r.apiVersion}/${r.kind}`);
}

function parseConfigTemplate(id: string, namespace: string): K8sResource[] {
  const content = readFileSync(`/config/k8s.${id}.yaml`, "utf-8");
  const templated = content.replaceAll("{{NAMESPACE}}", namespace);
  const docs = parseAllDocuments(templated);
  const resources: K8sResource[] = [];
  for (const doc of docs) {
    const obj = doc.toJSON();
    if (!obj || !obj.kind) continue;
    if (["ServiceAccount", "ClusterRole", "ClusterRoleBinding"].includes(obj.kind)) continue;
    resources.push(obj);
  }
  return resources;
}

async function applyServiceConfig(
  id: string,
  namespace: string
): Promise<{ success: boolean; errors: string[] }> {
  const resources = parseConfigTemplate(id, namespace);
  const errors: string[] = [];

  for (const r of resources) {
    const { basePath, namePath } = apiPathForResource(r, namespace);
    const createResult = await k8s("POST", basePath, r);
    if (!createResult.ok) {
      if (createResult.status === 409) {
        const updateResult = await k8s("PUT", namePath, r);
        if (!updateResult.ok) {
          errors.push(`${r.kind}/${r.metadata.name}: ${updateResult.data.message || "update failed"}`);
        }
      } else {
        errors.push(`${r.kind}/${r.metadata.name}: ${createResult.data.message || "create failed"}`);
      }
    }
  }
  return { success: errors.length === 0, errors };
}

async function deleteServiceConfig(
  id: string,
  namespace: string
): Promise<{ success: boolean; errors: string[] }> {
  const resources = parseConfigTemplate(id, namespace);
  const errors: string[] = [];

  for (const r of resources) {
    const { namePath } = apiPathForResource(r, namespace);
    const result = await k8s("DELETE", namePath);
    if (!result.ok && result.status !== 404) {
      errors.push(`${r.kind}/${r.metadata.name}: ${result.data.message || "delete failed"}`);
    }
  }
  return { success: errors.length === 0, errors };
}

// ---------------------------------------------------------------------------
// Namespace operations
// ---------------------------------------------------------------------------

async function createNamespace(
  name: string
): Promise<{ success: boolean; error?: string }> {
  const result = await k8s("POST", "/api/v1/namespaces", {
    apiVersion: "v1",
    kind: "Namespace",
    metadata: {
      name,
      labels: {
        "pod-security.kubernetes.io/enforce": "privileged",
        "pod-security.kubernetes.io/warn": "privileged",
        "pod-security.kubernetes.io/audit": "privileged",
      },
    },
  });
  if (!result.ok) {
    return { success: false, error: result.data.message || "Failed to create namespace" };
  }
  return { success: true };
}

async function deleteNamespace(
  name: string
): Promise<{ success: boolean; error?: string }> {
  if (name === "bluetext") {
    return { success: false, error: "Cannot delete the bluetext namespace" };
  }
  const result = await k8s("DELETE", `/api/v1/namespaces/${name}`);
  if (!result.ok && result.status !== 404) {
    return { success: false, error: result.data.message || "Failed to delete namespace" };
  }
  return { success: true };
}

// ---------------------------------------------------------------------------
// JSON response helpers
// ---------------------------------------------------------------------------

function json(data: any, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json",
      "Access-Control-Allow-Origin": "*",
    },
  });
}

// ---------------------------------------------------------------------------
// Static file serving (production — serves built React app from dist/)
// ---------------------------------------------------------------------------

const DIST_DIR = join(import.meta.dir, "dist");
const HAS_DIST = existsSync(DIST_DIR);

const MIME_TYPES: Record<string, string> = {
  ".html": "text/html",
  ".js": "application/javascript",
  ".css": "text/css",
  ".json": "application/json",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".ico": "image/x-icon",
};

function serveStatic(pathname: string): Response | null {
  if (!HAS_DIST) return null;

  // Only try to serve actual files (paths with extensions)
  const ext = pathname.substring(pathname.lastIndexOf("."));
  if (ext !== pathname && MIME_TYPES[ext]) {
    const filePath = join(DIST_DIR, pathname);
    try {
      const file = Bun.file(filePath);
      if (file.size > 0) {
        return new Response(file, {
          headers: { "Content-Type": MIME_TYPES[ext] },
        });
      }
    } catch {}
  }

  // SPA fallback: serve index.html for all other routes
  try {
    return new Response(Bun.file(join(DIST_DIR, "index.html")), {
      headers: { "Content-Type": "text/html" },
    });
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------
// HTTP Server
// ---------------------------------------------------------------------------

Bun.serve({
  port: PORT,
  async fetch(req) {
    const url = new URL(req.url);
    const method = req.method;

    // --- CORS preflight ---
    if (method === "OPTIONS") {
      return new Response(null, {
        headers: {
          "Access-Control-Allow-Origin": "*",
          "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
          "Access-Control-Allow-Headers": "Content-Type",
        },
      });
    }

    // --- Status endpoints ---
    if (url.pathname === "/api/status" && method === "GET") {
      return json(await getStatus());
    }

    if (url.pathname === "/api/status/stream" && method === "GET") {
      const stream = new ReadableStream({
        async start(controller) {
          const send = async () => {
            try {
              const status = await getStatus();
              controller.enqueue(`data: ${JSON.stringify(status)}\n\n`);
            } catch (err) {
              controller.enqueue(`data: ${JSON.stringify({ error: String(err) })}\n\n`);
            }
          };
          await send();
          const interval = setInterval(send, 3000);
          req.signal.addEventListener("abort", () => clearInterval(interval));
        },
      });
      return new Response(stream, {
        headers: {
          "Content-Type": "text/event-stream",
          "Cache-Control": "no-cache",
          Connection: "keep-alive",
          "X-Accel-Buffering": "no",
        },
      });
    }

    // --- Service configs ---
    if (url.pathname === "/api/services/configs" && method === "GET") {
      return json(getServiceConfigs());
    }

    // --- Namespace mutations ---
    if (url.pathname === "/api/namespaces" && method === "POST") {
      const body = await req.json();
      const name = body.name?.trim();
      if (!name || !/^[a-z0-9][a-z0-9-]*$/.test(name)) {
        return json({ success: false, error: "Invalid namespace name" }, 400);
      }
      const result = await createNamespace(name);
      return json(
        result.success
          ? { success: true, message: `Namespace "${name}" created` }
          : { success: false, error: result.error },
        result.success ? 200 : 400
      );
    }

    const nsDeleteMatch = url.pathname.match(/^\/api\/namespaces\/([a-z0-9][a-z0-9-]*)$/);
    if (nsDeleteMatch && method === "DELETE") {
      const name = nsDeleteMatch[1];
      const result = await deleteNamespace(name);
      return json(
        result.success
          ? { success: true, message: `Namespace "${name}" deleted` }
          : { success: false, error: result.error },
        result.success ? 200 : 400
      );
    }

    // --- Service mutations ---
    if (url.pathname === "/api/services/start" && method === "POST") {
      const body = await req.json();
      const { id, namespace } = body;
      if (!id || !namespace) {
        return json({ success: false, error: "Missing id or namespace" }, 400);
      }
      const configs = getServiceConfigs();
      const cfg = configs.find((c) => c.id === id);
      if (!cfg) {
        return json({ success: false, error: `Unknown service: ${id}` }, 404);
      }
      const result = await applyServiceConfig(id, namespace);
      return json(
        result.success
          ? { success: true, message: `Service "${id}" started in ${namespace}` }
          : { success: false, errors: result.errors },
        result.success ? 200 : 500
      );
    }

    if (url.pathname === "/api/services/stop" && method === "POST") {
      const body = await req.json();
      const { id, namespace } = body;
      if (!id || !namespace) {
        return json({ success: false, error: "Missing id or namespace" }, 400);
      }
      const result = await deleteServiceConfig(id, namespace);
      return json(
        result.success
          ? { success: true, message: `Service "${id}" stopped in ${namespace}` }
          : { success: false, errors: result.errors },
        result.success ? 200 : 500
      );
    }

    // --- Host agent proxy (rebuild / watch) ---
    const hostAgentPaths = ["/api/services/rebuild", "/api/services/watch/start", "/api/services/watch/stop", "/api/services/watch/status"];
    if (hostAgentPaths.includes(url.pathname) && method === "POST") {
      const agentPath = url.pathname.replace("/api/services/", "/");
      try {
        const body = await req.json();
        const resp = await fetch(`http://host.k3d.internal:16981${agentPath}`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        });
        const data = await resp.json();
        return json(data, resp.ok ? 200 : 500);
      } catch (err) {
        return json({ success: false, message: `Host agent unreachable: ${err}` }, 502);
      }
    }

    // --- Static files (production) ---
    const staticResponse = serveStatic(url.pathname);
    if (staticResponse) return staticResponse;

    return new Response("Not found", { status: 404 });
  },
});

console.log(`Bluetext API listening on http://localhost:${PORT}`);
if (HAS_DIST) {
  console.log(`Serving static files from ${DIST_DIR}`);
}
