import { defineConfig } from 'vitest/config';

// Unit tests run in Node (fetch, WebCrypto and Request/Response are built in) with
// every Google endpoint mocked. test/e2e/ needs the Firebase emulators: npm run test:e2e.
export default defineConfig({
  test: {
    include: ['test/*.test.ts'],
    environment: 'node',
    testTimeout: 20_000,
  },
});
