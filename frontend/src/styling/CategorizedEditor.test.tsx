import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { renderWithQueryClient } from '@/test/render'
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
