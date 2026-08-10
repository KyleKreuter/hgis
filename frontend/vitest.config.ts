import { defineConfig } from 'vitest/config'

/**
 * Deliberately separate from vite.config.ts: the sync algorithm tests are plain
 * unit tests against a fake map object, no DOM and no plugins required. Keeping
 * the tanstack-router and tailwind plugins out of the test run avoids their
 * codegen/transform steps entirely.
 */
export default defineConfig({
  resolve: {
    alias: {
      '@': new URL('./src', import.meta.url).pathname,
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
})
