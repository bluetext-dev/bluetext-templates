import { defineConfig } from 'vite';

export default defineConfig({
  server: {
    allowedHosts: ['.bluetext.localhost'],
    proxy: {
      '/api': {
        target: 'http://api',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
});
