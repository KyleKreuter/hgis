import { describe, expect, it } from 'vitest'
import { ApiError } from './client'
import { buildExportRequest, exportErrorMessage, filenameFromContentDisposition } from './export'

describe('buildExportRequest', () => {
  it('requests the whole layer via GET when fids is omitted', () => {
    const { url, init } = buildExportRequest('layer-1')
    expect(url).toBe('/api/layers/layer-1/export.geojson')
    expect(init).toEqual({ method: 'GET' })
  })

  it('sends a small selection as a GET query parameter', () => {
    const { url, init } = buildExportRequest('layer-1', [1, 2, 3])
    expect(url).toBe('/api/layers/layer-1/export.geojson?fids=1,2,3')
    expect(init).toEqual({ method: 'GET' })
  })

  it('sends an explicitly empty selection as ?fids= (not the whole layer)', () => {
    const { url, init } = buildExportRequest('layer-1', [])
    expect(url).toBe('/api/layers/layer-1/export.geojson?fids=')
    expect(init).toEqual({ method: 'GET' })
  })

  it('still uses GET at exactly 300 fids', () => {
    const fids = Array.from({ length: 300 }, (_, i) => i)
    const { init } = buildExportRequest('layer-1', fids)
    expect(init.method).toBe('GET')
  })

  it('switches to POST with a JSON body above 300 fids', () => {
    const fids = Array.from({ length: 301 }, (_, i) => i)
    const { url, init } = buildExportRequest('layer-1', fids)
    expect(url).toBe('/api/layers/layer-1/export.geojson')
    expect(init.method).toBe('POST')
    expect(init.headers).toEqual({ 'Content-Type': 'application/json' })
    expect(init.body).toBe(JSON.stringify({ fids }))
  })

  it('encodes the layer id in the URL', () => {
    const { url } = buildExportRequest('a/b c')
    expect(url).toBe('/api/layers/a%2Fb%20c/export.geojson')
  })
})

describe('filenameFromContentDisposition', () => {
  it('falls back to the default when the header is missing', () => {
    expect(filenameFromContentDisposition(null)).toBe('export.geojson')
    expect(filenameFromContentDisposition(undefined)).toBe('export.geojson')
  })

  it('prefers the percent-decoded filename* form, umlauts included', () => {
    const header =
      'attachment; filename="Grundstuecke.geojson"; filename*=UTF-8\'\'Grundst%C3%BCcke.geojson'
    expect(filenameFromContentDisposition(header)).toBe('Grundstücke.geojson')
  })

  it('falls back to the plain filename when filename* is absent', () => {
    const header = 'attachment; filename="layer.geojson"'
    expect(filenameFromContentDisposition(header)).toBe('layer.geojson')
  })

  it('unescapes backslash-escaped quotes in the plain filename', () => {
    const header = 'attachment; filename="a \\"b\\".geojson"'
    expect(filenameFromContentDisposition(header)).toBe('a "b".geojson')
  })

  it('falls back to the default when neither form is present', () => {
    expect(filenameFromContentDisposition('attachment')).toBe('export.geojson')
  })

  it('falls back past a malformed filename* to the plain form', () => {
    const header = 'attachment; filename="layer.geojson"; filename*=UTF-8\'\'%E0%A4%A'
    expect(filenameFromContentDisposition(header)).toBe('layer.geojson')
  })

  it('accepts a custom fallback', () => {
    expect(filenameFromContentDisposition(null, 'auswahl.geojson')).toBe('auswahl.geojson')
  })
})

describe('exportErrorMessage', () => {
  it('rewords a 413 into a non-technical sentence', () => {
    const error = new ApiError(413, { status: 413, detail: 'Anfrage überschreitet 4194304 Bytes' })
    expect(exportErrorMessage(error)).toBe('Zu viele Objekte für den Export ausgewählt.')
  })

  it('passes through the backend detail for other statuses', () => {
    const error = new ApiError(404, { status: 404, detail: 'Layer nicht gefunden' })
    expect(exportErrorMessage(error)).toBe('Layer nicht gefunden')
  })

  it('gives a generic message for a non-ApiError failure', () => {
    expect(exportErrorMessage(new TypeError('Failed to fetch'))).toBe('Export fehlgeschlagen')
  })
})
