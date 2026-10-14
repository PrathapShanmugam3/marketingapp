import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const target = process.env.VITE_API_TARGET || 'http://localhost:8080';
// Anchored so SPA routes such as /leads, /forms or /reviews are not mistaken for /l/, /f/ or /r/.
const proxied = ['/api/', '^/f/', '^/l/', '^/o/', '^/r/', '^/p/', '^/files/', '^/dev/', '^/webhooks/'];

export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          react: ['react', 'react-dom', 'react-router-dom'],
          data: ['@tanstack/react-query', 'react-hook-form', 'zod', '@hookform/resolvers'],
          i18n: ['i18next', 'react-i18next'],
        },
      },
    },
  },
  server: {
    port: 5173,
    proxy: {
      ...Object.fromEntries(proxied.map((p) => [p, { target, changeOrigin: false }])),
      '/ws': { target, ws: true, changeOrigin: false },
    },
  },
  test: { environment: 'jsdom', globals: true },
});
