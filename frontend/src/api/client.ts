/**
 * Thin fetch wrapper around the backend.
 *
 * The backend reports every error as RFC 7807 ProblemDetail, so failures are unwrapped
 * once here and thrown as ApiError. Callers get a readable message and, for validation
 * failures, the per-field errors -- no component has to parse an error body itself.
 */

export interface ProblemDetail {
  type?: string
  title?: string
  status?: number
  detail?: string
  instance?: string
  /** Present on validation failures: field name -> message */
  errors?: Record<string, string>
}

export class ApiError extends Error {
  readonly status: number
  readonly problem: ProblemDetail

  constructor(status: number, problem: ProblemDetail) {
    super(problem.detail ?? problem.title ?? `Anfrage fehlgeschlagen (${status})`)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
  }

  /** Message for a specific form field, if the backend flagged one. */
  fieldError(field: string): string | undefined {
    return this.problem.errors?.[field]
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: {
      ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      ...init?.headers,
    },
  })

  if (!response.ok) {
    let problem: ProblemDetail = { status: response.status }
    try {
      problem = { ...problem, ...(await response.json()) }
    } catch {
      // Non-JSON error body (proxy error, backend down). Keep the bare status.
    }
    throw new ApiError(response.status, problem)
  }

  // 204 No Content, e.g. after DELETE
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body: unknown) =>
    request<T>(path, { method: 'POST', body: JSON.stringify(body) }),
  patch: <T>(path: string, body: unknown) =>
    request<T>(path, { method: 'PATCH', body: JSON.stringify(body) }),
  delete: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
}
