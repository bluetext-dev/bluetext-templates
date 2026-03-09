import type { Node } from "../types";

function statusClass(s: string) {
  return "status-" + s.toLowerCase().replace(/[^a-z]/g, "");
}

export function ClusterCard({ nodes, reachable }: { nodes: Node[]; reachable: boolean }) {
  return (
    <div className="card">
      <div className="card-title">Cluster</div>
      {reachable && nodes.length > 0 ? (
        <div className="node-info">
          {nodes.map((n) => (
            <div className="node-chip" key={n.name}>
              <span className={statusClass(n.status)}>{n.status}</span>{" "}
              {n.name} <span className="label">({n.roles})</span>
            </div>
          ))}
        </div>
      ) : (
        <div className="empty">Cluster not reachable</div>
      )}
    </div>
  );
}
