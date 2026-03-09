import type { ClusterStatus, ServiceConfig } from "./types";

export async function fetchStatus(): Promise<ClusterStatus> {
  const r = await fetch("/api/status");
  if (!r.ok) throw new Error(`API error: ${r.status}`);
  return r.json();
}

export async function fetchServiceConfigs(): Promise<ServiceConfig[]> {
  const r = await fetch("/api/services/configs");
  if (!r.ok) throw new Error(`API error: ${r.status}`);
  return r.json();
}

interface MutationResult {
  success: boolean;
  message?: string;
  error?: string;
  errors?: string[];
}

async function mutate(
  method: string,
  path: string,
  body?: unknown,
): Promise<MutationResult> {
  const opts: RequestInit = {
    method,
    headers: { "Content-Type": "application/json" },
  };
  if (body) opts.body = JSON.stringify(body);
  const r = await fetch(path, opts);
  return r.json();
}

export async function createNamespace(name: string) {
  return mutate("POST", "/api/namespaces", { name });
}

export async function deleteNamespace(name: string) {
  return mutate("DELETE", `/api/namespaces/${encodeURIComponent(name)}`);
}

export async function startService(id: string, namespace: string) {
  return mutate("POST", "/api/services/start", { id, namespace });
}

export async function stopService(id: string, namespace: string) {
  return mutate("POST", "/api/services/stop", { id, namespace });
}

export async function rebuildService(id: string, namespace: string) {
  return mutate("POST", "/api/services/rebuild", { id, namespace });
}

export async function startWatch(id: string, namespace: string) {
  return mutate("POST", "/api/services/watch/start", { id, namespace });
}

export async function stopWatch(id: string, namespace: string) {
  return mutate("POST", "/api/services/watch/stop", { id, namespace });
}

export async function getWatchStatus(id: string): Promise<{ message: string }> {
  const r = await fetch("/api/services/watch/status", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ id }),
  });
  return r.json();
}
