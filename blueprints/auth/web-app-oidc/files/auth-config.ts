/**
 * OIDC config for the web-app.
 *
 * Phantom token requires same-origin: the SPA, the API, and the browser's
 * token POST all live under Kong's single host. Kong serves the SPA at `/`,
 * proxies `/api/*` to the API (with the phantom-token plugin), and proxies
 * `/curity/*` to the Curity OIDC runtime for the token exchange. So the page
 * is reached at
 *   http://kong.<route-token>.bluetext.localhost
 * and endpoints are derived at runtime from the current page host so the same
 * code works in any namespace.
 *
 * Two Curity surfaces, two transports:
 *   - authorize  → a full-page redirect to Curity's OWN host
 *                  (http://curity.<route-token>.bluetext.localhost). Curity is
 *                  the IdP — a separate origin by OIDC design — and a redirect
 *                  is not a fetch, so no CORS / same-origin constraint applies.
 *   - token      → a browser `fetch` POST. It MUST be same-origin, so it goes
 *                  to Kong at /curity/* (Kong strips /curity and forwards to
 *                  Curity's token endpoint). jwks/userinfo are never fetched
 *                  (the SPA decodes the JWT client-side), so only the token
 *                  endpoint is proxied.
 */

function deriveCurityBase(): string {
  if (typeof window === "undefined") return "";
  const { protocol, host } = window.location;
  // host is "kong.<route-token>.bluetext.localhost" (Kong is the front door);
  // Curity keeps its own host on the same route-token segment.
  const curityHost = host.replace(/^kong\./, "curity.");
  return `${protocol}//${curityHost}`;
}

function deriveRedirectUri(): string {
  if (typeof window === "undefined") return "";
  // The browser origin is now Kong's host; the callback route is served by
  // Kong's `/` SPA catch-all. Curity must have this exact string registered
  // (see web-app-client.xml — redirect-uri on the Kong host's route-token).
  return `${window.location.protocol}//${window.location.host}/auth/callback`;
}

export const authConfig = {
  /** Full-page redirect to Curity's own host — not a fetch, no CORS. */
  authorizeEndpoint: () => `${deriveCurityBase()}/oauth/v2/oauth-authorize`,
  /** Token POST goes same-origin through Kong's /curity proxy. */
  tokenEndpoint: "/curity/oauth/v2/oauth-token",
  /** RP-initiated logout — clears the Curity session cookie. */
  endSessionEndpoint: () => `${deriveCurityBase()}/oauth/v2/oauth-session/logout`,
  clientId: "web-app",
  redirectUri: deriveRedirectUri,
  scopes: ["openid", "profile", "email", "roles"],
} as const;
