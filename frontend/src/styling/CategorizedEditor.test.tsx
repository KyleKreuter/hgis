import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { LayerField } from '@/api/layers'
import { renderWithQueryClient, stubFetch } from '@/test/render'
import { CategorizedEditor } from './CategorizedEditor'
import { defaultSymbolFor, withPrimaryColor } from './defaults'
import { DEFAULT_RAMP, paletteColors } from './palettes'
import type { Renderer, StyleCategory } from './types'

function makeCategories(): StyleCategory[] {
  return [
    { value: 'a', label: 'A', symbol: withPrimaryColor(defaultSymbolFor('MULTIPOLYGON'), '#111111') },
    { value: 'b', label: 'B', symbol: withPrimaryColor(defaultSymbolFor('MULTIPOLYGON'), '#222222') },
  ]
}

function makeFields(): LayerField[] {
  return [
    { id: 'f-alt', sourceName: 'Alt', columnName: 'alt', dataType: 'text' },
    { id: 'f-neu', sourceName: 'Neu', columnName: 'neu', dataType: 'text' },
  ]
}

/**
 * The crash found on the running system: a stored categorized renderer without
 * `fallbackSymbol` at all -- an older row, or a client that bypassed validation, from
 * before the server started requiring it (`LayerStyleService.validateRenderer`). The
 * "Sonstige" row read `renderer.fallbackSymbol` unguarded during render
 * (`primaryColorOf`, `defaults.ts`), so opening this panel for such a layer took the
 * whole component down with `TypeError: Cannot read properties of undefined (reading
 * 'kind')` -- the same failure `styleToMapLibre.ts`'s `dataDriven` has for the map
 * itself, just reachable a different way.
 */
describe('CategorizedEditor ohne fallbackSymbol (Bestandsstil vor der Pflicht)', () => {
  it('rendert die „Sonstige“-Zeile mit einer Ersatzfarbe, statt abzustürzen', () => {
    const renderer = {
      type: 'categorized',
      field: 'kategorie',
      categories: [],
    } as unknown as Extract<Renderer, { type: 'categorized' }>

    renderWithQueryClient(
      <CategorizedEditor layerId="layer-1" geometryType="MULTIPOLYGON" renderer={renderer} fields={[]} onChange={vi.fn()} />,
    )

    expect(screen.getByLabelText('Farbe für alle übrigen Werte')).toBeInTheDocument()
  })
})

/**
 * Team review, package 3 addendum: the button ("Farben neu über die Kategorien
 * verteilen") calls `recolor(palette)`, replaying whatever `palette` local state
 * currently holds -- and a style saved with a `palette` name that has since been renamed
 * or removed (`initialCategorizedPalette` only defaults a *missing* value, never
 * validates one that is present) seeds that state with exactly such a name. Before the
 * fix, pressing the button repainted every category from `DEFAULT_RAMP` while writing the
 * old, unresolved name back into `renderer.palette` unchanged -- the stored style then
 * claimed a palette the colours on screen no longer matched.
 */
describe('CategorizedEditor „Farben neu verteilen“ (team review, package 3 addendum)', () => {
  it('schreibt bei einem unbekannten, gespeicherten Palettennamen den tatsächlich benutzten Namen zurück', async () => {
    const categories = makeCategories()
    const renderer: Extract<Renderer, { type: 'categorized' }> = {
      type: 'categorized',
      field: 'kategorie',
      categories,
      fallbackSymbol: defaultSymbolFor('MULTIPOLYGON'),
      // Ein Name, den kein `COLOR_RAMPS`-Eintrag mehr trägt -- z. B. umbenannt oder
      // entfernt, seit dieser Stil gespeichert wurde.
      palette: 'brewer-set2',
    }
    const onChange = vi.fn()

    renderWithQueryClient(
      <CategorizedEditor layerId="layer-1" geometryType="MULTIPOLYGON" renderer={renderer} fields={[]} onChange={onChange} />,
    )

    await userEvent.click(screen.getByLabelText('Farben neu über die Kategorien verteilen'))

    expect(onChange).toHaveBeenCalledTimes(1)
    const written = onChange.mock.calls[0][0] as Extract<Renderer, { type: 'categorized' }>
    // Nicht mehr der unaufgelöste Name -- der Katalog-Name, aus dem tatsächlich gemalt wurde.
    expect(written.palette).toBe(DEFAULT_RAMP)
    expect(written.palette).not.toBe('brewer-set2')

    const expectedColors = paletteColors(DEFAULT_RAMP, categories.length)
    expect(written.categories.map((category) => category.symbol.kind === 'fill' ? category.symbol.fillColor : null))
      .toEqual(expectedColors)
  })

  it('lässt einen gültigen Palettennamen unverändert', async () => {
    const categories = makeCategories()
    const renderer: Extract<Renderer, { type: 'categorized' }> = {
      type: 'categorized',
      field: 'kategorie',
      categories,
      fallbackSymbol: defaultSymbolFor('MULTIPOLYGON'),
      palette: 'reds',
    }
    const onChange = vi.fn()

    renderWithQueryClient(
      <CategorizedEditor layerId="layer-1" geometryType="MULTIPOLYGON" renderer={renderer} fields={[]} onChange={onChange} />,
    )

    await userEvent.click(screen.getByLabelText('Farben neu über die Kategorien verteilen'))

    const written = onChange.mock.calls[0][0] as Extract<Renderer, { type: 'categorized' }>
    expect(written.palette).toBe('reds')
  })
})

/**
 * Team review, package 3 addendum, second occurrence of the same class: `selectField`
 * calls `request(field, palette, [])`, replaying the very same `palette` state as the
 * shuffle button above -- a field change on a style with an unresolved `palette` used to
 * repaint the fresh categories from `DEFAULT_RAMP` while writing the old, unresolved name
 * back into `renderer.palette` unchanged, exactly as the shuffle button did before its fix.
 */
describe('CategorizedEditor Feldwechsel (team review, package 3 addendum)', () => {
  it('schreibt bei einem unbekannten, gespeicherten Palettennamen den tatsächlich benutzten Namen zurück', async () => {
    const renderer: Extract<Renderer, { type: 'categorized' }> = {
      type: 'categorized',
      field: 'alt',
      categories: makeCategories(),
      fallbackSymbol: defaultSymbolFor('MULTIPOLYGON'),
      palette: 'brewer-set2',
    }
    const onChange = vi.fn()
    stubFetch([
      {
        match: '/values',
        body: { field: 'neu', values: [{ value: 'x', count: 3 }, { value: 'y', count: 1 }], truncated: false },
      },
    ])

    renderWithQueryClient(
      <CategorizedEditor layerId="layer-1" geometryType="MULTIPOLYGON" renderer={renderer} fields={makeFields()} onChange={onChange} />,
    )

    // Erste Combobox der Reihe ist das Feld -- `Row`s eigenes Label ist nicht per
    // `htmlFor`/`aria-labelledby` verknüpft, daher über die Reihenfolge statt den Namen.
    await userEvent.click(screen.getAllByRole('combobox')[0])
    await userEvent.click(await screen.findByRole('option', { name: 'Neu' }))

    await waitFor(() => {
      const last = onChange.mock.calls.at(-1)?.[0] as Extract<Renderer, { type: 'categorized' }>
      expect(last.palette).toBe(DEFAULT_RAMP)
      expect(last.palette).not.toBe('brewer-set2')
    })
  })
})
