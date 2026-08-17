import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { LayerField } from '@/api/layers'
import { renderWithQueryClient, stubFetch } from '@/test/render'
import { buildClasses } from './classification'
import { defaultSymbolFor } from './defaults'
import { GraduatedEditor } from './GraduatedEditor'
import { DEFAULT_RAMP } from './palettes'
import type { Renderer } from './types'

function makeFields(): LayerField[] {
  return [{ id: 'f-hoehe', sourceName: 'Höhe', columnName: 'hoehe', dataType: 'integer' }]
}

/**
 * Team review, package 3 addendum, the `graduated`/`ramp` counterpart to
 * `CategorizedEditor.test.tsx`'s two suites. `GraduatedEditor` has no dedicated
 * "recolor" button -- `selectRamp` (the palette picker itself) is the closest
 * equivalent, and it always receives an already-resolved value straight from
 * `PaletteSelect`. But `selectField`, `selectMethod` and `selectClassCount` all replay
 * the current `ramp` local state through the same `request`, and that state can hold a
 * name `initialGraduatedControls` never validated -- it only defaults a *missing*
 * `renderer.ramp`, not one naming a ramp since renamed or removed. Exercised here via
 * `selectMethod` (the "Methode" control); the fix lives in `request` itself, so the same
 * `resolvePaletteId` call covers `selectField` and `selectClassCount` too.
 */
describe('GraduatedEditor „Methode ändern“ (team review, package 3 addendum)', () => {
  it('schreibt bei einem unbekannten, gespeicherten Rampen-Namen den tatsächlich benutzten Namen zurück', async () => {
    const stored = buildClasses([0, 40, 320, 900], 'MULTIPOLYGON', DEFAULT_RAMP)
    const renderer: Extract<Renderer, { type: 'graduated' }> = {
      type: 'graduated',
      field: 'hoehe',
      classes: stored,
      fallbackSymbol: defaultSymbolFor('MULTIPOLYGON'),
      method: 'quantile',
      classCount: stored.length,
      // Ein Name, den kein `COLOR_RAMPS`-Eintrag mehr trägt -- z. B. umbenannt oder
      // entfernt, seit dieser Stil gespeichert wurde.
      ramp: 'brewer-set2',
    }
    const onChange = vi.fn()
    stubFetch([
      {
        match: '/classify',
        body: { field: 'hoehe', method: 'equalInterval', breaks: [0, 300, 600, 900], min: 0, max: 900, nullCount: 0 },
      },
    ])

    renderWithQueryClient(
      <GraduatedEditor layerId="layer-1" geometryType="MULTIPOLYGON" renderer={renderer} fields={makeFields()} onChange={onChange} />,
    )

    // Comboboxen in Reihenfolge: Feld, Methode, Rampe (`Row`s eigenes Label ist nicht
    // per `htmlFor`/`aria-labelledby` verknüpft, daher über die Reihenfolge).
    await userEvent.click(screen.getAllByRole('combobox')[1])
    await userEvent.click(await screen.findByRole('option', { name: 'Gleiche Intervalle' }))

    await waitFor(() => {
      const last = onChange.mock.calls.at(-1)?.[0] as Extract<Renderer, { type: 'graduated' }>
      expect(last.ramp).toBe(DEFAULT_RAMP)
      expect(last.ramp).not.toBe('brewer-set2')
    })
  })

  it('lässt einen gültigen Rampen-Namen unverändert', async () => {
    const stored = buildClasses([0, 40, 320, 900], 'MULTIPOLYGON', 'reds')
    const renderer: Extract<Renderer, { type: 'graduated' }> = {
      type: 'graduated',
      field: 'hoehe',
      classes: stored,
      fallbackSymbol: defaultSymbolFor('MULTIPOLYGON'),
      method: 'quantile',
      classCount: stored.length,
      ramp: 'reds',
    }
    const onChange = vi.fn()
    stubFetch([
      {
        match: '/classify',
        body: { field: 'hoehe', method: 'equalInterval', breaks: [0, 300, 600, 900], min: 0, max: 900, nullCount: 0 },
      },
    ])

    renderWithQueryClient(
      <GraduatedEditor layerId="layer-1" geometryType="MULTIPOLYGON" renderer={renderer} fields={makeFields()} onChange={onChange} />,
    )

    await userEvent.click(screen.getAllByRole('combobox')[1])
    await userEvent.click(await screen.findByRole('option', { name: 'Gleiche Intervalle' }))

    await waitFor(() => {
      const last = onChange.mock.calls.at(-1)?.[0] as Extract<Renderer, { type: 'graduated' }>
      expect(last.ramp).toBe('reds')
    })
  })
})
