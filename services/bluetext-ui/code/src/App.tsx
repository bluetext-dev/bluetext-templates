import { useStatus, useServiceConfigs, useStacks, useToast } from "./hooks";
import * as api from "./api";
import { ClusterCard } from "./components/ClusterCard";
import { NamespacesCard } from "./components/NamespacesCard";
import { DeployPanel } from "./components/DeployPanel";
import { StacksPanel } from "./components/StacksPanel";
import { PodsTable } from "./components/PodsTable";
import { ServicesTable } from "./components/ServicesTable";
import { IngressTable } from "./components/IngressTable";
import { Toast } from "./components/Toast";

export function App() {
  const { status, error } = useStatus();
  const configs = useServiceConfigs();
  const stacks = useStacks();
  const { toast, show } = useToast();

  async function handleAction(fn: () => Promise<{ success: boolean; message?: string; error?: string; errors?: string[] }>): Promise<boolean> {
    try {
      const result = await fn();
      if (result.success) {
        show(result.message ?? "Done", "success");
        return true;
      } else {
        show(result.error ?? result.message ?? result.errors?.join(", ") ?? "Request failed", "error");
        return false;
      }
    } catch (err) {
      show(err instanceof Error ? err.message : "Request failed", "error");
      return false;
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
        <IngressTable ingresses={status.ingresses} />

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

        <StacksPanel
          stacks={stacks}
          namespaces={status.namespaces}
          deployments={status.deployments}
          onStart={(id, ns) =>
            handleAction(() => api.startStack(id, ns))
          }
          onStop={(id, ns) =>
            handleAction(() => api.stopStack(id, ns))
          }
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
          onToast={(msg, type) => show(msg, type ?? "success")}
        />

        <PodsTable pods={status.pods} />
        <ServicesTable services={status.services} />
      </div>

      <Toast toast={toast} />
    </>
  );
}
