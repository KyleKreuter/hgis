import { afterEach, describe, expect, test, vi } from 'vitest'
import { ApiError, api, type ProblemDetail } from './client'

/**
 * Every failed request in the application comes out of here, so what this file gets
 * wrong is wrong everywhere at once: a message the user reads, a per-field error a form
 * shows next to its input, or a `DELETE` that returns a body where there is none.
 */

afterEach(() => {
  vi.unstubAllGlobals()
})

/** Answers one request; `body` is returned as JSON unless `text` is given instead. */
function stubResponse(options: {
  status?: number
  body?: unknown
  /** A non-JSON error body, as a proxy or a stopped backend sends it. */
  text?: string
}) {
  const status = options.status ?? 200
  // Typed with both parameters so the assertions below can read the RequestInit the
  // wrapper built -- the headers are half of what this file is about.
  const fetchStub = vi.fn((_input: RequestInfo | URL, _init?: RequestInit) =>
    Promise.resolve({
      ok: status >= 200 && status < 300,
      status,
      json: () =>
        options.text === undefined
          ? Promise.resolve(options.body)
          : Promise.reject(new SyntaxError('Unexpected token')),
    } as Response),
  )
  vi.stubGlobal('fetch', fetchStub)
  return fetchStub
}

describe('request', () => {
  test('returns the parsed body on success', async () => {
    stubResponse({ body: { id: 'p-1', name: 'Hamburg' } })
    await expect(api.get('/api/projects/p-1')).resolves.toEqual({ id: 'p-1', name: 'Hamburg' })
  })

  test('returns undefined for 204, without reading a body', async () => {
    const fetchStub = stubResponse({ status: 204, body: undefined })
    await expect(api.delete('/api/projects/p-1')).resolves.toBeUndefined()
    expect(fetchStub).toHaveBeenCalledWith('/api/projects/p-1', expect.objectContaining({ method: 'DELETE' }))
  })

  test('sends a JSON content type for a body, and none for FormData', async () => {
    const fetchStub = stubResponse({ body: {} })
    await api.post('/api/projects', { name: 'Hamburg' })
    expect(fetchStub.mock.calls[0][1]).toMatchObject({
      headers: { 'Content-Type': 'application/json' },
    })

    // The browser has to set this one itself -- only it knows the multipart boundary.
    const form = new FormData()
    form.append('file', new Blob(['x']), 'a.geojson')
    await api.postForm('/api/imports', form)
    expect(fetchStub.mock.calls[1][1]).not.toHaveProperty('headers.Content-Type')
  })

  test('sends no content type for a GET, which carries no body', async () => {
    const fetchStub = stubResponse({ body: {} })
    await api.get('/api/projects')
    expect(fetchStub.mock.calls[0][1]).not.toHaveProperty('headers.Content-Type')
  })
})

describe('ApiError', () => {
  /** Performs a failing request and returns the ApiError it threw. */
  async function failWith(
    status: number,
    problem: ProblemDetail | undefined,
    text?: string,
  ): Promise<ApiError> {
    stubResponse({ status, body: problem, text })
    try {
      await api.get('/api/layers/l-1')
    } catch (caught) {
      if (caught instanceof ApiError) return caught
      throw caught
    }
    throw new Error(`expected status ${status} to reject, but the request resolved`)
  }

  test('reads the message out of the problem detail', async () => {
    const error = await failWith(400, { title: 'Bad Request', detail: 'Feld "baujahr" ist unbekannt.' })
    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(400)
    expect(error.message).toBe('Feld "baujahr" ist unbekannt.')
  })

  test('falls back to the title, then to a readable default', async () => {
    expect((await failWith(409, { title: 'Conflict' })).message).toBe('Conflict')
    // Neither field set: the message must still say something, and name the status.
    expect((await failWith(500, {})).message).toBe('Anfrage fehlgeschlagen (500)')
  })

  test('keeps the bare status when the error body is not JSON', async () => {
    const error = await failWith(502, undefined, '<html>Bad Gateway</html>')
    expect(error.status).toBe(502)
    expect(error.problem).toEqual({ status: 502 })
    expect(error.message).toBe('Anfrage fehlgeschlagen (502)')
  })

  test('exposes per-field validation errors', async () => {
    const error = await failWith(400, {
      detail: 'Validierung fehlgeschlagen',
      errors: { name: 'darf nicht leer sein' },
    })
    expect(error.fieldError('name')).toBe('darf nicht leer sein')
    expect(error.fieldError('srid')).toBeUndefined()
  })

  test('has no field errors when the problem carries none', async () => {
    expect((await failWith(500, { detail: 'Interner Fehler' })).fieldError('name')).toBeUndefined()
  })
})
