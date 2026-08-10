import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createRouter, RouterProvider } from '@tanstack/react-router'
import { ApiError } from './api/client'
import { routeTree } from './routeTree.gen'
import './index.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Catalog data changes rarely and only through our own mutations, so a short
      // stale window avoids refetch storms while panels mount.
      staleTime: 30_000,
      refetchOnWindowFocus: false,
      // A 4xx says the request itself was wrong -- a bad filter expression, a layer that
      // no longer exists -- and repeating it verbatim can only fail the same way. Only
      // server and network errors are worth a second attempt.
      retry: (failureCount, error) => {
        const status = error instanceof ApiError ? error.status : undefined
        if (status !== undefined && status >= 400 && status < 500) return false
        return failureCount < 1
      },
    },
  },
})

const router = createRouter({
  routeTree,
  context: { queryClient },
  defaultPreload: 'intent',
})

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
)
