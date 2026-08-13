import { describe, expect, it } from 'vitest'
import { ApiError } from '@/api/client'
import {
  geometryKindOf,
  mergeBlockReason,
  mergeObjection,
  mergeRowVersions,
  splitBlockReason,
  splitObjection,
  structureErrorMessage,
  structureLockReason,
  structureToolsFor,
} from './structureTools'

describe('structureToolsFor', () => {
  it('bietet auf einem Punktlayer kein Werkzeug an', () => {
    // Not "disabled": a point has no inside to cut and no area to unite, so the tools
    // must not appear at all -- see CONTRACT.md 12.
    expect(structureToolsFor('MULTIPOINT')).toEqual([])
  })

  it('bietet beide Werkzeuge auf Linien-, Flächen- und gemischten Layern an', () => {
    expect(structureToolsFor('MULTILINESTRING')).toEqual(['split', 'merge'])
    expect(structureToolsFor('MULTIPOLYGON')).toEqual(['split', 'merge'])
    // GEOMETRY says nothing about the individual row, so the column may not decide here.
    // The per-feature check is `splitObjection`/`mergeObjection`.
    expect(structureToolsFor('GEOMETRY')).toEqual(['split', 'merge'])
  })
})

describe('structureLockReason', () => {
  it('sperrt beide Werkzeuge, solange Änderungen offen sind', () => {
    // The rule CONTRACT.md 12 states outright: both write immediately, so a local buffer
    // would be stale the moment they did.
    const reason = structureLockReason(3, false)

    expect(reason).toContain('Speichern oder verwerfen Sie zuerst Ihre Änderungen')
  })

  it('sperrt beide Werkzeuge im Zeichenmodus, auch bei leerem Puffer', () => {
    // Nothing is unsaved, but the drawing surface holds its own copy of every loaded
    // feature -- a split behind its back leaves it editing a row that no longer exists.
    expect(structureLockReason(0, true)).toContain('Beenden Sie zuerst den Zeichenmodus')
  })

  it('nennt die offenen Änderungen zuerst, wenn beides zutrifft', () => {
    // The more informative of the two: it names work that would be lost, not a mode.
    expect(structureLockReason(2, true)).toContain('Speichern oder verwerfen')
  })

  it('gibt ohne Sperrgrund null zurück', () => {
    expect(structureLockReason(0, false)).toBeNull()
  })
})

describe('splitBlockReason', () => {
  it('reicht den Sperrgrund unverändert durch', () => {
    // The lock outranks the selection: it says the tool may not run at all, and
    // "Wählen Sie ein Objekt aus" would send the user off to fix the wrong thing.
    expect(splitBlockReason(1, 'gesperrt')).toBe('gesperrt')
  })

  it('verlangt eine Auswahl', () => {
    expect(splitBlockReason(0, null)).toBe('Wählen Sie zuerst ein Objekt aus.')
  })

  it('verlangt genau ein Objekt', () => {
    expect(splitBlockReason(3, null)).toContain('genau ein Objekt')
  })

  it('lässt genau ein ausgewähltes Objekt zu', () => {
    expect(splitBlockReason(1, null)).toBeNull()
  })
})

describe('mergeBlockReason', () => {
  it('reicht den Sperrgrund unverändert durch', () => {
    expect(mergeBlockReason(2, 'gesperrt')).toBe('gesperrt')
  })

  it('verlangt mindestens zwei Objekte', () => {
    expect(mergeBlockReason(1, null)).toBe('Wählen Sie mindestens zwei Objekte aus.')
  })

  it('lehnt mehr als hundert Objekte ab', () => {
    // The server's own upper limit (CONTRACT.md 12.2). Refused before the request so the
    // answer names the count rather than arriving as a bare 400.
    expect(mergeBlockReason(101, null)).toContain('höchstens 100 Objekte')
  })

  it('lässt zwei bis hundert Objekte zu', () => {
    expect(mergeBlockReason(2, null)).toBeNull()
    expect(mergeBlockReason(100, null)).toBeNull()
  })
})

describe('geometryKindOf', () => {
  it('fasst ein- und mehrteilige Geometrien derselben Art zusammen', () => {
    expect(geometryKindOf('Point')).toBe('point')
    expect(geometryKindOf('MultiPoint')).toBe('point')
    expect(geometryKindOf('LineString')).toBe('line')
    expect(geometryKindOf('MultiLineString')).toBe('line')
    expect(geometryKindOf('Polygon')).toBe('area')
    expect(geometryKindOf('MultiPolygon')).toBe('area')
  })

  it('kennt keine andere Art', () => {
    expect(geometryKindOf('GeometryCollection')).toBeNull()
    expect(geometryKindOf(undefined)).toBeNull()
  })
})

