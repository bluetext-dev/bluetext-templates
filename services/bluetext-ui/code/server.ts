import { readFileSync, writeFileSync, readdirSync, existsSync } from "fs";
import { join } from "path";
import { parseAllDocuments, stringify } from "yaml";

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
// Service config discovery (reads /config/services/k8s.*.yaml)
// ---------------------------------------------------------------------------

interface ServiceConfigInfo {
  id: string;
  targetPort: number;
  requiresCli: boolean;
  hasImage: boolean;
}

function getServiceConfigs(): ServiceConfigInfo[] {
  try {
    const files = readdirSync("/config/services");
    const configs: ServiceConfigInfo[] = [];
    for (const file of files) {
      const match = file.match(/^k8s\.(.+)\.yaml$/);
      if (!match) continue;
      const id = match[1];
      if (id === "bluetext-ui") continue;

      const content = readFileSync(`/config/services/${file}`, "utf-8");
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
// Stack config discovery (reads /config/bluetext.yaml)
// ---------------------------------------------------------------------------

interface StackConfig {
  id: string;
  services: string[];
}

function getStackConfigs(): StackConfig[] {
  try {
    const content = readFileSync("/config/bluetext.yaml", "utf-8");
    const docs = parseAllDocuments(content);
    const root = docs[0]?.toJSON();
    if (!root?.stacks) return [];

    const raw: Record<string, string[]> = root.stacks;

    // Resolve stack references: entries that match another stack name get expanded
    function resolve(entries: string[], seen: Set<string>): string[] {
      const result: string[] = [];
      for (const entry of entries) {
        if (raw[entry] && !seen.has(entry)) {
          seen.add(entry);
          result.push(...resolve(raw[entry], seen));
        } else if (!raw[entry]) {
          result.push(entry);
        }
      }
      return result;
    }

    return Object.entries(raw).map(([id, entries]) => ({
      id,
      services: [...new Set(resolve(entries, new Set([id])))],
    }));
  } catch {
    return [];
  }
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

    // --- Stack configs ---
    if (url.pathname === "/api/stacks" && method === "GET") {
      return json(getStackConfigs());
    }

    if (url.pathname === "/api/stacks" && method === "POST") {
      const body = await req.json();
      const id = body.id?.trim();
      const entries: string[] = body.entries;
      if (!id || !/^[a-z0-9][a-z0-9-]*$/.test(id)) {
        return json({ success: false, error: "Invalid stack ID" }, 400);
      }
      if (!Array.isArray(entries) || entries.length === 0) {
        return json({ success: false, error: "Stack must have at least one entry" }, 400);
      }
      try {
        const configPath = "/config/bluetext.yaml";
        let root: any = {};
        try {
          const content = readFileSync(configPath, "utf-8");
          const docs = parseAllDocuments(content);
          root = docs[0]?.toJSON() || {};
        } catch {}
        if (!root.stacks) root.stacks = {};
        root.stacks[id] = entries;
        writeFileSync(configPath, stringify(root));
        return json({ success: true, message: `Stack "${id}" created` });
      } catch (err) {
        return json({ success: false, error: String(err) }, 500);
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
