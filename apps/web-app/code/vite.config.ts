import { reactRouter } from "@react-router/dev/vite";
import tailwindcss from "@tailwindcss/vite";
import { defineConfig } from "vite";
import tsconfigPaths from "vite-tsconfig-paths";
import path from "path";

export default defineConfig({
  clearScreen: false,
  server: {
    allowedHosts: ['.bluetext.localhost'],
    proxy: {
      '/api': {
        target: process.env.API_URL || 'http://api',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
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
