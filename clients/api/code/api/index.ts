/**
 * Server-side API client for making requests to a backend service via Traefik.
 *
 * Reads configuration from environment variables injected by `b client configure`:
 *   - {PREFIX}_URL  — in-cluster Traefik URL (e.g. http://traefik.kube-system.svc.cluster.local)
 *   - {PREFIX}_HOST — Host header for Traefik routing (e.g. api.main.bluetext.localhost)
 *
 * Usage in a React Router loader:
 *   import { createApiClient } from "@bluetext/api-client";
 *   const api = createApiClient();
 *   const data = await api.get("/users");
 */

export interface ApiClientConfig {
  url: string;
  host: string;
}

export interface ApiClient {
  get: <T = unknown>(path: string, init?: RequestInit) => Promise<T>;
  post: <T = unknown>(path: string, body: unknown, init?: RequestInit) => Promise<T>;
  put: <T = unknown>(path: string, body: unknown, init?: RequestInit) => Promise<T>;
  patch: <T = unknown>(path: string, body: unknown, init?: RequestInit) => Promise<T>;
  delete: <T = unknown>(path: string, init?: RequestInit) => Promise<T>;
}

export function configFromEnv(prefix = "API"): ApiClientConfig {
  const url = process.env[`${prefix}_URL`];
  const host = process.env[`${prefix}_HOST`];
  if (!url) throw new Error(`${prefix}_URL is not set. Run 'b client configure api' to inject it.`);
  if (!host) throw new Error(`${prefix}_HOST is not set. Run 'b client configure api' to inject it.`);
  return { url, host };
}

export function createApiClient(config?: ApiClientConfig): ApiClient {
  const { url, host } = config ?? configFromEnv();

  async function request<T>(method: string, path: string, body?: unknown, init?: RequestInit): Promise<T> {
    const res = await fetch(`${url}${path}`, {
      method,
      headers: {
        Host: host,
        "Content-Type": "application/json",
        ...init?.headers,
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
      ...init,
    });
    if (!res.ok) {
      const text = await res.text().catch(() => "");
      throw new Error(`API ${method} ${path} failed (${res.status}): ${text}`);
    }
    const text = await res.text();
    return text ? JSON.parse(text) : undefined;
  }

  return {
    get: (path, init) => request("GET", path, undefined, init),
    post: (path, body, init) => request("POST", path, body, init),
    put: (path, body, init) => request("PUT", path, body, init),
    patch: (path, body, init) => request("PATCH", path, body, init),
    delete: (path, init) => request("DELETE", path, undefined, init),
  };
}
