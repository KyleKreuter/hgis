import { describe, expect, it } from 'vitest'
import { geodesicArea, geodesicDistance, pathLength, type LngLat } from './geodesy'

describe('geodesicDistance', () => {
  it('reproduziert Vincentys eigenes Beispiel (Flinders Peak – Buninyong)', () => {
    const flinders: LngLat = [144.42486788, -37.95103342]
    const buninyong: LngLat = [143.92649552, -37.65282113]
    // Der publizierte Wert ist 54972,271 m -- Millimeter, nicht Meter, sind hier die
    // Toleranz: eine falsche Ellipsoidformel läge um Hunderte von Metern daneben.
    expect(geodesicDistance(flinders, buninyong)).toBeCloseTo(54972.271, 2)
  })

  it('trifft ein Längengrad am Äquator und ein Breitengrad am Meridian', () => {
    // Am Äquator ist ein Grad der Bogen der großen Halbachse, am Meridian ist er
    // kürzer -- genau der Unterschied, den eine naive Gradrechnung verliert.
    expect(geodesicDistance([0, 0], [1, 0])).toBeCloseTo(111319.491, 2)
    expect(geodesicDistance([0, 0], [0, 1])).toBeCloseTo(110574.389, 2)
  })

  it('ist symmetrisch und für denselben Punkt null', () => {
    const a: LngLat = [13.404954, 52.520008]
    const b: LngLat = [9.993682, 53.551086]
    expect(geodesicDistance(a, b)).toBeCloseTo(geodesicDistance(b, a), 6)
    expect(geodesicDistance(a, a)).toBe(0)
  })

  it('rechnet über die Datumsgrenze hinweg die kurze Strecke', () => {
    // Ein Grad auseinander, aber 359 Grad, wenn man die Differenz nicht faltet.
    expect(geodesicDistance([179.5, 0], [-179.5, 0])).toBeCloseTo(111319.491, 2)
  })

  it('liefert auch für nahezu antipodische Punkte einen brauchbaren Wert', () => {
    // Hier konvergiert Vincenty nicht; der Haversine-Rückfall greift. Der halbe
    // Erdumfang liegt bei rund 20.000 km, und darum muss das Ergebnis liegen.
    const distance = geodesicDistance([0, 0], [179.7, 0.2])
    expect(distance).toBeGreaterThan(19_900_000)
    expect(distance).toBeLessThan(20_100_000)
  })
})

describe('pathLength', () => {
  it('summiert die Teilstrecken', () => {
    const path: LngLat[] = [
      [0, 0],
      [1, 0],
      [1, 1],
    ]
    expect(pathLength(path)).toBeCloseTo(
      geodesicDistance([0, 0], [1, 0]) + geodesicDistance([1, 0], [1, 1]),
      6,
    )
  })

  it('ist null, solange es keine zweite Stützstelle gibt', () => {
    expect(pathLength([])).toBe(0)
    expect(pathLength([[13.4, 52.5]])).toBe(0)
  })
})

