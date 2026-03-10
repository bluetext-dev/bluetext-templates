import { useState, useEffect } from "react";
import type { Stack, Deployment, ServiceConfig } from "../types";
import { AddStackOverlay } from "./AddStackOverlay";

function StackRow({
  stack,
  namespaces,
  deployments,
  onStart,
  onStop,
}: {
  stack: Stack;
  namespaces: string[];
  deployments: Deployment[];
  onStart: (id: string, ns: string) => Promise<boolean>;
  onStop: (id: string, ns: string) => Promise<boolean>;
}) {
  const [selectedNs, setSelectedNs] = useState("");
  const [starting, setStarting] = useState<string | null>(null);
  const [stopping, setStopping] = useState<string | null>(null);
  const userNamespaces = namespaces.filter((ns) => ns !== "bluetext");

  // A stack is "running" in a namespace if all its services have a ready deployment there
  const runningNamespaces = userNamespaces.filter((ns) =>
    stack.services.every((svc) =>
      deployments.some((d) => d.name === svc && d.namespace === ns && d.ready > 0)
    )
  );

  // Clear spinner once deployments reflect the change
  useEffect(() => {
    if (starting && runningNamespaces.includes(starting)) setStarting(null);
  }, [starting, runningNamespaces]);
  useEffect(() => {
    if (stopping && !runningNamespaces.includes(stopping)) setStopping(null);
  }, [stopping, runningNamespaces]);

  return (
    <div className="svc-row">
      <div>
        <span className="svc-name">{stack.id}</span>
        <span className="svc-port" style={{ marginLeft: 8 }}>
          {stack.services.join(", ")}
        </span>
      </div>
      <div className="svc-actions">
        {runningNamespaces.map((ns) => (
          <span key={ns} style={{ display: "contents" }}>
            <span className="badge badge-ns">{ns}</span>
            <button
              className="btn btn-danger btn-sm"
              onClick={async () => {
                setStopping(ns);
                if (!await onStop(stack.id, ns)) setStopping(null);
              }}
              disabled={stopping === ns}
            >
              {stopping === ns ? <><span className="spinner" /> Stopping</> : "Stop"}
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
              if (!await onStart(stack.id, selectedNs)) setStarting(null);
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

export function StacksPanel({
  stacks,
  namespaces,
  deployments,
  serviceConfigs,
  onStart,
  onStop,
  onAddStack,
  onToast,
}: {
  stacks: Stack[];
  namespaces: string[];
  deployments: Deployment[];
  serviceConfigs: ServiceConfig[];
  onStart: (id: string, ns: string) => Promise<boolean>;
  onStop: (id: string, ns: string) => Promise<boolean>;
  onAddStack: (id: string, entries: string[]) => Promise<boolean>;
  onToast: (msg: string, type: "success" | "error") => void;
}) {
  const [showAdd, setShowAdd] = useState(false);

  return (
    <div className="card full">
      <div className="card-title">
        Stacks <span className="count">{stacks.length}</span>
        <button
          className="btn btn-sm"
          style={{ marginLeft: "auto" }}
          onClick={() => setShowAdd(true)}
        >
          + Add
        </button>
      </div>
      {stacks.length > 0 ? (
        stacks.map((stack) => (
          <StackRow
            key={stack.id}
            stack={stack}
            namespaces={namespaces}
            deployments={deployments}
            onStart={onStart}
            onStop={onStop}
          />
        ))
      ) : (
        <div className="empty">No stacks defined in bluetext.yaml</div>
      )}
      {showAdd && (
        <AddStackOverlay
          serviceConfigs={serviceConfigs}
          existingStacks={stacks}
          onSave={onAddStack}
          onClose={() => setShowAdd(false)}
        />
      )}
    </div>
  );
}
