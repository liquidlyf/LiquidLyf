import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['rules-tests/**/*.test.js'],
    // One emulator, and each test clears it: run files one at a time.
    fileParallelism: false,
    testTimeout: 30_000,
    hookTimeout: 60_000,
  },
});
