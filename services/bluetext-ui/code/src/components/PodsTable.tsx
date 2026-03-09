import type { Pod } from "../types";

function statusClass(s: string) {
  return "status-" + s.toLowerCase().replace(/[^a-z]/g, "");
}

const SYSTEM_NS = new Set(["kube-system", "kube-public", "kube-node-lease"]);

export function PodsTable({ pods }: { pods: Pod[] }) {
  const userPods = pods.filter((p) => !SYSTEM_NS.has(p.namespace));

  return (
    <div className="card full">
      <div className="card-title">
        Pods <span className="count">{userPods.length}</span>
      </div>
      {userPods.length > 0 ? (
        <table>
          <thead>
            <tr>
              <th>Namespace</th>
              <th>Name</th>
              <th>Status</th>
              <th>Ready</th>
              <th>Restarts</th>
              <th>Age</th>
            </tr>
          </thead>
          <tbody>
            {userPods.map((p) => (
              <tr key={`${p.namespace}/${p.name}`}>
                <td>
                  <span className="badge badge-ns">{p.namespace}</span>
                </td>
                <td>{p.name}</td>
                <td className={statusClass(p.status)}>{p.status}</td>
                <td>{p.ready}</td>
                <td>{p.restarts}</td>
                <td>{p.age}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <div className="empty">No pods running</div>
      )}
    </div>
  );
}
