import { defineConfig, devices } from '@playwright/test';

// Test data for a fresh test server (docs/manuals/build/README.md): adds customers and their documents to tenant
// BANK1 through the API, signed in as the dev users. Runs against the stack at JDMS_WEB_PUBLIC_URL.
const webUrl = process.env.JDMS_WEB_PUBLIC_URL ?? 'http://localhost:5180';

export default defineConfig({
  testDir: './e2e/seed',
  workers: 1,
  timeout: 600_000,
  reporter: 'list',
  // launchOptions: see playwright.stack.config.ts
  use: { launchOptions: { args: process.env.JDMS_E2E_CHROMIUM_ARGS?.split(' ').filter(Boolean) ?? [] }, baseURL: webUrl, ignoreHTTPSErrors: true, actionTimeout: 30_000, navigationTimeout: 60_000 },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
