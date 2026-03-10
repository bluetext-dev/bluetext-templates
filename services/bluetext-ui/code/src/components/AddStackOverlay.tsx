import { useState, useEffect } from "react";
import type { ServiceConfig, Stack } from "../types";

export function AddStackOverlay({
  serviceConfigs,
  existingStacks,
  onSave,
  onClose,
}: {
  serviceConfigs: ServiceConfig[];
  existingStacks: Stack[];
  onSave: (id: string, entries: string[]) => Promise<boolean>;
  onClose: () => void;
}) {
  const [stackId, setStackId] = useState("");
  const [selectedServices, setSelectedServices] = useState<Set<string>>(
    new Set()
  );
  const [selectedStacks, setSelectedStacks] = useState<Set<string>>(new Set());
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const toggleService = (id: string) => {
    setSelectedServices((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const toggleStack = (id: string) => {
    setSelectedStacks((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const handleSave = async () => {
    const id = stackId.trim();
    if (!id) return;
    const entries = [...selectedStacks, ...selectedServices];
    if (entries.length === 0) return;
    setSaving(true);
    const ok = await onSave(id, entries);
    setSaving(false);
    if (ok) onClose();
  };

  const isValid =
    stackId.trim().length > 0 &&
    (selectedServices.size > 0 || selectedStacks.size > 0);

  return (
    <div className="overlay-backdrop" onClick={onClose}>
      <div className="overlay-panel" onClick={(e) => e.stopPropagation()}>
        <div className="overlay-header">
          <span>Add Stack</span>
          <button className="btn btn-sm" onClick={onClose}>
            Esc
          </button>
        </div>

        <div style={{ marginBottom: 12 }}>
          <input
            type="text"
            placeholder="stack-id"
            value={stackId}
            onChange={(e) => setStackId(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && isValid) handleSave();
            }}
            style={{ width: "100%" }}
            autoFocus
          />
        </div>

        {existingStacks.length > 0 && (
          <>
            <div className="overlay-section-label">Stacks</div>
            <div className="checkbox-grid">
              {existingStacks.map((s) => (
                <label key={s.id} className="checkbox-item">
                  <input
                    type="checkbox"
                    checked={selectedStacks.has(s.id)}
                    onChange={() => toggleStack(s.id)}
                  />
                  <span>{s.id}</span>
                </label>
              ))}
            </div>
          </>
        )}

        {serviceConfigs.length > 0 && (
          <>
            <div className="overlay-section-label">Services</div>
            <div className="checkbox-grid">
              {serviceConfigs.map((svc) => (
                <label key={svc.id} className="checkbox-item">
                  <input
                    type="checkbox"
                    checked={selectedServices.has(svc.id)}
                    onChange={() => toggleService(svc.id)}
                  />
                  <span>{svc.id}</span>
                </label>
              ))}
            </div>
          </>
        )}

        <div className="overlay-actions">
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button
            className="btn btn-primary"
            onClick={handleSave}
            disabled={!isValid || saving}
          >
            {saving ? (
              <>
                <span className="spinner" /> Saving
              </>
            ) : (
              "Save"
            )}
          </button>
        </div>
      </div>
    </div>
  );
}
