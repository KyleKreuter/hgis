import { describe, expect, it } from 'vitest'
import type { LayerSummary } from '@/api/layers'
import {
  availableClipModes,
  canBeClipMask,
  clipMaskBadgeAriaLabel,
  clipMaskBadgeTooltip,
  clipMaskLockedReason,
  clipMaskReplacedMessage,
  clipModeLabel,
  findOtherClipMask,
} from './clipMask'

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
    clipMode: null,
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

describe('availableClipModes', () => {
  it('bietet alle drei Modi für Flächen- und gemischte Layer', () => {
    expect(availableClipModes('MULTIPOLYGON')).toEqual([null, 'inside', 'outside'])
    expect(availableClipModes('GEOMETRY')).toEqual([null, 'inside', 'outside'])
  })

  it('bietet nur "kein Zuschnitt" für Punkt- und Linienlayer', () => {
    expect(availableClipModes('MULTIPOINT')).toEqual([null])
    expect(availableClipModes('MULTILINESTRING')).toEqual([null])
  })
})

describe('clipModeLabel', () => {
  it('beschriftet jeden der drei Modi', () => {
    expect(clipModeLabel(null)).toBe('Kein Zuschnitt')
    expect(clipModeLabel('inside')).toBe('Nur innerhalb zeigen')
    expect(clipModeLabel('outside')).toBe('Nur außerhalb zeigen')
  })
})

describe('clipMaskBadgeAriaLabel', () => {
  it('unterscheidet die Richtung', () => {
    expect(clipMaskBadgeAriaLabel('inside')).not.toBe(clipMaskBadgeAriaLabel('outside'))
    expect(clipMaskBadgeAriaLabel('inside')).toMatch(/innerhalb/)
    expect(clipMaskBadgeAriaLabel('outside')).toMatch(/außerhalb/)
  })
})

describe('clipMaskBadgeTooltip', () => {
  it('unterscheidet die Richtung und nennt beide Male, dass die Maske trotz Ausblenden wirkt', () => {
    const inside = clipMaskBadgeTooltip('inside')
    const outside = clipMaskBadgeTooltip('outside')

    expect(inside).not.toBe(outside)
    expect(inside).toMatch(/innerhalb/)
    expect(outside).toMatch(/außerhalb/)
    expect(inside).toMatch(/ausgeblendet/)
    expect(outside).toMatch(/ausgeblendet/)
  })
})

describe('findOtherClipMask', () => {
  it('findet die aktuelle Maske eines anderen Layers, gleich in welchem Modus', () => {
    const insideMask = makeLayer({ id: 'a', clipMode: 'inside' })
    const other = makeLayer({ id: 'b', clipMode: null })

    expect(findOtherClipMask([insideMask, other], 'b')).toBe(insideMask)
  })

  it('findet auch eine Maske im Modus "outside"', () => {
    const outsideMask = makeLayer({ id: 'a', clipMode: 'outside' })

    expect(findOtherClipMask([outsideMask], 'b')).toBe(outsideMask)
  })

  it('ignoriert den eigenen Layer, auch wenn er bereits die Maske ist', () => {
    const self = makeLayer({ id: 'a', clipMode: 'inside' })

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
