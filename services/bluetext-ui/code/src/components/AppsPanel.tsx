import { useState, useEffect } from "react";
import type { AppConfig, Deployment } from "../types";
import { rebuildApp } from "../api";
import { AddAppOverlay } from "./AddAppOverlay";

function getRunningInstances(id: string, deployments: Deployment[]) {
  const namespace = `app-${id}`;
  return deployments.filter(
    (d) => d.name === id && d.namespace === namespace && d.ready > 0,
  );
}

function AppRow({
  cfg,
  deployments,
  onStart,
  onStop,
  onToast,
}: {
  cfg: AppConfig;
  deployments: Deployment[];
  onStart: (id: string) => Promise<boolean>;
  onStop: (id: string) => Promise<boolean>;
  onToast: (msg: string) => void;
}) {
  const [rebuilding, setRebuilding] = useState(false);
  const [starting, setStarting] = useState(false);
  const [stopping, setStopping] = useState(false);
  const running = getRunningInstances(cfg.id, deployments);
  const isRunning = running.length > 0;
  const namespace = `app-${cfg.id}`;

  // Clear spinner once deployments reflect the change
  useEffect(() => {
    if (starting && isRunning) setStarting(false);
  }, [starting, isRunning]);
  useEffect(() => {
    if (stopping && !isRunning) setStopping(false);
  }, [stopping, isRunning]);

  const handleRebuild = async () => {
    setRebuilding(true);
    try {
      const r = await rebuildApp(cfg.id);
      onToast(r.message || r.error || "Done");
    } finally {
      setRebuilding(false);
    }
  };

  return (
    <div className="svc-row">
      <div>
        <span className="svc-name">{cfg.id}</span>
        <span className="svc-port">:{cfg.targetPort}</span>
        <span className="badge badge-ns" style={{ marginLeft: 8, opacity: 0.6 }}>{namespace}</span>
      </div>
      <div className="svc-actions">
        {isRunning && (
          <>
            {cfg.hasImage && (
              <button
                className="btn btn-warning btn-sm"
                onClick={handleRebuild}
                disabled={rebuilding}
              >
                {rebuilding ? "Building..." : "Rebuild"}
              </button>
            )}
            <button
              className="btn btn-danger btn-sm"
              onClick={async () => {
                setStopping(true);
                if (!await onStop(cfg.id)) setStopping(false);
              }}
              disabled={stopping}
            >
              {stopping ? <><span className="spinner" /> Stopping</> : "Stop"}
            </button>
          </>
        )}
        {!isRunning && (
          <button
            className="btn btn-primary btn-sm"
            onClick={async () => {
              setStarting(true);
              if (!await onStart(cfg.id)) setStarting(false);
            }}
            disabled={starting}
          >
            {starting ? <><span className="spinner" /> Starting</> : "Start"}
          </button>
        )}
      </div>
    </div>
  );
}

export function AppsPanel({
  configs,
  deployments,
  onStart,
  onStop,
  onToast,
}: {
  configs: AppConfig[];
  deployments: Deployment[];
  onStart: (id: string) => Promise<boolean>;
  onStop: (id: string) => Promise<boolean>;
  onToast: (msg: string, type?: "success" | "error") => void;
}) {
  const [showAdd, setShowAdd] = useState(false);
  return (
    <div className="card full">
      <div className="card-title">
        Apps <span className="count">{configs.length}</span>
        <button className="btn btn-primary btn-sm" style={{ marginLeft: "auto" }} onClick={() => setShowAdd(true)}>
          + Add
        </button>
      </div>
      {showAdd && (
        <AddAppOverlay
          onClose={() => setShowAdd(false)}
          onToast={(msg, type) => onToast(msg, type)}
        />
      )}
      {configs.length > 0 ? (
        configs.map((cfg) => (
          <AppRow
            key={cfg.id}
            cfg={cfg}
            deployments={deployments}
            onStart={onStart}
            onStop={onStop}
            onToast={onToast}
          />
        ))
      ) : (
        <div className="empty">No app configs found (add to config/apps/)</div>
      )}
    </div>
  );
}