describe('geodesicArea', () => {
  it('trifft die Fläche einer Gradzelle am Äquator', () => {
    // 1°x1° am Äquator misst auf dem WGS84-Ellipsoid rund 12.308 km². Auf einer
    // Kugel kämen 12.364 km² heraus -- die 0,45 %, um die es hier geht.
    const cell = geodesicArea([
      [0, 0],
      [1, 0],
      [1, 1],
      [0, 1],
    ])
    expect(cell / 1e6).toBeCloseTo(12_308.46, 1)
  })

  it('trifft die Fläche einer Gradzelle bei 45 Grad Nord', () => {
    const cell = geodesicArea([
      [0, 45],
      [1, 45],
      [1, 46],
      [0, 46],
    ])
    expect(cell / 1e6).toBeCloseTo(8686.49, 1)
  })

  it('misst ein Quadrat von 100 m Kantenlänge', () => {
    const origin: LngLat = [13.4, 52.5]
    const deltaLat = 100 / geodesicDistance(origin, [origin[0], origin[1] + 1])
    const deltaLon = 100 / geodesicDistance(origin, [origin[0] + 1, origin[1]])
    const square: LngLat[] = [
      origin,
      [origin[0] + deltaLon, origin[1]],
      [origin[0] + deltaLon, origin[1] + deltaLat],
      [origin[0], origin[1] + deltaLat],
    ]
    // Ein Zehntel Promille Toleranz: die Kantenlängen selbst sind über die lineare
    // Näherung der Gradlänge gebildet, exakt kann das Quadrat also nicht sein.
    expect(geodesicArea(square)).toBeCloseTo(10_000, -1)
  })

  it('ignoriert Umlaufrichtung und einen doppelten Schlusspunkt', () => {
    const ring: LngLat[] = [
      [0, 0],
      [1, 0],
      [1, 1],
      [0, 1],
    ]
    const reversed = [...ring].reverse()
    const closed: LngLat[] = [...ring, ring[0]]
    expect(geodesicArea(reversed)).toBeCloseTo(geodesicArea(ring), 6)
    expect(geodesicArea(closed)).toBeCloseTo(geodesicArea(ring), 6)
  })

  /**
   * Ein Ring um einen Pol ist der eine Fall, den das Linienintegral nicht von selbst
   * beantwortet: es misst den Bereich zwischen Ring und Äquator und lässt die Kappe
   * darüber aus. Ein Kreis um den Nordpol kam damit als knapp ein Viertel des Planeten
   * heraus statt als die paar tausend Quadratkilometer, die er einschließt.
   */
  describe('um einen Pol', () => {
    /** Ein geschlossener Ring auf einem Breitenkreis, alle 10 Grad ein Stützpunkt. */
    function parallel(latitude: number, eastward = true): LngLat[] {
      const ring: LngLat[] = []
      for (let step = 0; step < 36; step += 1) {
        const longitude = (eastward ? step : 35 - step) * 10 - 180
        ring.push([longitude, latitude])
      }
      return ring
    }

    /** Die Oberfläche des WGS84-Ellipsoids: 510.065.622 km², ein publizierter Wert. */
    const EARTH_SURFACE = 510_065_622e6

    it('misst den Ring entlang des Äquators als halbe Erdoberfläche', () => {
      // Die Referenz hängt an keiner Zwischengröße dieses Moduls: ein Ring auf dem
      // Äquator teilt die Erde in zwei gleiche Hälften, und die Gesamtoberfläche ist
      // ein von außen nachschlagbarer Wert.
      expect(geodesicArea(parallel(0))).toBeCloseTo(EARTH_SURFACE / 2, -9)
    })

    it('misst die Polkappe statt des halben Planeten', () => {
      const cap = geodesicArea(parallel(89))
      // Die Kugelnäherung der Kappe nördlich von 89°: 2πR²(1-sin φ), rund 38.800 km².
      const spherical = 2 * Math.PI * 6_371_008.8 ** 2 * (1 - Math.sin((89 * Math.PI) / 180))
      expect(cap / spherical).toBeCloseTo(1, 1)
      // Und vor allem: nicht mehr die Viertelkugel, die vorher herauskam.
      expect(cap).toBeLessThan(EARTH_SURFACE / 1000)
    })

    it('misst unabhängig von der Umlaufrichtung dieselbe Kappe', () => {
      expect(geodesicArea(parallel(80, false))).toBeCloseTo(geodesicArea(parallel(80)), 0)
    })

    it('wächst mit dem Abstand vom Pol', () => {
      const near = geodesicArea(parallel(85))
      const far = geodesicArea(parallel(60))
      expect(far).toBeGreaterThan(near)
      expect(far).toBeLessThan(EARTH_SURFACE / 2)
    })

    it('lässt einen Ring an der Datumsgrenze unberührt', () => {
      // Ein Grad breit, ein Grad hoch, mitten auf der Datumsgrenze: er umschließt
      // keinen Pol, also darf die Korrektur oben hier nichts tun.
      const straddling: LngLat[] = [
        [179.5, 0],
        [-179.5, 0],
        [-179.5, 1],
        [179.5, 1],
      ]
      const shifted: LngLat[] = [
        [-0.5, 0],
        [0.5, 0],
        [0.5, 1],
        [-0.5, 1],
      ]
      expect(geodesicArea(straddling)).toBeCloseTo(geodesicArea(shifted), 6)
    })
  })

  it('misst nichts, wo nichts eingeschlossen ist', () => {
    expect(geodesicArea([])).toBe(0)
    expect(geodesicArea([[0, 0]])).toBe(0)
    expect(
      geodesicArea([
        [0, 0],
        [1, 1],
      ]),
    ).toBe(0)
    // Drei Punkte auf einer Linie schließen ebenfalls keine Fläche ein.
    expect(
      geodesicArea([
        [0, 0],
        [0, 1],
        [0, 2],
      ]),
    ).toBeCloseTo(0, 6)
  })
})
