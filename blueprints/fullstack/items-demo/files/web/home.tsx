import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { apiUrl } from "~/lib/api";

interface {{entity}} {
  id: string;
  text: string;
}

export function meta() {
  return [{ title: "{{entity}} demo · Bluetext" }];
}

// Reach the api through `apiUrl` (app/lib/api.ts): in the browser it resolves to
// the same-origin `/api/{{collection}}`, which the web-app's Vite proxy forwards
// to the api in-cluster — so the call never crosses an origin boundary and never
// trips CORS. NEVER build the api's own URL (its `service--token--user.domain`
// subdomain) or read its `ingress-url` in browser code: that's cross-origin.
// `b service check` enforces this. See the web-app's AGENTS.md.
export default function Home() {
  const [entries, setEntries] = useState<{{entity}}[]>([]);
  const [text, setText] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const fetchEntries = async () => {
    try {
      const res = await fetch(apiUrl("/{{collection}}"));
      if (!res.ok) throw new Error(`GET /{{collection}} returned ${res.status}`);
      const body = (await res.json()) as {{entity}}[];
      setEntries(body);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to fetch {{collection}}");
    }
  };

  useEffect(() => {
    fetchEntries();
  }, []);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const trimmed = text.trim();
    if (!trimmed) return;
    setSubmitting(true);
    try {
      const res = await fetch(apiUrl("/{{collection}}"), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ text: trimmed }),
      });
      if (!res.ok) throw new Error(`POST /{{collection}} returned ${res.status}`);
      // The api returns the created {{entity}} — append it directly so the UI
      // doesn't depend on a follow-up GET hitting the Couchbase indexer in
      // time. The next page load will re-fetch from N1QL and reconcile.
      const created = (await res.json()) as {{entity}};
      setEntries((prev) => [...prev, created]);
      setText("");
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to save {{entity_snake}}");
    } finally {
      setSubmitting(false);
    }
  };

  // Styling uses the web-app template's semantic tokens (bg-background, bg-card,
  // text-muted-foreground, …) defined in app/app.css — so the page follows the
  // active theme (the `dark` class on <html>) instead of hardcoding a palette.
  return (
    <main className="mx-auto max-w-[640px] px-5 py-12 font-sans">
      <h1 className="mb-1 text-2xl font-semibold capitalize">{{collection}}</h1>
      <p className="mb-6 text-sm text-muted-foreground">
        Round-trip demo: this form POSTs to the Rust api, which writes each {{entity_snake}}
        into the <code className="rounded bg-muted px-1.5 py-0.5 text-foreground">{{bucket}}._default.{{collection}}</code> Couchbase collection.
      </p>

      <form onSubmit={submit} className="mb-4 flex gap-2">
        <input
          data-testid="{{entity_snake}}-text"
          type="text"
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder="What's on your mind?"
          disabled={submitting}
          className="flex-1 rounded-md border border-input bg-transparent px-3 py-2.5 text-sm outline-none placeholder:text-muted-foreground focus:ring-2 focus:ring-ring"
        />
        <button
          data-testid="{{entity_snake}}-submit"
          type="submit"
          disabled={submitting || !text.trim()}
          className="cursor-pointer rounded-md bg-primary px-4 py-2.5 text-sm text-primary-foreground disabled:cursor-default disabled:opacity-60"
        >
          {submitting ? "Saving…" : "Save"}
        </button>
      </form>

      {error && (
        <div
          data-testid="{{entity_snake}}-error"
          className="mb-4 rounded-md border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive"
        >
          {error}
        </div>
      )}

      <ul data-testid="{{entity_snake}}-list" className="m-0 list-none p-0">
        {entries.length === 0 && !error && (
          <li className="py-2 text-sm text-muted-foreground">No {{collection}} yet. Add one above.</li>
        )}
        {entries.map((entry) => (
          <li
            key={entry.id}
            data-testid="{{entity_snake}}-row"
            className="mb-1.5 rounded-md border bg-card px-3 py-2.5 text-sm"
          >
            {entry.text}
          </li>
        ))}
      </ul>
    </main>
  );
}
