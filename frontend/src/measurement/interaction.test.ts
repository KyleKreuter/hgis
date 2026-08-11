import { describe, expect, it } from 'vitest'
import { suspendHandler, type ToggleableHandler } from './interaction'

function createHandler(enabled: boolean): ToggleableHandler & { enabled: boolean } {
  const handler = {
    enabled,
    isEnabled: () => handler.enabled,
    enable: () => {
      handler.enabled = true
    },
    disable: () => {
      handler.enabled = false
    },
  }
  return handler
}

describe('suspendHandler', () => {
  it('schaltet ab und danach wieder an', () => {
    const handler = createHandler(true)

    const restore = suspendHandler(handler)
    expect(handler.enabled).toBe(false)

    restore()
    expect(handler.enabled).toBe(true)
  })

  /**
   * Der Befund: das Zeichenwerkzeug schaltet den Doppelklick-Zoom ebenfalls ab, weil
   * ein Doppelklick dort eine Form schließt. Das Ende der Messung schaltete ihn
   * bedingungslos wieder ein -- und jeder Doppelklick, der ein Polygon schloss, zoomte
   * die Karte gleich mit.
   */
  it('lässt aus, was schon vorher aus war', () => {
    const handler = createHandler(false)

    const restore = suspendHandler(handler)
    restore()

    expect(handler.enabled).toBe(false)
  })

  it('stellt genau einmal wieder her', () => {
    const handler = createHandler(true)
    const restore = suspendHandler(handler)

    restore()
    handler.disable()
    restore()

    expect(handler.enabled).toBe(false)
  })
})
