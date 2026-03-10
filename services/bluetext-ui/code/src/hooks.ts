import { useState, useEffect, useCallback } from "react";
import type { ClusterStatus, ServiceConfig, Stack } from "./types";
import { fetchStatus, fetchServiceConfigs, fetchStacks } from "./api";

export function useStatus(intervalMs = 3000) {
  const [status, setStatus] = useState<ClusterStatus | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      setStatus(await fetchStatus());
      setError(null);
    } catch (err) {
      setError(String(err));
    }
  }, []);

  useEffect(() => {
    refresh();
    const id = setInterval(refresh, intervalMs);
    return () => clearInterval(id);
  }, [refresh, intervalMs]);

  return { status, error, refresh };
}

export function useServiceConfigs(intervalMs = 10000) {
  const [configs, setConfigs] = useState<ServiceConfig[]>([]);

  useEffect(() => {
    const load = async () => {
      try {
        setConfigs(await fetchServiceConfigs());
      } catch {}
    };
    load();
    const id = setInterval(load, intervalMs);
    return () => clearInterval(id);
  }, [intervalMs]);

  return configs;
}

export function useStacks(intervalMs = 10000) {
  const [stacks, setStacks] = useState<Stack[]>([]);

  useEffect(() => {
    const load = async () => {
      try {
        setStacks(await fetchStacks());
      } catch {}
    };
    load();
    const id = setInterval(load, intervalMs);
    return () => clearInterval(id);
  }, [intervalMs]);

  return stacks;
}

export function useToast() {
  const [toast, setToast] = useState<{
    message: string;
    type: "success" | "error";
  } | null>(null);

  const show = useCallback((message: string, type: "success" | "error") => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 3000);
  }, []);

  return { toast, show };
}
