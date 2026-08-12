import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render as rtlRender } from '@testing-library/react'
import { vi } from 'vitest'
import type { ReactElement, ReactNode } from 'react'

/**
 * Helpers shared by the component tests.
 *
 * The line these tests mock is `fetch`, not the query hooks above it: a test that
 * replaces `useGeoportalDataset` proves only that the component reads the hook it was
 * written to read. Answering the request instead leaves the real hook, the real query
 * key and `api/client.ts`'s own unwrapping in the path, so the test still fails when any
 * of them stops carrying the field through.
 */

/**
 * A query client for one test. Retries off, because a test that mocks a failure would
 * otherwise wait out three retries before the component ever renders the error.
 */
export function testQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  })
}

export function renderWithQueryClient(ui: ReactElement, client = testQueryClient()) {
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
  return { client, ...rtlRender(ui, { wrapper }) }
}

/** One route the fetch stub answers: a substring of the URL, and the body to return. */
export interface StubRoute {
  /** Matched with `String.includes` against the request URL, first match wins. */
  match: string
  body: unknown
  status?: number
}

/**
 * Installs a `fetch` that answers from `routes` and rejects anything else loudly.
 *
 * An unmatched request must not silently resolve: a component that asks for a URL no
 * route covers is a test whose fixture no longer matches the code, and the error names
 * the URL so it is obvious which.
 */
export function stubFetch(routes: StubRoute[]) {
  const calls: string[] = []
  const fetchStub = vi.fn((input: RequestInfo | URL) => {
    const url = String(input)
    calls.push(url)
    const route = routes.find((candidate) => url.includes(candidate.match))
    if (!route) {
      return Promise.reject(new Error(`No stub route for ${url}`))
    }
    const status = route.status ?? 200
    return Promise.resolve({
      ok: status >= 200 && status < 300,
      status,
      json: () => Promise.resolve(route.body),
    } as Response)
  })
  vi.stubGlobal('fetch', fetchStub)
  return { calls, fetchStub }
}
