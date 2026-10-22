import { fileURLToPath } from 'node:url';

import { testResultDir } from '@cloud-notes-test/kit/playwright';
import type { PlaywrightTestConfig } from '@playwright/test';
// import { devices } from '@playwright/test';

const runtimeConfigPath = fileURLToPath(
  new URL('./runtime-config.json', import.meta.url)
);

/**
 * Read environment variables from file.
 * https://github.com/motdotla/dotenv
 */
// require('dotenv').config();

/**
 * See https://playwright.dev/docs/test-configuration.
 */
const config: PlaywrightTestConfig = {
  testDir: './e2e',
  fullyParallel: true,
  timeout: process.env.CI ? 300_000 : 60_000,
  outputDir: testResultDir,
  use: {
    viewport: { width: 1440, height: 800 },
  },
  reporter: process.env.CI ? 'github' : 'list',
  webServer: [
    // Intentionally not building the web, reminds you to run it by yourself.
    {
      command: 'yarn run -T cloud-notes dev -p @cloud-notes/electron-renderer',
      timeout: 120 * 1000,
      reuseExistingServer: !process.env.CI,
      stdout: 'pipe',
      env: {
        COVERAGE: process.env.COVERAGE || 'false',
        DISTRIBUTION: 'desktop',
      },
      url: 'http://localhost:8080',
    },
    {
      command: 'yarn run -T cloud-notes dev -p @cloud-notes/server',
      timeout: 120 * 1000,
      reuseExistingServer: !process.env.CI,
      stdout: 'pipe',
      stderr: 'pipe',
      env: {
        DATABASE_URL:
          process.env.DATABASE_URL ??
          'postgresql://cloud-notes:cloud-notes@localhost:5432/cloud-notes',
        NODE_ENV: 'test',
        CLOUD_NOTES_ENV: process.env.CLOUD_NOTES_ENV ?? 'dev',
        CLOUD_NOTES_BACKEND_RUNTIME_CONFIG_PATH: runtimeConfigPath,
        DEBUG: 'cloud-notes:*',
        FORCE_COLOR: 'true',
        DEBUG_COLORS: 'true',
        MAILER_SENDER: 'noreply@toeverything.info',
      },
      url: 'http://localhost:3010/graphql',
    },
  ],
};

if (process.env.CI) {
  config.retries = 3;
  config.workers = '50%';
}

export default config;
