import { useEffect, useState } from "react";
import type { FormEvent } from "react";

interface Item {
  id: string;
  text: string;
}

export function meta() {
  return [{ title: "Items · Bluetext Demo" }];
}

/**
 * Reach the api by swapping the `web-app.` subdomain prefix of our own host
 * for `api.`. That keeps the demo namespace-agnostic — whatever the deploy
 * target, the api ingress lives next to ours.
 */
function apiBase(): string {
  if (typeof window === "undefined") return "";
  const { protocol, host } = window.location;
  // host looks like web-app.ss--development--default--main.bluetext.localhost
  const withoutPrefix = host.replace(/^web-app\./, "");
  return `${protocol}//api.${withoutPrefix}`;
}

export default function Home() {
  const [items, setItems] = useState<Item[]>([]);
  const [text, setText] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const fetchItems = async () => {
    try {
      const res = await fetch(`${apiBase()}/items`);
      if (!res.ok) throw new Error(`GET /items returned ${res.status}`);
      const body = (await res.json()) as Item[];
      setItems(body);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to fetch items");
    }
  };

  useEffect(() => {
    fetchItems();
  }, []);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const trimmed = text.trim();
    if (!trimmed) return;
    setSubmitting(true);
    try {
      const res = await fetch(`${apiBase()}/items`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ text: trimmed }),
      });
      if (!res.ok) throw new Error(`POST /items returned ${res.status}`);
      // The api returns the created Item — append it directly so the UI
      // doesn't depend on a follow-up GET hitting the Couchbase indexer in
      // time. The next page load will re-fetch from N1QL and reconcile.
      const created = (await res.json()) as Item;
      setItems((prev) => [...prev, created]);
      setText("");
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to save item");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <main
      style={{
        maxWidth: 640,
        margin: "48px auto",
        padding: "0 20px",
        fontFamily: "Inter, ui-sans-serif, system-ui, sans-serif",
        color: "#f3f4f6",
      }}
    >
      <h1 style={{ fontSize: 28, marginBottom: 4 }}>Items</h1>
      <p style={{ color: "#9ca3af", marginBottom: 24, fontSize: 14 }}>
        Round-trip demo: this form POSTs to the Rust api, which writes each item
        into the <code style={{ background: "#1f2937", padding: "1px 6px", borderRadius: 4 }}>main._default._default</code> Couchbase collection.
      </p>

      <form onSubmit={submit} style={{ display: "flex", gap: 8, marginBottom: 16 }}>
        <input
          data-testid="item-text"
          type="text"
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder="What's on your mind?"
          disabled={submitting}
          style={{
            flex: 1,
            padding: "10px 12px",
            fontSize: 14,
            background: "#111827",
            color: "#f3f4f6",
            border: "1px solid #374151",
            borderRadius: 6,
            outline: "none",
          }}
        />
        <button
          data-testid="item-submit"
          type="submit"
          disabled={submitting || !text.trim()}
          style={{
            padding: "10px 18px",
            fontSize: 14,
            background: "#2563eb",
            color: "#fff",
            border: "none",
            borderRadius: 6,
            cursor: submitting || !text.trim() ? "default" : "pointer",
            opacity: submitting || !text.trim() ? 0.6 : 1,
          }}
        >
          {submitting ? "Saving…" : "Save"}
        </button>
      </form>

      {error && (
        <div
          data-testid="item-error"
          style={{
            padding: "8px 12px",
            marginBottom: 16,
            background: "#7f1d1d",
            border: "1px solid #991b1b",
            borderRadius: 6,
            fontSize: 13,
          }}
        >
          {error}
        </div>
      )}

      <ul data-testid="item-list" style={{ margin: 0, padding: 0, listStyle: "none" }}>
        {items.length === 0 && !error && (
          <li style={{ color: "#6b7280", fontSize: 13, padding: "8px 0" }}>No items yet. Add one above.</li>
        )}
        {items.map((it) => (
          <li
            key={it.id}
            data-testid="item-row"
            style={{
              padding: "10px 12px",
              background: "#111827",
              border: "1px solid #1f2937",
              borderRadius: 6,
              marginBottom: 6,
              fontSize: 14,
            }}
          >
            {it.text}
          </li>
        ))}
      </ul>
    </main>
  );
}
