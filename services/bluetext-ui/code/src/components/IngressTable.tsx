import type { Ingress } from "../types";

export function IngressTable({ ingresses }: { ingresses: Ingress[] }) {
  return (
    <div className="card full">
      <div className="card-title">
        Ingresses <span className="count">{ingresses.length}</span>
      </div>
      {ingresses.length > 0 ? (
        <table>
          <thead>
            <tr>
              <th>Namespace</th>
              <th>Name</th>
              <th>Hosts</th>
            </tr>
          </thead>
          <tbody>
            {ingresses.map((i) => (
              <tr key={`${i.namespace}/${i.name}`}>
                <td>
                  <span className="badge badge-ns">{i.namespace}</span>
                </td>
                <td>{i.name}</td>
                <td>
                  {i.hosts.map((host) => (
                    <span key={host}>
                      <a href={`http://${host}`}>{host}</a>{" "}
                    </span>
                  ))}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <div className="empty">No ingresses</div>
      )}
    </div>
  );
}
