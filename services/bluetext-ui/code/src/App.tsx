import { useStatus, useServiceConfigs, useToast } from "./hooks";
import * as api from "./api";
import { ClusterCard } from "./components/ClusterCard";
import { NamespacesCard } from "./components/NamespacesCard";
import { DeployPanel } from "./components/DeployPanel";
import { PodsTable } from "./components/PodsTable";
import { ServicesTable } from "./components/ServicesTable";
import { IngressTable } from "./components/IngressTable";
import { Toast } from "./components/Toast";

export function App() {
  const { status, error } = useStatus();
  const configs = useServiceConfigs();
  const { toast, show } = useToast();

  async function handleAction(fn: () => Promise<{ success: boolean; message?: string; error?: string; errors?: string[] }>) {
    const result = await fn();
    if (result.success) {
      show(result.message ?? "Done", "success");
    } else {
      show(result.error ?? result.errors?.join(", ") ?? "Request failed", "error");
    }
  }

  if (error) {
    return <div>{error}</div>;
  }

  if (!status) {
    return <div>Loading...</div>;
  }

  return (
    <>
      <h1>BLUETEXT CONTROL PLANE</h1>
      <div className="subtitle">
        <span className="live-dot" /> Live &mdash; updates every 3s
      </div>

      <div className="grid">
        <ClusterCard
          nodes={status.cluster.nodes}
          reachable={status.cluster.reachable}
        />

        <NamespacesCard
          namespaces={status.namespaces}
          onCreateNamespace={(name) =>
            handleAction(() => api.createNamespace(name))
          }
          onDeleteNamespace={(name) => {
            if (confirm(`Delete namespace "${name}"? This will remove all resources in it.`)) {
              handleAction(() => api.deleteNamespace(name));
            }
          }}
        />

        <DeployPanel
          configs={configs}
          namespaces={status.namespaces}
          deployments={status.deployments}
          onStart={(id, ns) =>
            handleAction(() => api.startService(id, ns))
          }
          onStop={(id, ns) =>
            handleAction(() => api.stopService(id, ns))
          }
          onToast={(msg) => show(msg, "success")}
        />

        <PodsTable pods={status.pods} />
        <ServicesTable services={status.services} />
        <IngressTable ingresses={status.ingresses} />
      </div>

      <Toast toast={toast} />
    </>
  );
}
