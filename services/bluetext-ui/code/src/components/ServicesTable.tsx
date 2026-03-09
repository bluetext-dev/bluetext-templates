import type { Service } from "../types";

const SYSTEM_NS = new Set(["kube-system", "kube-public", "kube-node-lease"]);

export function ServicesTable({ services }: { services: Service[] }) {
  const filtered = services.filter(
    (s) => !SYSTEM_NS.has(s.namespace) && s.name !== "kubernetes",
  );

  return (
    <div className="card full">
      <div className="card-title">
        Services <span className="count">{filtered.length}</span>
      </div>
      {filtered.length > 0 ? (
        <table>
          <thead>
            <tr>
              <th>Namespace</th>
              <th>Name</th>
              <th>Type</th>
              <th>ClusterIP</th>
              <th>Ports</th>
            </tr>
          </thead>
          <tbody>
            {filtered.map((s) => (
              <tr key={`${s.namespace}/${s.name}`}>
                <td>
                  <span className="badge badge-ns">{s.namespace}</span>
                </td>
                <td>{s.name}</td>
                <td>{s.type}</td>
                <td>{s.clusterIP}</td>
                <td>{s.ports}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <div className="empty">No services</div>
      )}
    </div>
  );
}
