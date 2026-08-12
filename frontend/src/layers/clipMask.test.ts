import { describe, expect, it } from 'vitest'
import {
  availableClipModes,
  canBeClipMask,
  clipMaskBadgeAriaLabel,
  clipMaskBadgeTooltip,
  clipMaskLockedReason,
  clipModeLabel,
} from './clipMask'

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
  it('bietet alle vier Modi für Flächen- und gemischte Layer', () => {
    expect(availableClipModes('MULTIPOLYGON')).toEqual([
      null,
      'insideWhole',
      'insideClipped',
      'outsideWhole',
      'outsideClipped',
    ])
    expect(availableClipModes('GEOMETRY')).toEqual([
      null,
      'insideWhole',
      'insideClipped',
      'outsideWhole',
      'outsideClipped',
    ])
  })

  it('bietet nur "kein Zuschnitt" für Punkt- und Linienlayer', () => {
    expect(availableClipModes('MULTIPOINT')).toEqual([null])
    expect(availableClipModes('MULTILINESTRING')).toEqual([null])
  })
})

describe('clipModeLabel', () => {
  it('beschriftet jeden der vier Modi und den leeren Zustand', () => {
    expect(clipModeLabel(null)).toBe('Kein Zuschnitt')
    expect(clipModeLabel('insideWhole')).toBe('Nur innerhalb')
    expect(clipModeLabel('insideClipped')).toBe('Nur innerhalb + geschnitten')
    expect(clipModeLabel('outsideWhole')).toBe('Nur außerhalb')
    expect(clipModeLabel('outsideClipped')).toBe('Nur außerhalb + geschnitten')
  })
})

describe('clipMaskBadgeAriaLabel', () => {
  it('unterscheidet alle vier Modi', () => {
    const labels = [
      clipMaskBadgeAriaLabel('insideWhole'),
      clipMaskBadgeAriaLabel('insideClipped'),
      clipMaskBadgeAriaLabel('outsideWhole'),
      clipMaskBadgeAriaLabel('outsideClipped'),
    ]

    expect(new Set(labels).size).toBe(labels.length)
    expect(clipMaskBadgeAriaLabel('insideWhole')).toMatch(/innerhalb/)
    expect(clipMaskBadgeAriaLabel('insideClipped')).toMatch(/innerhalb/)
    expect(clipMaskBadgeAriaLabel('outsideWhole')).toMatch(/außerhalb/)
    expect(clipMaskBadgeAriaLabel('outsideClipped')).toMatch(/außerhalb/)
  })
})

describe('clipMaskBadgeTooltip', () => {
  it('unterscheidet alle vier Modi und nennt jedes Mal, dass die Maske trotz Ausblenden wirkt', () => {
    const insideWhole = clipMaskBadgeTooltip('insideWhole')
    const insideClipped = clipMaskBadgeTooltip('insideClipped')
    const outsideWhole = clipMaskBadgeTooltip('outsideWhole')
    const outsideClipped = clipMaskBadgeTooltip('outsideClipped')
    const all = [insideWhole, insideClipped, outsideWhole, outsideClipped]

    expect(new Set(all).size).toBe(all.length)
    for (const tooltip of all) {
      expect(tooltip).toMatch(/ausgeblendet/)
    }
    expect(insideWhole).toMatch(/innerhalb/)
    expect(insideClipped).toMatch(/innerhalb/)
    expect(outsideWhole).toMatch(/außerhalb/)
    expect(outsideClipped).toMatch(/außerhalb/)
  })

  it('nennt die Grenzregel nur bei den beiden "Whole"-Modi', () => {
    expect(clipMaskBadgeTooltip('insideWhole')).toMatch(/Grenze zählt als innerhalb/)
    expect(clipMaskBadgeTooltip('outsideWhole')).toMatch(/Grenze zählt als innerhalb/)
    expect(clipMaskBadgeTooltip('insideClipped')).not.toMatch(/Grenze/)
    expect(clipMaskBadgeTooltip('outsideClipped')).not.toMatch(/Grenze/)
  })
})