describe('splitObjection', () => {
  it('lehnt einen Punkt mit dem Wortlaut des Servers ab', () => {
    // Same sentence the server sends, so the answer reads the same whichever side
    // caught it (CONTRACT.md 12.1).
    expect(splitObjection('Point')).toBe('Punkte lassen sich nicht teilen.')
    expect(splitObjection('MultiPoint')).toBe('Punkte lassen sich nicht teilen.')
  })

  it('lässt Linien und Flächen durch', () => {
    expect(splitObjection('LineString')).toBeNull()
    expect(splitObjection('MultiPolygon')).toBeNull()
  })
})

describe('mergeObjection', () => {
  it('lehnt jeden Punkt in der Auswahl ab', () => {
    expect(mergeObjection(['Polygon', 'Point'])).toBe('Punkte lassen sich nicht zusammenführen.')
  })

  it('nennt bei lauter Punkten die Punkte, nicht die gemischte Art', () => {
    // Reporting mixed kinds here would send the user looking for an odd one out that is
    // not the problem.
    expect(mergeObjection(['Point', 'MultiPoint'])).toBe('Punkte lassen sich nicht zusammenführen.')
  })

  it('lehnt gemischte Geometriearten ab', () => {
    expect(mergeObjection(['Polygon', 'LineString'])).toBe(
      'Nur Objekte derselben Geometrieart lassen sich zusammenführen.',
    )
  })

  it('lässt gleiche Arten durch, ein- und mehrteilig gemischt', () => {
    expect(mergeObjection(['Polygon', 'MultiPolygon'])).toBeNull()
    expect(mergeObjection([])).toBeNull()
  })
})

describe('mergeRowVersions', () => {
  it('baut die Zuordnung mit Zeichenketten als Schlüssel', () => {
    // JSON has no numeric keys. Numeric ones would work by accident through
    // JSON.stringify and stop working the moment anything read the map back.
    const versions = mergeRowVersions([
      { fid: 42, rowVersion: '8241' },
      { fid: 43, rowVersion: '8242' },
    ])

    expect(versions).toEqual({ '42': '8241', '43': '8242' })
    expect(Object.keys(versions)).toEqual(['42', '43'])
  })
})

describe('structureErrorMessage', () => {
  it('erklärt einen 409 als fremde Änderung statt als Versionskonflikt', () => {
    // The server states it as a row version mismatch, which is true and says nothing to
    // whoever is looking at the map.
    const error = new ApiError(409, {
      detail: 'Eine andere Stelle hat Objekt 7 zwischenzeitlich geändert',
    })

    expect(structureErrorMessage(error, 'split')).toContain(
      'Ein anderer Benutzer hat das Objekt inzwischen geändert',
    )
    expect(structureErrorMessage(error, 'split')).toContain('Laden Sie die Ansicht neu')
  })

  it('sagt beim Zusammenführen dazu, dass nichts geschrieben wurde', () => {
    // The merge is one transaction: one mismatch rolls back all of it (CONTRACT.md 12.2).
    const error = new ApiError(409, { detail: 'Konflikt' })

    expect(structureErrorMessage(error, 'merge')).toContain('nichts zusammengeführt')
  })

  it('behält den Wortlaut des Servers bei jedem anderen Fehler', () => {
    // "Die Linie teilt das Objekt nicht" names the cause far better than any generic
    // sentence could -- swallowing it would leave the user without a next step.
    const error = new ApiError(400, { detail: 'Die Linie teilt das Objekt nicht.' })

    expect(structureErrorMessage(error, 'split')).toBe('Die Linie teilt das Objekt nicht.')
  })

  it('fällt bei einem Fehler ohne Antwort auf einen eigenen Satz zurück', () => {
    expect(structureErrorMessage(new TypeError('offline'), 'split')).toBe(
      'Das Programm konnte das Objekt nicht teilen.',
    )
    expect(structureErrorMessage(new TypeError('offline'), 'merge')).toBe(
      'Das Programm konnte die Objekte nicht zusammenführen.',
    )
  })
})
