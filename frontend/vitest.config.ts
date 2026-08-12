import { defineConfig } from 'vitest/config'

const alias = {
  '@': new URL('./src', import.meta.url).pathname,
}

/**
 * Deliberately separate from vite.config.ts: neither project needs the tanstack-router
 * or tailwind plugins, and keeping their codegen/transform steps out of the test run is
 * what makes it fast.
 *
 * Two projects, because the suite has two kinds of test with incompatible needs:
 *
 * - `unit` -- plain functions against fake objects. No DOM, so `node` keeps it cheap.
 * - `dom` -- components rendered with @testing-library/react. Needs `jsdom` plus the
 *   browser APIs jsdom itself does not implement (see `src/test/setup.ts`).
 *
 * A single project cannot serve both: `environment: 'node'` leaves `document` undefined,
 * and running every unit test under jsdom would pay for a DOM none of them touch. The
 * split by file extension (`.test.ts` vs `.test.tsx`) is what assigns a new test to its
 * project without any further declaration -- a component test is a `.tsx` file anyway.
 */
export default defineConfig({
  test: {
    projects: [
      {
        resolve: { alias },
        test: {
          name: 'unit',
          environment: 'node',
          include: ['src/**/*.test.ts'],
        },
      },
      {
        resolve: { alias },
        test: {
          name: 'dom',
          environment: 'jsdom',
          include: ['src/**/*.test.tsx'],
          setupFiles: ['./src/test/setup.ts'],
          // Component tests share one jsdom per file; a leftover portal or store value
          // from a previous file must never decide the next one's outcome.
          restoreMocks: true,
        },
      },
    ],
  },
})
