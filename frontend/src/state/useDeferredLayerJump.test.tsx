import { describe, expect, it, vi } from 'vitest'
import { act, render } from '@testing-library/react'
import { useDeferredLayerJump, type DeferredLayerJump } from './useDeferredLayerJump'

/**
 * Ob ein Sprung jetzt oder spaeter stattfindet -- und dass er nicht verlorengeht.
 *
 * Die Bedienung laeuft ueber ein Probe-Bauteil statt ueber `renderHook`, weil der
 * interessante Teil das Zusammenspiel mit dem *neuen* Zaehler ist: der Sprung wird von
 * ausserhalb des Renderns angefordert und muss beim naechsten Rendern mit Zaehler 0
 * nachgeholt werden.
 */
describe('useDeferredLayerJump', () => {
  function setup(workAtRisk = false) {
    const jump = vi.fn()
    let api: DeferredLayerJump | null = null

    function Probe({ atRisk }: { atRisk: boolean }) {
      api = useDeferredLayerJump(atRisk, jump)
      return null
    }

    const view = render(<Probe atRisk={workAtRisk} />)
    const setAtRisk = (atRisk: boolean) =>
      act(() => {
        view.rerender(<Probe atRisk={atRisk} />)
      })
    return {
      jump,
      request: (layerId: string) => act(() => api!.request(layerId)),
      cancel: () => act(() => api!.cancel()),
      setAtRisk,
      unmount: view.unmount,
    }
  }

  it('springt sofort, wenn nichts ungespeichert ist', () => {
    const { jump, request } = setup(false)

    request('layer-b')

    expect(jump).toHaveBeenCalledWith('layer-b')
  })

  it('springt nicht, solange ungespeicherte Arbeit laeuft', () => {
    const { jump, request } = setup(true)

    request('layer-b')

    expect(jump).not.toHaveBeenCalled()
  })

  it('holt den Sprung nach, sobald die Arbeit fertig ist', () => {
    const { jump, request, setAtRisk } = setup(true)
    request('layer-b')

    setAtRisk(false)

    expect(jump).toHaveBeenCalledWith('layer-b')
    expect(jump).toHaveBeenCalledTimes(1)
  })

  it('behandelt Verwerfen wie Speichern -- beides endet mit leerem Puffer', () => {
    // Der Hook erfaehrt gar nicht, was aus der Arbeit wurde. Genau das ist der Punkt:
    // beides ist kein Grund mehr zu bleiben.
    const { jump, request, setAtRisk } = setup(true)
    request('layer-b')

    setAtRisk(false)

    expect(jump).toHaveBeenCalledWith('layer-b')
  })

  it('springt nicht bei einem Rendern, das nichts freigibt', () => {
    const { jump, request, setAtRisk } = setup(true)
    request('layer-b')

    setAtRisk(true)

    expect(jump).not.toHaveBeenCalled()
  })

  it('holt nur den neuesten von mehreren gewarteten Sprungen nach', () => {
    const { jump, request, setAtRisk } = setup(true)
    request('layer-b')
    request('layer-c')

    setAtRisk(false)

    expect(jump).toHaveBeenCalledTimes(1)
    expect(jump).toHaveBeenCalledWith('layer-c')
  })

  it('vergisst einen wartenden Sprung, wenn der Nutzer selbst einen Layer waehlt', () => {
    // Sonst naehme der nachgeholte Sprung ihm gleich wieder weg, was er gerade gewaehlt hat.
    const { jump, request, cancel, setAtRisk } = setup(true)
    request('layer-b')

    cancel()
    setAtRisk(false)

    expect(jump).not.toHaveBeenCalled()
  })

  it('springt kein zweites Mal, wenn spaeter erneut gespeichert wird', () => {
    const { jump, request, setAtRisk } = setup(true)
    request('layer-b')
    setAtRisk(false)
    expect(jump).toHaveBeenCalledTimes(1)

    setAtRisk(true)
    setAtRisk(false)

    expect(jump).toHaveBeenCalledTimes(1)
  })

  it('springt nach dem Verlassen der Seite nicht mehr', () => {
    const { jump, request, unmount } = setup(true)
    request('layer-b')

    unmount()

    expect(jump).not.toHaveBeenCalled()
  })
})
