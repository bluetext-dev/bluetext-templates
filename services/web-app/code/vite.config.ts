import { reactRouter } from "@react-router/dev/vite";
import tailwindcss from "@tailwindcss/vite";
import { defineConfig } from "vite";
import tsconfigPaths from "vite-tsconfig-paths";
import path from "path";

export default defineConfig({
  clearScreen: false,
  server: {
    // Locally the web-app is served at `web-app.<ns>.bluetext.localhost`. On a
    // remote server reached through a reverse proxy the host is the lab form
    // `web-app--<token>--<user>.<domain>` — set VITE_ALLOWED_HOST to that
    // domain (e.g. `.example.com`) so the dev server accepts it without the
    // proxy having to rewrite the Host header.
    allowedHosts: ['.bluetext.localhost', ...(process.env.VITE_ALLOWED_HOST ? [process.env.VITE_ALLOWED_HOST] : [])],
    // Browser code reaches the api through this same-origin `/api` proxy
    // (see app/lib/api.ts): a relative `/api/...` request stays on the
    // web-app's own origin, so there is no cross-origin preflight. The proxy
    // strips the `/api` prefix before forwarding, so the api defines its
    // routes without it (`/hello`, not `/api/hello`).
    proxy: {
      '/api': {
        target: process.env.API_URL || 'http://api',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
        headers: process.env.API_HOST ? { Host: process.env.API_HOST } : {},
      },
    },
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, '.'),
      '~': path.resolve(__dirname, './app'),
    },
  },
  plugins: [tailwindcss(), reactRouter(), tsconfigPaths()],
});
