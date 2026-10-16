// Phase 0 storage spec runner (cloud-notes-hybrid). Deliberately NOT the
// monorepo vitest.config.ts: the spec mocks every @affine/* and @capacitor/*
// import, so it needs only vitest itself -- no yarn install of the whole
// AFFiNE workspace, which keeps this check in seconds instead of minutes.
import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/plugins/nbstore/index.spec.ts'],
  },
});
