// @vitest-environment jsdom
// Braucht ein DOM, liegt aber im `unit`-Projekt, weil es kein JSX enthält.

import { describe, expect, test, vi } from 'vitest'
import { releaseWebGl } from './releaseWebGl'

/**
 * The leak this closes is invisible in a test run -- it needs a browser that counts live
 * contexts. What is checked here is that the release actually happens on the map's own
 * canvas, and that this cleanup never throws: it runs inside a `catch` that still has a
 * ref to reset after it.
 */

function containerWithCanvas(gl: unknown) {
  const canvas = document.createElement('canvas')
  canvas.getContext = vi.fn((type: string) => (type === 'webgl2' ? gl : null)) as never
  const container = document.createElement('div')
  container.append(canvas)
  return container
}

describe('releaseWebGl', () => {
  test('verliert den Kontext der vorhandenen Zeichenfläche', () => {
    const loseContext = vi.fn()
    const container = containerWithCanvas({ getExtension: () => ({ loseContext }) })

    releaseWebGl(container)

    expect(loseContext).toHaveBeenCalledOnce()
  })

  test('kommt ohne Container zurecht', () => {
    expect(() => releaseWebGl(null)).not.toThrow()
  })

  test('kommt ohne Zeichenfläche zurecht', () => {
    expect(() => releaseWebGl(document.createElement('div'))).not.toThrow()
  })

  test('kommt ohne WebGL zurecht', () => {
    expect(() => releaseWebGl(containerWithCanvas(null))).not.toThrow()
  })

  test('kommt ohne die Erweiterung zurecht', () => {
    // Ohne WEBGL_lose_context bleibt der Kontext liegen. Das ist nicht zu ändern, darf
    // aber nicht den Rest des Aufräumens mitreißen.
    const container = containerWithCanvas({ getExtension: () => null })

    expect(() => releaseWebGl(container)).not.toThrow()
  })

  test('wirft auch dann nicht, wenn das Freigeben selbst fehlschlägt', () => {
    const container = containerWithCanvas({
      getExtension: () => ({
        loseContext: () => {
          throw new Error('kaputt')
        },
      }),
    })

    expect(() => releaseWebGl(container)).not.toThrow()
  })
})
