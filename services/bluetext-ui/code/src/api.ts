import type { AppConfig, ClusterStatus, ServiceConfig, Stack } from "./types";
import { send } from "./ws";

export async function fetchStatus(): Promise<ClusterStatus> {
  const result = await send("status", {});
  if (!result.success) throw new Error(result.message || "Failed to fetch status");
  return (result as any).data;
}

export async function fetchServiceConfigs(): Promise<ServiceConfig[]> {
  const result = await send("services:configs", {});
  if (!result.success) throw new Error(result.message || "Failed to fetch service configs");
  return (result as any).data ?? [];
}

export async function fetchAppConfigs(): Promise<AppConfig[]> {
  const result = await send("apps:configs", {});
  if (!result.success) throw new Error(result.message || "Failed to fetch app configs");
  return (result as any).data ?? [];
}

export async function fetchStacks(): Promise<Stack[]> {
  const result = await send("stacks:list", {});
  if (!result.success) throw new Error(result.message || "Failed to fetch stacks");
  return (result as any).data ?? [];
}

export async function addStack(id: string, entries: string[]) {
  return send("stacks:add", { id, entries });
}

export async function createNamespace(name: string) {
  return send("namespaces:create", { name });
}

export async function deleteNamespace(name: string) {
  return send("namespaces:delete", { name });
}

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

export async function getWatchStatus(id: string): Promise<{ message: string }> {
  const result = await send("services:watch/status", { id });
  return { message: result.message ?? "" };
}

export interface Template {
  id: string;
  name?: string;
  description?: string;
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

export async function startStack(id: string, namespace: string) {
  return send("stacks:start", { id, namespace });
}

export async function stopStack(id: string, namespace: string) {
  return send("stacks:stop", { id, namespace });
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

export async function fetchAppTemplates(): Promise<Template[]> {
  const result = await send("templates:list-apps", {});
  return (result as any).templates ?? [];
}

export async function addApp(template: string, id: string) {
  return send("apps:add", { template, id });
}
