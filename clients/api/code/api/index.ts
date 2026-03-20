// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

interface ApiConfig {
  url: string;
}

function getConfig(prefix: string): ApiConfig {
  const envKey = `${prefix.toUpperCase().replace(/-/g, "_")}_URL`;
  const url = process.env[envKey];
  if (!url) {
    throw new Error(
      `Required env var ${envKey} is not set. Run 'b client configure api --target <app> --upstream <service>' to inject it.`
    );
  }
  return { url };
}

// ---------------------------------------------------------------------------
// Client
// ---------------------------------------------------------------------------

interface ApiResponse<T = unknown> {
  ok: boolean;
  status: number;
  data: T;
}

interface ApiClient {
  get: <T = unknown>(path: string, init?: RequestInit) => Promise<ApiResponse<T>>;
  post: <T = unknown>(path: string, body?: unknown, init?: RequestInit) => Promise<ApiResponse<T>>;
  put: <T = unknown>(path: string, body?: unknown, init?: RequestInit) => Promise<ApiResponse<T>>;
  delete: <T = unknown>(path: string, init?: RequestInit) => Promise<ApiResponse<T>>;
}

async function request<T>(baseUrl: string, method: string, path: string, body?: unknown, init?: RequestInit): Promise<ApiResponse<T>> {
  const url = `${baseUrl.replace(/\/+$/, "")}/${path.replace(/^\/+/, "")}`;
  const headers: Record<string, string> = {
    "Accept": "application/json",
    ...(init?.headers as Record<string, string> ?? {}),
  };
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
  }

  const res = await fetch(url, {
    ...init,
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });

  let data: T;
  const contentType = res.headers.get("content-type") ?? "";
  if (contentType.includes("application/json")) {
    data = await res.json() as T;
  } else {
    data = await res.text() as unknown as T;
  }

  return { ok: res.ok, status: res.status, data };
}

export function createApiClient(prefix = "API"): ApiClient {
  const { url: baseUrl } = getConfig(prefix);

  return {
    get: <T = unknown>(path: string, init?: RequestInit) =>
      request<T>(baseUrl, "GET", path, undefined, init),
    post: <T = unknown>(path: string, body?: unknown, init?: RequestInit) =>
      request<T>(baseUrl, "POST", path, body, init),
    put: <T = unknown>(path: string, body?: unknown, init?: RequestInit) =>
      request<T>(baseUrl, "PUT", path, body, init),
    delete: <T = unknown>(path: string, init?: RequestInit) =>
      request<T>(baseUrl, "DELETE", path, undefined, init),
  };
}

export { getConfig, type ApiConfig, type ApiResponse, type ApiClient };
