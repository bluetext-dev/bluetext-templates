import type { AppConfig, ClusterStatus, ServiceConfig, Stack } from "./types";
import { send } from "./ws";

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

export interface MutationResult {
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

// --- Host-agent operations via WebSocket ---

export async function startService(id: string, namespace: string) {
  return send("services:start", { id, namespace });
}

export async function stopService(id: string, namespace: string) {
  return send("services:stop", { id, namespace });
}

export async function rebuildService(id: string, namespace: string) {
  return send("services:rebuild", { id, namespace });
}

export async function startWatch(id: string, namespace: string) {
  return send("services:watch/start", { id, namespace });
}

export async function stopWatch(id: string, namespace: string) {
  return send("services:watch/stop", { id, namespace });
}

export async function fetchStacks(): Promise<Stack[]> {
  const r = await fetch("/api/stacks");
  if (!r.ok) throw new Error(`API error: ${r.status}`);
  return r.json();
}

export async function startStack(id: string, namespace: string) {
  return send("stacks:start", { id, namespace });
}

export async function stopStack(id: string, namespace: string) {
  return send("stacks:stop", { id, namespace });
}

export async function addStack(
  id: string,
  entries: string[]
): Promise<MutationResult> {
  const r = await fetch("/api/stacks", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ id, entries }),
  });
  return r.json();
}

export async function getWatchStatus(id: string): Promise<{ message: string }> {
  const result = await send("services:watch/status", { id });
  return { message: result.message ?? "" };
}

export interface Template {
  id: string;
  hasCode: boolean;
  hasConfig: boolean;
}

export async function fetchTemplates(): Promise<Template[]> {
  const result = await send("templates:list", {});
  return (result as any).templates ?? [];
}

export async function addService(template: string, id: string) {
  return send("services:add", { template, id });
}

// --- App operations ---

export async function fetchAppConfigs(): Promise<AppConfig[]> {
  const r = await fetch("/api/apps/configs");
  if (!r.ok) throw new Error(`API error: ${r.status}`);
  return r.json();
}

export async function startApp(id: string) {
  return send("apps:start", { id });
}

export async function stopApp(id: string) {
  return send("apps:stop", { id });
}

export async function rebuildApp(id: string) {
  return send("apps:rebuild", { id });
}
