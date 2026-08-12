import { describe, expect, it } from 'vitest'
import { GEOMETRY_FILTERS } from '@/map/layerSpecs'
import { baseTileFilter, editableTileLayerIds, tileFilterWhileEditing } from './tileFilter'

const LAYER = 'gebaeude'
const RENDER = 'hgis-layer-gebaeude-render'
const LABEL = 'hgis-layer-gebaeude-label'
const POLYGON = 'hgis-layer-gebaeude-polygon'
const LINE = 'hgis-layer-gebaeude-line'
const POINT = 'hgis-layer-gebaeude-point'
const SELECTION = 'hgis-layer-gebaeude-selected'
const SELECTION_OUTLINE = 'hgis-layer-gebaeude-selected-outline'
const OTHER_LAYER = 'hgis-layer-strassen-render'

describe('editableTileLayerIds', () => {
  it('nimmt die Kachellayer des Layers', () => {
    expect(editableTileLayerIds([RENDER, LABEL, OTHER_LAYER, 'basemap:osm'], LAYER)).toEqual([
      RENDER,
      LABEL,
    ])
  })

  it('lässt die Auswahl-Hervorhebung aus, obwohl sie unter demselben Präfix liegt', () => {
    // Genau der belegte Fehler: die Hervorhebung heißt `hgis-layer-<id>-selected` und
    // fiel unter dasselbe Präfix. Ihr `['in', ['id'], …]`-Filter wurde überschrieben, die
    // Hervorhebung deckte danach alle Objekte des Layers ein.
    expect(
      editableTileLayerIds([RENDER, SELECTION, SELECTION_OUTLINE], LAYER),
    ).toEqual([RENDER])
  })

  it('nimmt alle drei Unterlayer eines GEOMETRY-Layers', () => {
    expect(editableTileLayerIds([POLYGON, LINE, POINT, SELECTION], LAYER)).toEqual([
      POLYGON,
      LINE,
      POINT,
    ])
  })

  it('liefert für einen Stil ohne diesen Layer nichts', () => {
    expect(editableTileLayerIds([OTHER_LAYER, 'basemap:osm'], LAYER)).toEqual([])
  })
})

describe('baseTileFilter', () => {
  it('gibt jedem Unterlayer eines GEOMETRY-Layers seinen Geometrietyp-Filter zurück', () => {
    expect(baseTileFilter(POLYGON)).toEqual(GEOMETRY_FILTERS.polygon)
    expect(baseTileFilter(LINE)).toEqual(GEOMETRY_FILTERS.line)
    expect(baseTileFilter(POINT)).toEqual(GEOMETRY_FILTERS.point)
  })

  it('lässt einen Layer ohne eigenen Filter ungefiltert', () => {
    expect(baseTileFilter(RENDER)).toBeNull()
    expect(baseTileFilter(LABEL)).toBeNull()
  })
})

describe('tileFilterWhileEditing', () => {
  it('versteckt die bearbeiteten Objekte', () => {
    expect(tileFilterWhileEditing(RENDER, [1, 7])).toEqual([
      '!',
      ['in', ['id'], ['literal', [1, 7]]],
    ])
  })

  it('behält den Geometrietyp-Filter eines Unterlayers bei', () => {
    // Ohne das zeichnete der Flächen-Unterlayer auch Punkte und Linien, sobald ein
    // einziges Objekt bearbeitet wurde.
    expect(tileFilterWhileEditing(POLYGON, [1])).toEqual([
      'all',
      GEOMETRY_FILTERS.polygon,
      ['!', ['in', ['id'], ['literal', [1]]]],
    ])
  })

  it('stellt ohne versteckte Objekte genau den Ausgangsfilter her', () => {
    expect(tileFilterWhileEditing(POLYGON, [])).toEqual(GEOMETRY_FILTERS.polygon)
    expect(tileFilterWhileEditing(RENDER, [])).toBeNull()
  })
})
