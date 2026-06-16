# web-app — reaching the api from the browser

How browser code in the web-app talks to a backend api. This card is injected
into the agent's context whenever a system has the web-app, so the convention is
known before any `fetch` is written. Worked cross-origin example:
`fullstack/items-demo`.

## Always call the api same-origin via `/api/*`

The web-app's dev server proxies `/api/*` to the api in-cluster — see
`server.proxy` in `vite.config.ts`:

```ts
proxy: {
  '/api': {
    target: process.env.API_URL || 'http://api',  // the api Service, in-cluster
    changeOrigin: true,
    rewrite: (path) => path.replace(/^\/api/, ''), // /api/hello -> /hello
  },
}
```

So a browser `fetch("/api/hello")` reaches the api's `GET /hello` route. To add
an endpoint: add the route on the api (e.g. `GET /scores`, **without** an `/api`
prefix) and call it from the browser as `/api/scores`. The request never leaves
the web-app's own origin.

**Use the `apiUrl()` seam (`app/lib/api.ts`) for every call** — it returns the
same-origin `/api/...` path in the browser and the in-cluster `API_URL` on the
server, so the same call site is correct in both contexts:

```tsx
import { apiUrl } from "~/lib/api";

// ✅ same-origin — apiUrl() routes through the /api proxy, no CORS, no gateway
const res = await fetch(apiUrl("/scores"));

// ❌ bare relative path — skips the /api proxy → 404, then CORS once "fixed" with the api URL
const res = await fetch("/scores");

// ❌ cross-origin — leaves the web-app origin for the api's own subdomain
const res = await fetch("https://api--<token>--<user>.dm-k8s.bluetext.dev/scores");
```

**This is enforced.** `b service check web-app` runs `lint:api`
(`scripts/lint-api-calls.mjs`), which fails on any browser→backend call not under
`/api/` or pointed at a service's own URL / `ingress-url`. The agent harness runs
the check after every edit, so a wrong call is caught immediately — use `apiUrl()`
and it passes. (Genuinely-not-an-api relative fetch? `// bluetext-lint-ignore api-call`.)

## Never fetch the api's `…dm-k8s.bluetext.dev` subdomain from browser code

That second form is a **cross-origin** request. It leaves the
`web-app--…` origin for the `api--…` origin, which:

1. hits the lab auth gateway — unauthenticated cross-site calls get a 302 to
   login the browser can't follow, and
2. trips CORS — the gateway/error responses carry no `Access-Control-Allow-Origin`.

The browser reports both as `No 'Access-Control-Allow-Origin' header is present`
/ `net::ERR_FAILED`. That error usually means "the request went cross-origin and
got blocked," **not** "the api's CORS config is wrong." Route it through `/api/*`
and the gateway and CORS never enter the picture.

## Production builds

The proxy above is the **Vite dev server** (what the lab runs via `bun run dev`).
If you serve a production build, give it the same same-origin `/api/*` proxy, or
follow the cross-origin pattern in `fullstack/items-demo`: the web-app reads the
api's browser-facing URL from its declared link mount
(`/etc/bluetext/links/api/ingress-url`) and the api ships a permissive wildcard
CORS layer (`allow_origin/methods/headers(Any)`). Note a wildcard cannot carry
credentials (the spec forbids it; tower-http panics), so cross-origin access is
unauthenticated — authenticated traffic must use the same-origin `/api/*` proxy.
