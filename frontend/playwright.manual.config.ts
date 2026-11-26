import { defineConfig, devices } from '@playwright/test';

// Screenshots for the user manual and testing guide (docs/manuals/build/README.md).
// Runs against the local stack like playwright.stack.config.ts and adds test data to tenant BANK1.
const webUrl = process.env.JDMS_WEB_PUBLIC_URL ?? 'http://localhost:5180';

export default defineConfig({
  testDir: './e2e/manual',
  workers: 1,
  timeout: 900_000,
  expect: { timeout: 20_000 },
  reporter: 'list',
  // launchOptions: see playwright.stack.config.ts
  use: { launchOptions: { args: process.env.JDMS_E2E_CHROMIUM_ARGS?.split(' ').filter(Boolean) ?? [] }, baseURL: webUrl, ignoreHTTPSErrors: true, viewport: { width: 1440, height: 900 }, actionTimeout: 30_000, navigationTimeout: 60_000 },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } } }],
  webServer: { command: 'npm run build && npm run preview', url: webUrl, ignoreHTTPSErrors: true, reuseExistingServer: true, timeout: 180_000 },
});
