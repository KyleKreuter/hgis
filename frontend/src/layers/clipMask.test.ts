import { describe, expect, it } from 'vitest'
import type { LayerSummary } from '@/api/layers'
import { canBeClipMask, clipMaskLockedReason, clipMaskReplacedMessage, findOtherClipMask } from './clipMask'

function makeLayer(overrides: Partial<LayerSummary> = {}): LayerSummary {
  return {
    id: 'layer-1',
    name: 'Gebäude',
    geometryType: 'MULTIPOLYGON',
    srid: 25832,
    featureCount: 100,
    visible: true,
    zIndex: 0,
    minZoom: 0,
    maxZoom: 22,
    dataVersion: 1,
    styleVersion: 1,
    extent: null,
    clipMask: false,
    clipVersion: 0,
    ...overrides,
  }
}

describe('canBeClipMask', () => {
  it('erlaubt MULTIPOLYGON und GEOMETRY', () => {
    expect(canBeClipMask('MULTIPOLYGON')).toBe(true)
    expect(canBeClipMask('GEOMETRY')).toBe(true)
  })

  it('lehnt MULTIPOINT und MULTILINESTRING ab -- eine Maske aus Punkten oder Linien schneidet alles weg', () => {
    expect(canBeClipMask('MULTIPOINT')).toBe(false)
    expect(canBeClipMask('MULTILINESTRING')).toBe(false)
  })
})

describe('clipMaskLockedReason', () => {
  it('nennt keinen Grund für eine Geometrieart, die als Maske taugt', () => {
    expect(clipMaskLockedReason('MULTIPOLYGON')).toBeNull()
    expect(clipMaskLockedReason('GEOMETRY')).toBeNull()
  })

  it('nennt einen Grund für Punkt- und Linienlayer, statt den Eintrag wortlos zu sperren', () => {
    expect(clipMaskLockedReason('MULTIPOINT')).toBeTypeOf('string')
    expect(clipMaskLockedReason('MULTILINESTRING')).toBeTypeOf('string')
  })
})

describe('findOtherClipMask', () => {
  it('findet die aktuelle Maske eines anderen Layers', () => {
    const mask = makeLayer({ id: 'a', clipMask: true })
    const other = makeLayer({ id: 'b', clipMask: false })

    expect(findOtherClipMask([mask, other], 'b')).toBe(mask)
  })

  it('ignoriert den eigenen Layer, auch wenn er bereits die Maske ist', () => {
    const self = makeLayer({ id: 'a', clipMask: true })

    expect(findOtherClipMask([self], 'a')).toBeNull()
  })

  it('liefert null, wenn im Projekt noch keine Maske gesetzt ist', () => {
    const layers = [makeLayer({ id: 'a' }), makeLayer({ id: 'b' })]

    expect(findOtherClipMask(layers, 'a')).toBeNull()
  })
})

describe('clipMaskReplacedMessage', () => {
  it('nennt den Namen der abgelösten Maske', () => {
    expect(clipMaskReplacedMessage(makeLayer({ name: 'Straßen' }))).toBe('„Straßen" ist keine Maske mehr')
  })
})
