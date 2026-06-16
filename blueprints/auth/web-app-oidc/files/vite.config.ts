import { reactRouter } from "@react-router/dev/vite";
import tailwindcss from "@tailwindcss/vite";
import { defineConfig } from "vite";
import tsconfigPaths from "vite-tsconfig-paths";
import path from "path";

/**
 * No dev-server proxies for the RBAC stack anymore.
 *
 * Phantom token requires same-origin, so KONG is the single front door:
 * the browser reaches the SPA at `/`, `/api/*` is routed by Kong to the API
 * (with the phantom-token plugin), and `/curity/*` is routed by Kong to the
 * Curity token endpoint for the same-origin OIDC token POST. Vite only serves
 * the SPA — it never sees `/api` or `/curity` traffic, because the browser
 * talks to Kong's host, and Kong's `/` catch-all forwards SPA requests here.
 * (Earlier this config proxied `/api` → Kong and `/curity` → Curity from the
 * Vite dev server when the web-app had its own Ingress; that origin is gone.)
 */
export default defineConfig({
  clearScreen: false,
  server: {
    // Local hosts plus, on a remote server behind a reverse proxy, the lab
    // domain (`web-app--<token>--<user>.<domain>`) via VITE_ALLOWED_HOST — so
    // the dev server accepts the forwarded Host without the proxy rewriting it.
    allowedHosts: [".bluetext.localhost", ".bluetext.lvh.me", ...(process.env.VITE_ALLOWED_HOST ? [process.env.VITE_ALLOWED_HOST] : [])],
  },
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "."),
      "~": path.resolve(__dirname, "./app"),
    },
  },
  plugins: [tailwindcss(), reactRouter(), tsconfigPaths()],
});
