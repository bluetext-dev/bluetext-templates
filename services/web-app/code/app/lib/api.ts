// The one seam for reaching the `api` service from the web-app.
//
// The api defines its routes without an `/api` prefix (e.g. `/hello`,
// `/highscore`). Two callers reach those routes, and each needs a different
// URL — `apiUrl` returns the right one so callers never have to know which:
//
//   • Browser code runs at the web-app's own origin
//     (web-app.<namespace>.bluetext.localhost). A relative `/api/...` URL stays
//     same-origin, so the request rides the Vite dev-server proxy
//     (see vite.config.ts), which strips the `/api` prefix and forwards it to
//     the api. Same-origin means no cross-origin preflight — no CORS.
//
//   • Server code (React Router loaders/actions, ssr: true) runs inside the
//     pod, where there is no browser origin and a relative URL has no base to
//     resolve against. It reaches the api directly at API_URL — the in-cluster
//     address the deploy pipeline injects — with no `/api` prefix, since the
//     proxy (and its prefix-stripping) only exists in the browser path.
//
// Both paths land on the same api route. Call `apiUrl("/highscore")` from a
// loader or from client code and it resolves correctly either way.
export function apiUrl(path: string): string {
  const route = path.startsWith("/") ? path : `/${path}`;
  return typeof window === "undefined"
    ? `${process.env.API_URL ?? ""}${route}`
    : `/api${route}`;
}
