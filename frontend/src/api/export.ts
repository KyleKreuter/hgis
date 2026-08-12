import { useMutation } from '@tanstack/react-query'
import { ApiError, type ProblemDetail } from './client'

/**
 * Download mechanics for `GET`/`POST /api/layers/{layerId}/export.geojson`.
 *
 * `api.*` from `client.ts` cannot be reused here: `request()` always calls
 * `response.json()`, but an export response is a file body meant for `response.blob()`.
 * This module therefore talks to `fetch` directly and only replicates the `ApiError`
 * contract from `client.ts`, not its JSON-only response handling.
 */

/**
 * Above this many fids the query string risks the container's ~8 KB header cap (see
 * `ExportController` on the backend). 300 twenty-digit ids with separating commas stay
 * comfortably under that, so GET is used up to and including this count, POST above it.
 */
const GET_FID_LIMIT = 300

const DEFAULT_FILENAME = 'export.geojson'

function exportUrl(layerId: string): string {
  return `/api/layers/${encodeURIComponent(layerId)}/export.geojson`
}

/**
 * Which request shape to send for a given selection.
 *
 * `fids` absent means "the whole layer", exactly as the backend reads a missing `fids`
 * parameter -- it must never be conflated with an empty array, which asks for an empty
 * file instead (`FidSelection` on the backend draws the same line).
 */
export function buildExportRequest(
  layerId: string,
  fids?: number[],
): { url: string; init: RequestInit } {
  const url = exportUrl(layerId)

  if (fids === undefined) {
    return { url, init: { method: 'GET' } }
  }
  if (fids.length <= GET_FID_LIMIT) {
    return { url: `${url}?fids=${fids.join(',')}`, init: { method: 'GET' } }
  }
  return {
    url,
    init: {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ fids }),
    },
  }
}

const FILENAME_STAR = /filename\*=UTF-8''([^;]+)/i
const FILENAME_PLAIN = /filename="((?:[^"\\]|\\.)*)"/i

/**
 * Reads the filename the backend chose from a `Content-Disposition` header.
 *
 * `filename*=UTF-8''...` is preferred: it is percent-encoded UTF-8 (RFC 5987), so it
 * survives umlauts and other non-ASCII characters that the plain `filename="..."` --
 * ASCII-only on the backend, see `ExportFilename` -- has to transliterate away.
 */
export function filenameFromContentDisposition(
  header: string | null | undefined,
  fallback = DEFAULT_FILENAME,
): string {
  if (!header) return fallback

  const starMatch = FILENAME_STAR.exec(header)
  if (starMatch) {
    try {
      return decodeURIComponent(starMatch[1])
    } catch {
      // Malformed percent-encoding -- fall through to the ASCII form below.
    }
  }

  const plainMatch = FILENAME_PLAIN.exec(header)
  if (plainMatch) {
    return plainMatch[1].replace(/\\(.)/g, '$1')
  }

  return fallback
}

/** Readable text for a failed export -- plainer than the technical 413 byte-limit detail. */
export function exportErrorMessage(caught: unknown): string {
  if (caught instanceof ApiError) {
    if (caught.status === 413) {
      return 'Sie haben zu viele Objekte für den Export ausgewählt.'
    }
    return caught.message
  }
  return 'Export fehlgeschlagen'
}

/**
 * Hands a blob to the browser's download machinery via a throwaway `<a download>`.
 *
 * The object URL is revoked right after, but not synchronously with `click()`: some
 * browsers start the navigation on the next tick, and revoking too early can cancel a
 * download that never got to read the blob.
 */
function triggerDownload(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 0)
}

/**
 * Exports a layer (or a selection within it) as GeoJSON and starts the browser download.
 *
 * @param fids omit for the whole layer; pass fids for a selection. Never pass an empty
 *   array meaning "everything" -- the backend reads an empty selection as "nothing" and
 *   downloads an empty file.
 */
export async function exportLayer(layerId: string, fids?: number[]): Promise<void> {
  const { url, init } = buildExportRequest(layerId, fids)
  const response = await fetch(url, init)

  if (!response.ok) {
    let problem: ProblemDetail = { status: response.status }
    try {
      problem = { ...problem, ...(await response.json()) }
    } catch {
      // Non-JSON error body (proxy error, backend down). Keep the bare status.
    }
    throw new ApiError(response.status, problem)
  }

  const blob = await response.blob()
  const filename = filenameFromContentDisposition(response.headers.get('Content-Disposition'))
  triggerDownload(blob, filename)
}

/**
 * One mutation instance per menu entry ("Layer exportieren" / "Auswahl exportieren") so
 * the spinner appears on whichever entry the user actually clicked, showing which of the
 * two exports is running. Disabling is a separate concern the caller layers on top, and
 * is deliberately shared across both entries while either is pending -- see `LayerRow`
 * in `LayerTree.tsx`.
 */
export function useExportLayer() {
  return useMutation({
    mutationFn: ({ layerId, fids }: { layerId: string; fids?: number[] }) =>
      exportLayer(layerId, fids),
  })
}
