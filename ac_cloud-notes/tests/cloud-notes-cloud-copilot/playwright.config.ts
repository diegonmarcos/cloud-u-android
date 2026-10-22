import { fileURLToPath } from 'node:url';

import { testResultDir } from '@cloud-notes-test/kit/playwright';
import type {
  PlaywrightTestConfig,
  PlaywrightWorkerOptions,
} from '@playwright/test';

const runtimeConfigPath = fileURLToPath(
  new URL('./runtime-config.json', import.meta.url)
);

const config: PlaywrightTestConfig = {
  testDir: './e2e',
  fullyParallel: true,
  timeout: 120_000,
  outputDir: testResultDir,
  globalSetup: './global-setup.ts',
  use: {
    baseURL: 'http://localhost:8080/',
    browserName:
      (process.env.BROWSER as PlaywrightWorkerOptions['browserName']) ??
      'chromium',
    permissions: ['clipboard-read', 'clipboard-write'],
    viewport: { width: 1440, height: 800 },
    actionTimeout: 10 * 1000,
    locale: 'en-US',
    trace: 'retain-on-failure',
    video: 'retain-on-failure',
  },
  forbidOnly: !!process.env.CI,
  workers: 4,
  retries: 3,
  reporter: process.env.CI ? 'github' : 'list',
  webServer: [
    {
      command: 'yarn run -T cloud-notes dev -p @cloud-notes/web',
      stdout: 'pipe',
      stderr: 'pipe',
      timeout: 240 * 1000,
      reuseExistingServer: !process.env.CI,
      env: {
        COVERAGE: process.env.COVERAGE || 'false',
      },
      url: 'http://localhost:8080',
    },
    {
      command: 'yarn run -T cloud-notes dev -p @cloud-notes/server',
      timeout: 240 * 1000,
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
        MAILER_HOST: '0.0.0.0',
        MAILER_PORT: '1025',
        MAILER_SENDER: 'noreply@toeverything.info',
        MAILER_USER: 'noreply@toeverything.info',
        MAILER_PASSWORD: 'cloud-notes',
      },
      url: 'http://localhost:3010/graphql',
    },
  ],
};

if (process.env.CI) {
  config.retries = 3;
}

export default config;
