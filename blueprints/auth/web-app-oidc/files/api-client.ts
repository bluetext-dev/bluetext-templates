/**
 * Authenticated fetch wrapper. Reads the access token from the same localStorage
 * key used by AuthProvider and attaches it as a Bearer header. Returns a normalized
 * { status, ok, body } shape so callers can render success/error UI uniformly.
 */
const STORAGE_KEY = "bluetext.auth";

export interface ApiResult {
  status: number;
  ok: boolean;
  body: unknown;
}

function readAccessToken(): string | null {
  if (typeof window === "undefined") return null;
  const raw = window.localStorage.getItem(STORAGE_KEY);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as { accessToken?: string };
    return parsed.accessToken ?? null;
  } catch {
    return null;
  }
}

export async function apiFetch(path: string, init: RequestInit = {}): Promise<ApiResult> {
  const headers = new Headers(init.headers);
  const token = readAccessToken();
  if (token && !headers.has("Authorization")) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  const resp = await fetch(path, { ...init, headers });
  const text = await resp.text();
  let body: unknown = text;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    /* keep raw text */
  }
  return { status: resp.status, ok: resp.ok, body };
}
