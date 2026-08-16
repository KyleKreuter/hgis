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
  function setup(unsavedChanges = 0) {
    const jump = vi.fn()
    let api: DeferredLayerJump | null = null

    function Probe({ changes }: { changes: number }) {
      api = useDeferredLayerJump(changes, jump)
      return null
    }

    const view = render(<Probe changes={unsavedChanges} />)
    const setChanges = (changes: number) =>
      act(() => {
        view.rerender(<Probe changes={changes} />)
      })
    return {
      jump,
      request: (layerId: string) => act(() => api!.request(layerId)),
      cancel: () => act(() => api!.cancel()),
      setChanges,
      unmount: view.unmount,
    }
  }

  it('springt sofort, wenn nichts ungespeichert ist', () => {
    const { jump, request } = setup(0)

    request('layer-b')

    expect(jump).toHaveBeenCalledWith('layer-b')
  })

  it('springt nicht, solange ungespeicherte Arbeit laeuft', () => {
    const { jump, request } = setup(3)

    request('layer-b')

    expect(jump).not.toHaveBeenCalled()
  })

  it('holt den Sprung nach, sobald die Arbeit fertig ist', () => {
    const { jump, request, setChanges } = setup(3)
    request('layer-b')

    setChanges(0)

    expect(jump).toHaveBeenCalledWith('layer-b')
    expect(jump).toHaveBeenCalledTimes(1)
  })

  it('behandelt Verwerfen wie Speichern -- beides endet mit leerem Puffer', () => {
    // Der Hook erfaehrt gar nicht, was aus der Arbeit wurde. Genau das ist der Punkt:
    // beides ist kein Grund mehr zu bleiben.
    const { jump, request, setChanges } = setup(2)
    request('layer-b')

    setChanges(0)

    expect(jump).toHaveBeenCalledWith('layer-b')
  })

  it('springt bei einem Zwischenstand noch nicht', () => {
    const { jump, request, setChanges } = setup(3)
    request('layer-b')

    setChanges(1)

    expect(jump).not.toHaveBeenCalled()
  })

  it('holt nur den neuesten von mehreren gewarteten Sprungen nach', () => {
    const { jump, request, setChanges } = setup(1)
    request('layer-b')
    request('layer-c')

    setChanges(0)

    expect(jump).toHaveBeenCalledTimes(1)
    expect(jump).toHaveBeenCalledWith('layer-c')
  })

  it('vergisst einen wartenden Sprung, wenn der Nutzer selbst einen Layer waehlt', () => {
    // Sonst naehme der nachgeholte Sprung ihm gleich wieder weg, was er gerade gewaehlt hat.
    const { jump, request, cancel, setChanges } = setup(1)
    request('layer-b')

    cancel()
    setChanges(0)

    expect(jump).not.toHaveBeenCalled()
  })

  it('springt kein zweites Mal, wenn spaeter erneut gespeichert wird', () => {
    const { jump, request, setChanges } = setup(1)
    request('layer-b')
    setChanges(0)
    expect(jump).toHaveBeenCalledTimes(1)

    setChanges(2)
    setChanges(0)

    expect(jump).toHaveBeenCalledTimes(1)
  })

  it('springt nach dem Verlassen der Seite nicht mehr', () => {
    const { jump, request, unmount } = setup(1)
    request('layer-b')

    unmount()

    expect(jump).not.toHaveBeenCalled()
  })
})
