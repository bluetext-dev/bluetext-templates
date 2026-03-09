import { defineConfig } from 'vite';

export default defineConfig({
  server: {
    allowedHosts: ['.local.bluetext.io'],
    proxy: {
      '/api': {
        target: 'http://api',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
});
