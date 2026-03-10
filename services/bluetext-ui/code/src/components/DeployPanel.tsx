import { useState, useEffect } from "react";
import type { ServiceConfig, Deployment } from "../types";
import { rebuildService, startWatch, stopWatch, getWatchStatus } from "../api";
import { AddServiceOverlay } from "./AddServiceOverlay";

function getRunningInstances(id: string, deployments: Deployment[]) {
  return deployments.filter(
    (d) => d.name === id && d.ready > 0 && d.namespace !== "bluetext",
  );
}

function ServiceRow({
  cfg,
  namespaces,
  deployments,
  onStart,
  onStop,
  onToast,
}: {
  cfg: ServiceConfig;
  namespaces: string[];
  deployments: Deployment[];
  onStart: (id: string, ns: string) => Promise<boolean>;
  onStop: (id: string, ns: string) => Promise<boolean>;
  onToast: (msg: string) => void;
}) {
  const [selectedNs, setSelectedNs] = useState("");
  const [rebuilding, setRebuilding] = useState(false);
  const [watching, setWatching] = useState(false);
  const [starting, setStarting] = useState<string | null>(null);
  const [stopping, setStopping] = useState<string | null>(null);
  const running = getRunningInstances(cfg.id, deployments);
  const userNamespaces = namespaces.filter((ns) => ns !== "bluetext");

  useEffect(() => {
    getWatchStatus(cfg.id).then((r) => setWatching(r.message === "watching"));
  }, [cfg.id]);

  // Clear spinner once deployments reflect the change
  useEffect(() => {
    if (starting && running.some((d) => d.namespace === starting)) setStarting(null);
  }, [starting, running]);
  useEffect(() => {
    if (stopping && !running.some((d) => d.namespace === stopping)) setStopping(null);
  }, [stopping, running]);

  const handleRebuild = async (ns: string) => {
    setRebuilding(true);
    try {
      const r = await rebuildService(cfg.id, ns);
      onToast(r.message || r.error || "Done");
    } finally {
      setRebuilding(false);
    }
  };

  const toggleWatch = async (ns: string) => {
    if (watching) {
      await stopWatch(cfg.id, ns);
      setWatching(false);
      onToast(`Stopped watching ${cfg.id}`);
    } else {
      await startWatch(cfg.id, ns);
      setWatching(true);
      onToast(`Watching ${cfg.id} for changes`);
    }
  };

  return (
    <div className="svc-row">
      <div>
        <span className="svc-name">{cfg.id}</span>
        <span className="svc-port">:{cfg.targetPort}</span>
      </div>
      <div className="svc-actions">
        {running.map((d) => (
          <span key={d.namespace} style={{ display: "contents" }}>
            <span className="badge badge-ns">{d.namespace}</span>
            {cfg.hasImage && (
              <>
                <button
                  className="btn btn-warning btn-sm"
                  onClick={() => handleRebuild(d.namespace)}
                  disabled={rebuilding}
                >
                  {rebuilding ? "Building..." : "Rebuild"}
                </button>
                <button
                  className={`btn btn-sm ${watching ? "btn-active" : "btn-secondary"}`}
                  onClick={() => toggleWatch(d.namespace)}
                >
                  {watching ? "Unwatch" : "Watch"}
                </button>
              </>
            )}
            <button
              className="btn btn-danger btn-sm"
              onClick={async () => {
                setStopping(d.namespace);
                if (!await onStop(cfg.id, d.namespace)) setStopping(null);
              }}
              disabled={stopping === d.namespace}
            >
              {stopping === d.namespace ? <><span className="spinner" /> Stopping</> : "Stop"}
            </button>
          </span>
        ))}
        <select
          value={selectedNs}
          onChange={(e) => setSelectedNs(e.target.value)}
        >
          <option value="">namespace...</option>
          {userNamespaces.map((ns) => (
            <option key={ns} value={ns}>
              {ns}
            </option>
          ))}
        </select>
        <button
          className="btn btn-primary btn-sm"
          onClick={async () => {
            if (selectedNs) {
              setStarting(selectedNs);
              if (!await onStart(cfg.id, selectedNs)) setStarting(null);
            }
          }}
          disabled={!selectedNs || !!starting}
        >
          {starting ? <><span className="spinner" /> Starting</> : "Start"}
        </button>
      </div>
    </div>
  );
}

export function DeployPanel({
  configs,
  namespaces,
  deployments,
  onStart,
  onStop,
  onToast,
}: {
  configs: ServiceConfig[];
  namespaces: string[];
  deployments: Deployment[];
  onStart: (id: string, ns: string) => Promise<boolean>;
  onStop: (id: string, ns: string) => Promise<boolean>;
  onToast: (msg: string, type?: "success" | "error") => void;
}) {
  const [showAdd, setShowAdd] = useState(false);
  return (
    <div className="card full">
      <div className="card-title">
        Deploy Services <span className="count">{configs.length}</span>
        <button className="btn btn-primary btn-sm" style={{ marginLeft: "auto" }} onClick={() => setShowAdd(true)}>
          + Add
        </button>
      </div>
      {showAdd && (
        <AddServiceOverlay
          onClose={() => setShowAdd(false)}
          onToast={(msg, type) => onToast(msg, type)}
        />
      )}
      {configs.length > 0 ? (
        configs.map((cfg) => (
          <ServiceRow
            key={cfg.id}
            cfg={cfg}
            namespaces={namespaces}
            deployments={deployments}
            onStart={onStart}
            onStop={onStop}
            onToast={onToast}
          />
        ))
      ) : (
        <div className="empty">No service configs found (mount /config)</div>
      )}
    </div>
  );
}
