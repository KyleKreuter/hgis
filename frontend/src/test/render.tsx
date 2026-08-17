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

/**
 * Gives every element a fixed offset size.
 *
 * jsdom runs no layout, so `offsetWidth` and `offsetHeight` are zero everywhere. A
 * virtualised list measures its scroller with exactly those two properties and renders
 * *no* rows at all for a height of zero -- there is no window to fill, and the list ends
 * up empty rather than short. A test that clicks a row in such a list therefore needs a
 * height to exist first. Unlike the stubs in `setup.ts`, this one is opt-in and its
 * value does matter: the box has to be tall enough to hold the rows the test looks for.
 */
export function stubElementSize({ width = 400, height = 600 } = {}) {
  vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockReturnValue(width)
  vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockReturnValue(height)
}

/** One route the fetch stub answers: a substring of the URL, and the body to return. */
export interface StubRoute {
  /** Matched with `String.includes` against the request URL, first match wins. */
  match: string
  /**
   * A fixed body, or a function evaluated fresh on every matching request.
   *
   * Needed to test that a write is actually reflected downstream, not just that it was
   * sent: a mutation's own success handler often invalidates a listing and triggers a
   * background refetch of it, and a fixed body would answer that refetch with the exact
   * fixture the test opened with -- silently undoing the effect being tested for and
   * leaving a false pass, or a flaky one if an assertion happens to run before the
   * refetch lands. A function can close over a flag another route's function sets, so
   * the listing genuinely reflects what the write route was told to do.
   */
  body: unknown | (() => unknown)
  status?: number
  /**
   * Answers after this many real ms instead of on the next microtask. Default: none.
   *
   * Needed to test a genuine race between two fired-together requests (e.g. a double
   * click): `userEvent.click()` itself takes several real ticks to walk through its own
   * pointerdown/mouseup/click sequence, so an instantly-resolving mock lets the *first*
   * click's whole round trip -- request, state update, `finally` -- finish before the
   * *second* click's sequence ever reaches its own click dispatch. The two calls then
   * never actually overlap, and a re-entrancy guard looks like it is doing nothing even
   * though nothing raced it. A few ms of delay, closer to a real network round trip,
   * keeps the first call in flight long enough for the second one to genuinely land
   * while it is still pending.
   */
  delayMs?: number
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
  /** Url and init of every request, for the tests that assert on what was sent. */
  const requests: { url: string; init?: RequestInit }[] = []
  const fetchStub = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    calls.push(url)
    requests.push({ url, init })
    const route = routes.find((candidate) => url.includes(candidate.match))
    if (!route) {
      return Promise.reject(new Error(`No stub route for ${url}`))
    }
    const status = route.status ?? 200
    const body = typeof route.body === 'function' ? (route.body as () => unknown)() : route.body
    const response = {
      ok: status >= 200 && status < 300,
      status,
      json: () => Promise.resolve(body),
    } as Response
    if (!route.delayMs) return Promise.resolve(response)
    return new Promise<Response>((resolve) => setTimeout(() => resolve(response), route.delayMs))
  })
  vi.stubGlobal('fetch', fetchStub)
  return { calls, requests, fetchStub }
}
