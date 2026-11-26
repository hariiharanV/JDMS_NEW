import { defineConfig, devices } from '@playwright/test';

// The screens' address: localhost by default, or the HTTPS network address of deploy/tls/README.md
const webUrl = process.env.JDMS_WEB_PUBLIC_URL ?? 'http://localhost:5180';

/**
 * UI journeys against the real local stack: Keycloak (deploy/docker-compose.yml), jdms-core on 8080 and jdms-worker
 * running with the local profile, and the dev users of deploy/keycloak. The journeys share users and data, so they
 * run one at a time; every run uses fresh CIFs and codes.
 */
export default defineConfig({
  testDir: './e2e/stack',
  fullyParallel: false,
  workers: 1,
  timeout: 180_000,
  expect: { timeout: 15_000 },
  forbidOnly: !!process.env.CI,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: webUrl,
    // extra Chromium flags for a machine whose software renderer hangs on screenshots, e.g.
    // JDMS_E2E_CHROMIUM_ARGS="--disable-gpu --disable-software-rasterizer"; nothing by default
    launchOptions: { args: process.env.JDMS_E2E_CHROMIUM_ARGS?.split(' ').filter(Boolean) ?? [] },
    // the network address uses the local certificate authority, which the test browser does not know
    ignoreHTTPSErrors: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run build && npm run preview',
    url: webUrl,
    ignoreHTTPSErrors: true,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
