import type { QueryClient } from '@tanstack/react-query'
import { describe, expect, it } from 'vitest'
import type { ClassifyResult } from '@/api/layers'
import {
  buildClasses,
  initialGraduatedControls,
  requestGraduatedClasses,
} from './classification'
import { defaultSymbolFor, withPrimaryColor } from './defaults'
import { DEFAULT_RAMP } from './palettes'
import type { Renderer, StyleClass } from './types'

/**
 * CONTRACT.md, package B1. Exercises the two functions `GraduatedEditor` actually
 * calls -- `initialGraduatedControls` (seeds the panel's local state, once, from the
 * saved renderer) and `requestGraduatedClasses` (the only place classes are ever
 * rebuilt) -- rather than rendering the component, which this project's vitest run
 * cannot do (`environment: 'node'`, `*.test.ts` only, no jsdom, no @testing-library).
 *
 * Before the fix, `GraduatedEditor` instead ran a `useEffect` that watched `renderer`,
 * `method`, `classCount` and `ramp`, with `method` and `ramp` hard-coded local state
 * that never remembered what had actually produced the saved classes. Its guard ref
 * started out `null` on every mount, which did not distinguish "just opened" from "the
 * user changed something" -- so the effect rebuilt the classes, with the wrong method
 * and ramp, the moment the panel opened. That bug is gone: nothing in `GraduatedEditor`
 * observes state anymore, `requestGraduatedClasses` runs only from a control's own
 * handler (Feld, Methode, Klassen, Farbverlauf).
 */
describe('GraduatedEditor beim Öffnen (CONTRACT.md B1)', () => {
  const fallbackSymbol = withPrimaryColor(defaultSymbolFor('MULTIPOLYGON'), '#a3a3a3')

  it('liest Methode, Klassenzahl und Farbverlauf aus der gespeicherten Symbologie', () => {
    const stored = buildClasses([0, 40, 320, 900], 'MULTIPOLYGON', 'reds')
    const renderer: Extract<Renderer, { type: 'graduated' }> = {
      type: 'graduated',
      field: 'hoehe',
      classes: stored,
      fallbackSymbol,
      method: 'naturalBreaks',
      classCount: 4,
      ramp: 'reds',
    }

    expect(initialGraduatedControls(renderer, stored)).toEqual({
      method: 'naturalBreaks',
      classCount: 4,
      ramp: 'reds',
    })
  })

  /** One of the tests CONTRACT.md names explicitly for step B1. */
  it('öffnet eine Symbologie ohne die neuen Felder mit den heutigen Vorgaben', () => {
    const stored = buildClasses([0, 120, 340, 780], 'MULTIPOLYGON', DEFAULT_RAMP)
    const renderer: Extract<Renderer, { type: 'graduated' }> = {
      type: 'graduated',
      field: 'hoehe',
      classes: stored,
      fallbackSymbol,
    }

    expect(initialGraduatedControls(renderer, stored)).toEqual({
      method: 'quantile',
      classCount: stored.length,
      ramp: DEFAULT_RAMP,
    })
  })

  /**
   * The Schritt-1 proof, updated to match the fix: a hand-recoloured class, and a
   * classification saved under a method other than 'quantile', both used to be wiped
   * out by the mere act of opening the panel. Opening now means "seed the controls from
   * the renderer" and nothing else -- no request, no rebuild -- so the saved classes,
   * colour and all, are exactly what they were before.
   */
  it('verändert eine gespeicherte Klasseneinteilung nicht, wenn nur die Ansicht aufgebaut wird', () => {
    const built = buildClasses([0, 40, 320, 900], 'MULTIPOLYGON', 'reds')
    const stored: StyleClass[] = built.map((styleClass, index) =>
      index === 0 ? { ...styleClass, symbol: withPrimaryColor(styleClass.symbol, '#123456') } : styleClass,
    )
    const renderer: Extract<Renderer, { type: 'graduated' }> = {
      type: 'graduated',
      field: 'hoehe',
      classes: stored,
      fallbackSymbol,
      method: 'naturalBreaks',
      classCount: 4,
      ramp: 'reds',
    }

    // "Opening the panel" -- the only thing `GraduatedEditor` does with `renderer`
    // before any control is touched.
    initialGraduatedControls(renderer, renderer.classes ?? [])

    expect(renderer.classes).toBe(stored)
  })

  /** The corresponding "das ist noch möglich" case: an explicit control action does rebuild. */
  it('baut die Klassen neu auf, wenn die Klassenzahl gezielt geändert wird', async () => {
    const stored = buildClasses([0, 120, 340, 780], 'MULTIPOLYGON', DEFAULT_RAMP)
    const nextResult: ClassifyResult = {
      field: 'hoehe',
      method: 'quantile',
      breaks: [0, 90, 250, 500, 780],
      min: 0,
      max: 780,
      nullCount: 3,
    }
    const queryClient = { fetchQuery: async () => nextResult } as unknown as QueryClient

    const { classes } = await requestGraduatedClasses(
      queryClient,
      'layer-1',
      'MULTIPOLYGON',
      'hoehe',
      'quantile',
      4,
      DEFAULT_RAMP,
      stored,
      fallbackSymbol,
    )

    expect(classes).toHaveLength(4)
    expect(classes.map((styleClass) => [styleClass.min, styleClass.max])).not.toEqual(
      stored.map((styleClass) => [styleClass.min, styleClass.max]),
    )
  })
})
