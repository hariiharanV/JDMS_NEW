import { defineConfig, devices } from '@playwright/test';

// Shell tests: the production build served by `vite preview`, without a backend (sign-in screen, languages, RTL).
// Journeys against the real stack: playwright.stack.config.ts (npm run e2e:stack).
export default defineConfig({
  testDir: './e2e/shell',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: 'http://localhost:5180',
    // see playwright.stack.config.ts
    launchOptions: { args: process.env.JDMS_E2E_CHROMIUM_ARGS?.split(' ').filter(Boolean) ?? [] },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run build && npm run preview',
    url: 'http://localhost:5180',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
