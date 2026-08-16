import { describe, expect, it } from 'vitest'
import {
  EMPTY_VIEW_STATE,
  SELECTION_SAVE_LIMIT,
  activeLayerJumpTarget,
  layerStateOf,
  planSelectionWrite,
  queryOf,
  restoredQueryHidesData,
  selectionWithinSaveLimit,
  shouldRestoreActiveLayer,
  survivingSelection,
  withActiveLayer,
  withQuery,
  withSort,
  type ViewStateDocument,
} from './viewState'

describe('shouldRestoreActiveLayer', () => {
  it('the address wins over the saved active layer', () => {
    const document: ViewStateDocument = { ...EMPTY_VIEW_STATE, activeLayerId: 'saved-layer' }
    expect(shouldRestoreActiveLayer('url-layer', document)).toBeNull()
  })

  it('the address winning also covers an empty string layer id', () => {
    const document: ViewStateDocument = { ...EMPTY_VIEW_STATE, activeLayerId: 'saved-layer' }
    expect(shouldRestoreActiveLayer('', document)).toBeNull()
  })

  it('restores the saved layer once the address says nothing', () => {
    const document: ViewStateDocument = { ...EMPTY_VIEW_STATE, activeLayerId: 'saved-layer' }
    expect(shouldRestoreActiveLayer(undefined, document)).toBe('saved-layer')
  })

  it('there is nothing to restore when neither names a layer', () => {
    expect(shouldRestoreActiveLayer(undefined, EMPTY_VIEW_STATE)).toBeNull()
  })
})

describe('planSelectionWrite', () => {
  it('a selection over the limit is not sent', () => {
    const overLimit = Array.from({ length: SELECTION_SAVE_LIMIT + 1 }, (_, index) => index)
    const plan = planSelectionWrite(EMPTY_VIEW_STATE, 'layer-1', overLimit)
    expect(plan.overLimit).toBe(true)
    expect(plan.document).toBeNull()
  })

  it('a selection exactly at the limit is sent', () => {
    const atLimit = Array.from({ length: SELECTION_SAVE_LIMIT }, (_, index) => index)
    const plan = planSelectionWrite(EMPTY_VIEW_STATE, 'layer-1', atLimit)
    expect(plan.overLimit).toBe(false)
    expect(plan.document?.layers['layer-1']?.selection).toHaveLength(SELECTION_SAVE_LIMIT)
  })

  it('a selection well under the limit is sent unchanged', () => {
    const plan = planSelectionWrite(EMPTY_VIEW_STATE, 'layer-1', [12, 47, 199])
    expect(plan.overLimit).toBe(false)
    expect(plan.document?.layers['layer-1']?.selection).toEqual([12, 47, 199])
  })
})

describe('selectionWithinSaveLimit', () => {
  it('agrees with planSelectionWrite at the boundary', () => {
    expect(selectionWithinSaveLimit(SELECTION_SAVE_LIMIT)).toBe(true)
    expect(selectionWithinSaveLimit(SELECTION_SAVE_LIMIT + 1)).toBe(false)
  })
})

describe('survivingSelection', () => {
  it('drops fids that no longer exist, keeps the rest', () => {
    const existing = new Set([12, 199])
    expect(survivingSelection([12, 47, 199], existing)).toEqual([12, 199])
  })

  it('an entirely vanished selection comes back empty', () => {
    expect(survivingSelection([12, 47], new Set([199]))).toEqual([])
  })
})

describe('restoredQueryHidesData', () => {
  it('flags a restriction that matched fewer rows than the layer has', () => {
    expect(restoredQueryHidesData(342, 5108)).toBe(true)
  })

  it('does not flag a restriction that matched everything', () => {
    expect(restoredQueryHidesData(5108, 5108)).toBe(false)
  })
})

describe('queryOf', () => {
  it('an empty text is "no restriction", not an object holding one', () => {
    expect(queryOf('search', '')).toBeNull()
  })

  it('a non-empty text keeps its mode', () => {
    expect(queryOf('filter', "name = 'Schmidt'")).toEqual({ mode: 'filter', text: "name = 'Schmidt'" })
  })
})

