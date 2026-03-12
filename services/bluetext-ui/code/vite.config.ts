import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    host: true,
    allowedHosts: [".bluetext.localhost"],
    hmr: {
      clientPort: 80,
    },
    watch: {
      usePolling: true,
    },
  },
  build: {
    outDir: "dist",
  },
});
