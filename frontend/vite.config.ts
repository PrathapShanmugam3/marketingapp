import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const target = process.env.VITE_API_TARGET || 'http://localhost:8080';
const proxied = ['/api', '/f', '/l', '/o', '/r', '/p', '/files', '/dev', '/webhooks'];

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      ...Object.fromEntries(proxied.map((p) => [p, { target, changeOrigin: false }])),
      '/ws': { target, ws: true, changeOrigin: false },
    },
  },
  test: { environment: 'jsdom', globals: true },
});
