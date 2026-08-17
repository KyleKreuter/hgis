import { describe, expect, it } from 'vitest'
import { CIRCLE_PAINT, FILL_PAINT, LINE_PAINT } from '@/map/layerSpecs'
import { COLOR_RAMP_IDS, COLOR_RAMPS, DEFAULT_FILL, DEFAULT_LINE, DEFAULT_MARKER, isHexColor, sampleRamp } from './defaults'

describe('Standardsymbole', () => {
  /**
   * The two are written out separately -- literal paint on one side, a symbol on the
   * other -- so this is the only thing keeping them equal. Without it the symbology
   * panel would open on a style that renders subtly differently from the layer as it
   * was a second ago.
   */
  it('geben Wert für Wert die unstilisierte Darstellung wieder', () => {
    expect(DEFAULT_MARKER.size).toBe(CIRCLE_PAINT['circle-radius'])
    expect(DEFAULT_MARKER.fillColor).toBe(CIRCLE_PAINT['circle-color'])
    expect(DEFAULT_MARKER.strokeWidth).toBe(CIRCLE_PAINT['circle-stroke-width'])
    expect(DEFAULT_MARKER.strokeColor).toBe(CIRCLE_PAINT['circle-stroke-color'])

    expect(DEFAULT_LINE.color).toBe(LINE_PAINT['line-color'])
    expect(DEFAULT_LINE.width).toBe(LINE_PAINT['line-width'])

    expect(DEFAULT_FILL.fillColor).toBe(FILL_PAINT['fill-color'])
    expect(DEFAULT_FILL.fillOpacity).toBe(FILL_PAINT['fill-opacity'])
    expect(DEFAULT_FILL.outlineColor).toBe(FILL_PAINT['fill-outline-color'])
  })
})

describe('Farbrampen', () => {
  it('liefern die Endpunkte der Rampe als erste und letzte Klasse', () => {
    const ramp = COLOR_RAMPS[0]
    const colors = sampleRamp(ramp, 5)

    expect(colors).toHaveLength(5)
    expect(colors[0]).toBe(ramp.stops[0])
    expect(colors[4]).toBe(ramp.stops[ramp.stops.length - 1])
  })

  it('erzeugen ausschliesslich gültige #rrggbb-Farben', () => {
    for (const ramp of COLOR_RAMPS) {
      // 5 und 7 sind die Klassenzahlen, in denen eine abgestufte Darstellung eine Rampe
      // tatsächlich zeigt (`GraduatedEditor`) -- `inferno`/`viridis` mit ihren fünf statt
      // drei Stützfarben laufen hier automatisch mit, weil die Schleife über alle
      // Katalogeinträge geht.
      for (const count of [1, 2, 5, 7, 12]) {
        for (const color of sampleRamp(ramp, count)) {
          expect(isHexColor(color)).toBe(true)
        }
      }
    }
  })
})

describe('COLOR_RAMP_IDS', () => {
  /**
   * Der verbindliche Katalog (team review, package 3): sieben Namen, `inferno`/`viridis`
   * eingeschlossen, weil `python/README.md` und die Docstrings (`style.py`, `layer.py`) sie
   * als kanonisches Beispiel zeigen, das Backend `COLOR_RAMPS` aber nie geführt hat --
   * einzig die Länge geprüft (Vertrag, package 1-3). Diese Liste ist, was dieses Paket dem
   * Backend-Entwickler zum Abgleich gibt: kommt hier ein Name dazu, ohne dass die
   * serverseitige Prüfung ihn kennt, lehnt der Server einen gültigen Namen mit 400 ab --
   * kommt einer nur serverseitig dazu, akzeptiert der Server einen Namen, den kein Client
   * je anbieten kann. Ändert sich der Katalog, muss dieser Test bewusst mitgeändert
   * werden, nicht nebenbei durchrutschen.
   */
  it('bleibt exakt der zwischen Client und Server abgestimmte Katalog', () => {
    expect(COLOR_RAMP_IDS).toEqual(['blues', 'reds', 'greens', 'greys', 'diverging', 'inferno', 'viridis'])
  })

  it('ist wortgleich mit den ids in COLOR_RAMPS, in derselben Reihenfolge', () => {
    expect(COLOR_RAMP_IDS).toEqual(COLOR_RAMPS.map((ramp) => ramp.id))
  })
})
