import { defineConfig } from 'vitest/config';

// End-to-end tests: need the Auth + Firestore emulators. Run with `npm run test:e2e`.
export default defineConfig({
  test: {
    include: ['test/e2e/**/*.test.ts'],
    environment: 'node',
    fileParallelism: false,
    testTimeout: 60_000,
    hookTimeout: 120_000,
  },
});
