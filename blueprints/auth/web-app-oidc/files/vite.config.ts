import { reactRouter } from "@react-router/dev/vite";
import tailwindcss from "@tailwindcss/vite";
import { defineConfig } from "vite";
import tsconfigPaths from "vite-tsconfig-paths";
import path from "path";

/**
 * Dev-server proxies for the RBAC stack:
 *   /api/*    → Kong (which strips /api and forwards to the API; the same Kong
 *               also runs the phantom-token + role-check plugins). Ignores
 *               $API_URL on purpose — for RBAC, traffic MUST go through Kong
 *               so the phantom-token plugin can introspect every request.
 *   /curity/* → Curity (so the browser does same-origin POSTs for the OIDC
 *               token exchange — no CORS preflight needed). The authorize
 *               *redirect* still goes to the public Curity hostname so the
 *               user sees the real login form there.
 */
export default defineConfig({
  clearScreen: false,
  server: {
    allowedHosts: [".bluetext.localhost", ".bluetext.lvh.me"],
    proxy: {
      "/api": {
        target: process.env.KONG_URL || "http://kong",
        changeOrigin: true,
      },
      "/curity": {
        target: process.env.CURITY_URL || "http://curity",
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/curity/, ""),
      },
    },
  },
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "."),
      "~": path.resolve(__dirname, "./app"),
    },
  },
  plugins: [tailwindcss(), reactRouter(), tsconfigPaths()],
});
