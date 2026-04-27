/**
 * OIDC config for the web-app.
 *
 * Endpoints are derived at runtime from the current page host so the same
 * code works in any namespace. The web-app is reachable at
 *   http://web-app.<namespace>.bluetext.localhost
 * and the Curity Identity Server at
 *   http://curity.<namespace>.bluetext.localhost
 *
 * The token endpoint is hit through the Vite dev-server proxy at /curity/* —
 * that keeps the POST same-origin so the browser doesn't block on CORS.
 */

function deriveCurityBase(): string {
  if (typeof window === "undefined") return "";
  const { protocol, host } = window.location;
  // host is "web-app.<ns>.bluetext.localhost" or "<ns>.bluetext.lvh.me" etc.
  const curityHost = host.replace(/^web-app\./, "curity.");
  return `${protocol}//${curityHost}`;
}

function deriveRedirectUri(): string {
  if (typeof window === "undefined") return "";
  return `${window.location.protocol}//${window.location.host}/auth/callback`;
}

export const authConfig = {
  /** Used for full-page redirects to the authorize endpoint. */
  authorizeEndpoint: () => `${deriveCurityBase()}/oauth/v2/oauth-authorize`,
  /** Token POST goes through the same-origin proxy to dodge CORS. */
  tokenEndpoint: "/curity/oauth/v2/oauth-token",
  /** RP-initiated logout — clears the Curity session cookie. */
  endSessionEndpoint: () => `${deriveCurityBase()}/oauth/v2/oauth-session/logout`,
  clientId: "web-app",
  redirectUri: deriveRedirectUri,
  scopes: ["openid", "profile", "email", "roles"],
} as const;
