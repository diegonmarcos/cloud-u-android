import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['test/**/*.spec.mjs'],
    globalSetup: ['test/global-setup.mjs'],
    testTimeout: 20000,
  },
});
