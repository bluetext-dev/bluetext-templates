import { useState, useEffect } from "react";
import { fetchAppTemplates, addApp, type Template } from "../api";

export function AddAppOverlay({
  onClose,
  onToast,
}: {
  onClose: () => void;
  onToast: (msg: string, type: "success" | "error") => void;
}) {
  const [templates, setTemplates] = useState<Template[]>([]);
  const [loading, setLoading] = useState(true);
  const [adding, setAdding] = useState<string | null>(null);
  const [customIds, setCustomIds] = useState<Record<string, string>>({});

  useEffect(() => {
    fetchAppTemplates()
      .then(setTemplates)
      .catch(() => onToast("Failed to load templates", "error"))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const handleAdd = async (template: Template) => {
    const id = customIds[template.id]?.trim() || template.id;
    setAdding(template.id);
    try {
      const result = await addApp(template.id, id);
      if (result.success) {
        onToast(result.message ?? `Added app "${id}"`, "success");
      } else {
        onToast(result.error ?? result.message ?? "Failed to add app", "error");
      }
    } catch {
      onToast("Failed to add app", "error");
    } finally {
      setAdding(null);
    }
  };

  return (
    <div className="overlay-backdrop" onClick={onClose}>
      <div className="overlay-panel" onClick={(e) => e.stopPropagation()}>
        <div className="overlay-header">
          <span>Add App from Template</span>
          <button className="btn btn-sm" onClick={onClose}>
            Esc
          </button>
        </div>
        {loading ? (
          <div className="overlay-loading">
            <span className="spinner" /> Loading templates...
          </div>
        ) : templates.length === 0 ? (
          <div className="empty">No templates available</div>
        ) : (
          <div className="template-grid">
            {templates.map((t) => (
              <div key={t.id} className="template-item">
                <input
                  type="text"
                  className="template-id-input"
                  placeholder={t.id}
                  value={customIds[t.id] ?? ""}
                  onChange={(e) =>
                    setCustomIds((prev) => ({ ...prev, [t.id]: e.target.value }))
                  }
                  onKeyDown={(e) => {
                    if (e.key === "Enter") handleAdd(t);
                  }}
                />
                <button
                  className="btn btn-primary btn-sm"
                  onClick={() => handleAdd(t)}
                  disabled={adding === t.id}
                >
                  {adding === t.id ? (
                    <span className="spinner" />
                  ) : (
                    t.id
                  )}
                </button>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
