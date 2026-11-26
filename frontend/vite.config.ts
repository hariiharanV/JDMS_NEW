/// <reference types="vitest/config" />
import { readFileSync } from 'node:fs';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev and preview servers on 5180 (matches the jdms-web redirect URIs in deploy/keycloak); API calls are proxied
// to jdms-core, so the browser only ever talks to one origin besides Keycloak. By default they listen on this
// machine only; JDMS_BIND_ADDRESS and the JDMS_TLS_* certificate open them to the network (deploy/tls/README.md).
const host = process.env.JDMS_BIND_ADDRESS ?? 'localhost';
const https =
  process.env.JDMS_TLS_CERT && process.env.JDMS_TLS_KEY
    ? { cert: readFileSync(process.env.JDMS_TLS_CERT), key: readFileSync(process.env.JDMS_TLS_KEY) }
    : undefined;
export default defineConfig({
  plugins: [react()],
  server: {
    host,
    https,
    port: 5180,
    strictPort: true,
    proxy: {
      '/api': 'http://localhost:8090',
    },
  },
  preview: {
    host,
    https,
    port: 5180,
    strictPort: true,
    proxy: {
      '/api': 'http://localhost:8090',
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    css: false,
  },
});