describe('document round trip', () => {
  it('rebuilds what was written back out of the document', () => {
    let document = EMPTY_VIEW_STATE
    document = withActiveLayer(document, 'layer-1')
    document = withSort(document, 'layer-1', { field: 'baujahr', desc: true })
    document = withQuery(document, 'layer-1', queryOf('search', 'Schmidt'))
    const plan = planSelectionWrite(document, 'layer-1', [12, 47, 199])
    document = plan.document ?? document

    expect(document.activeLayerId).toBe('layer-1')
    expect(layerStateOf(document, 'layer-1')).toEqual({
      sort: { field: 'baujahr', desc: true },
      query: { mode: 'search', text: 'Schmidt' },
      selection: [12, 47, 199],
    })
    // A layer nothing was ever written for still reads back the all-empty default.
    expect(layerStateOf(document, 'layer-2')).toEqual({ sort: null, query: null, selection: [] })
  })

  it('writing a layer never disturbs another layer already in the document', () => {
    let document = EMPTY_VIEW_STATE
    document = withSort(document, 'layer-1', { field: 'baujahr', desc: false })
    document = withQuery(document, 'layer-2', queryOf('filter', "name = 'Schmidt'"))

    expect(layerStateOf(document, 'layer-1').sort).toEqual({ field: 'baujahr', desc: false })
    expect(layerStateOf(document, 'layer-2').query).toEqual({ mode: 'filter', text: "name = 'Schmidt'" })
  })

  it('a long query text is clipped to the server limit', () => {
    const tooLong = 'x'.repeat(2500)
    const document = withQuery(EMPTY_VIEW_STATE, 'layer-1', queryOf('search', tooLong))
    expect(layerStateOf(document, 'layer-1').query?.text).toHaveLength(2000)
  })
})

/**
 * Wohin die Ansicht springt, wenn jemand anders den aktiven Layer umstellt -- und die
 * vier Faelle, in denen sie es nicht tut.
 */
describe('activeLayerJumpTarget', () => {
  it('springt auf den Layer, den jemand anders geoeffnet hat', () => {
    expect(activeLayerJumpTarget({ known: 'a', stored: 'b', open: 'a' })).toBe('b')
  })

  it('springt auch, wenn dieses Fenster gerade gar keinen Layer offen hat', () => {
    expect(activeLayerJumpTarget({ known: 'a', stored: 'b', open: null })).toBe('b')
  })

  it('springt nicht ohne Vergleichswert', () => {
    // Vor der ersten Lesung saehe jeder Wert neu aus, und das erste Ereignis wuerde eine
    // Ansicht bewegen, die nie darum gebeten hat.
    expect(activeLayerJumpTarget({ known: undefined, stored: 'b', open: 'a' })).toBeNull()
  })

  it('springt nicht, wenn der aktive Layer unveraendert ist', () => {
    // Der haeufigste Fall: ein Ereignis ueber eine Auswahl traegt den unveraenderten
    // aktiven Layer mit. Ohne diese Zeile risse es den Nutzer von seiner Adresse weg.
    expect(activeLayerJumpTarget({ known: 'b', stored: 'b', open: 'a' })).toBeNull()
  })

  it('springt nicht auf "kein Layer" -- das ist kein Ziel', () => {
    expect(activeLayerJumpTarget({ known: 'a', stored: null, open: 'a' })).toBeNull()
  })

  it('springt nicht dorthin, wo dieses Fenster schon ist', () => {
    expect(activeLayerJumpTarget({ known: 'a', stored: 'b', open: 'b' })).toBeNull()
  })

  it('springt, nachdem der gespeicherte Layer erst auf niemanden und dann woandershin zeigte', () => {
    // known folgt dem gespeicherten Wert auch dann, wenn nicht gesprungen wurde.
    expect(activeLayerJumpTarget({ known: null, stored: 'b', open: 'a' })).toBe('b')
  })
})
