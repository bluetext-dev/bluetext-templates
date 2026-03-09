import { useState } from "react";

export function NamespacesCard({
  namespaces,
  onCreateNamespace,
  onDeleteNamespace,
}: {
  namespaces: string[];
  onCreateNamespace: (name: string) => void;
  onDeleteNamespace: (name: string) => void;
}) {
  const [input, setInput] = useState("");

  const handleCreate = () => {
    const name = input.trim();
    if (!name) return;
    setInput("");
    onCreateNamespace(name);
  };

  return (
    <div className="card">
      <div className="card-title">
        Namespaces <span className="count">{namespaces.length}</span>
      </div>
      <div className="ns-badges">
        {namespaces.map((ns) => (
          <span className="ns-badge-wrap" key={ns}>
            <span className="badge badge-ns">{ns}</span>
            {ns !== "bluetext" && (
              <button
                className="btn btn-danger btn-sm"
                onClick={() => onDeleteNamespace(ns)}
                title="Delete"
              >
                &times;
              </button>
            )}
          </span>
        ))}
      </div>
      <div className="inline-form">
        <input
          type="text"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && handleCreate()}
          placeholder="namespace name"
        />
        <button className="btn btn-primary" onClick={handleCreate}>
          Create
        </button>
      </div>
    </div>
  );
}
