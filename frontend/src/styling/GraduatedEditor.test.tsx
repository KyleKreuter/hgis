import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { LayerField } from '@/api/layers'
import { renderWithQueryClient, stubFetch } from '@/test/render'
import { buildClasses } from './classification'
import { defaultSymbolFor } from './defaults'
import { GraduatedEditor } from './GraduatedEditor'
import { DEFAULT_RAMP } from './palettes'
import type { Renderer } from './types'

function makeFields(): LayerField[] {
  return [{ id: 'f-hoehe', sourceName: 'Höhe', columnName: 'hoehe', dataType: 'integer' }]
}

/**
 * Team review, package 3 addendum, the `graduated`/`ramp` counterpart to
 * `CategorizedEditor.test.tsx`'s two suites. `GraduatedEditor` has no dedicated
 * "recolor" button -- `selectRamp` (the palette picker itself) is the closest
 * equivalent, and it always receives an already-resolved value straight from
 * `PaletteSelect`. But `selectField`, `selectMethod` and `selectClassCount` all replay
 * the current `ramp` local state through the same `request`, and that state can hold a
 * name `initialGraduatedControls` never validated -- it only defaults a *missing*
 * `renderer.ramp`, not one naming a ramp since renamed or removed. Exercised here via
 * `selectMethod` (the "Methode" control); the fix lives in `request` itself, so the same
 * `resolvePaletteId` call covers `selectField` and `selectClassCount` too.
 */
describe('GraduatedEditor „Methode ändern“ (team review, package 3 addendum)', () => {
  it('schreibt bei einem unbekannten, gespeicherten Rampen-Namen den tatsächlich benutzten Namen zurück', async () => {
    const stored = buildClasses([0, 40, 320, 900], 'MULTIPOLYGON', DEFAULT_RAMP)
    const renderer: Extract<Renderer, { type: 'graduated' }> = {
      type: 'graduated',
      field: 'hoehe',
      classes: stored,
      fallbackSymbol: defaultSymbolFor('MULTIPOLYGON'),
      method: 'quantile',
      classCount: stored.length,
      // Ein Name, den kein `COLOR_RAMPS`-Eintrag mehr trägt -- z. B. umbenannt oder
      // entfernt, seit dieser Stil gespeichert wurde.
      ramp: 'brewer-set2',
    }
    const onChange = vi.fn()
    stubFetch([
      {
        match: '/classify',
        body: { field: 'hoehe', method: 'equalInterval', breaks: [0, 300, 600, 900], min: 0, max: 900, nullCount: 0 },
      },
    ])

    renderWithQueryClient(
      <GraduatedEditor layerId="layer-1" geometryType="MULTIPOLYGON" renderer={renderer} fields={makeFields()} onChange={onChange} />,
    )

    // Comboboxen in Reihenfolge: Feld, Methode, Rampe (`Row`s eigenes Label ist nicht
    // per `htmlFor`/`aria-labelledby` verknüpft, daher über die Reihenfolge).
    await userEvent.click(screen.getAllByRole('combobox')[1])
    await userEvent.click(await screen.findByRole('option', { name: 'Gleiche Intervalle' }))

    await waitFor(() => {
      const last = onChange.mock.calls.at(-1)?.[0] as Extract<Renderer, { type: 'graduated' }>
      expect(last.ramp).toBe(DEFAULT_RAMP)
      expect(last.ramp).not.toBe('brewer-set2')
    })
  })

  it('lässt einen gültigen Rampen-Namen unverändert', async () => {
    const stored = buildClasses([0, 40, 320, 900], 'MULTIPOLYGON', 'reds')
    const renderer: Extract<Renderer, { type: 'graduated' }> = {
      type: 'graduated',
      field: 'hoehe',
      classes: stored,
      fallbackSymbol: defaultSymbolFor('MULTIPOLYGON'),
      method: 'quantile',
      classCount: stored.length,
      ramp: 'reds',
    }
    const onChange = vi.fn()
    stubFetch([
      {
        match: '/classify',
        body: { field: 'hoehe', method: 'equalInterval', breaks: [0, 300, 600, 900], min: 0, max: 900, nullCount: 0 },
      },
    ])

    renderWithQueryClient(
      <GraduatedEditor layerId="layer-1" geometryType="MULTIPOLYGON" renderer={renderer} fields={makeFields()} onChange={onChange} />,
    )

    await userEvent.click(screen.getAllByRole('combobox')[1])
    await userEvent.click(await screen.findByRole('option', { name: 'Gleiche Intervalle' }))

    await waitFor(() => {
      const last = onChange.mock.calls.at(-1)?.[0] as Extract<Renderer, { type: 'graduated' }>
      expect(last.ramp).toBe('reds')
    })
  })
})

/**
 * Klassengrenzen (`renderer.classes`) leben im gespeicherten Stil, nicht nur im
 * Zwischenspeicher -- eine Neuberechnung schreibt sie fest hinein, und danach malt die
 * Karte genau diese gespeicherten Grenzen weiter, auch wenn sich die Daten seitdem
 * bewegt haben. `stepExpression` klemmt jeden Wert außerhalb an die Randfarbe -- ein
 * neues Objekt über dem alten Maximum sieht farblich aus wie eines aus der obersten
 * Klasse, ohne dass irgendetwas das anzeigt.
 *
 * Diese Suite prüft die Warnung, die genau das sichtbar macht: einen Vergleich der
 * gespeicherten Klassengrenzen gegen die *aktuelle* Feldspanne (`/classify` mit
 * `quantile`/2 Klassen, derselbe Aufruf, den `HeatmapEditor`s Legende für ihre eigenen
 * festen Grenzen schon macht).
 */
describe('Klassengrenzen gegen die aktuelle Datenlage (Warnung bei Überschreitung)', () => {
  function graduatedRenderer(): Extract<Renderer, { type: 'graduated' }> {
    return {
      type: 'graduated',
      field: 'hoehe',
      // Grenzen 0..900, drei Klassen -- klassisches Ergebnis einer früheren
      // Berechnung, unabhängig vom Live-Check hier gesetzt.
      classes: buildClasses([0, 300, 600, 900], 'MULTIPOLYGON', DEFAULT_RAMP),
      fallbackSymbol: defaultSymbolFor('MULTIPOLYGON'),
      method: 'quantile',
      classCount: 3,
      ramp: DEFAULT_RAMP,
    }
  }

  it('zeigt einen unauffälligen Zustand, wenn die aktuelle Spanne genau in den gespeicherten Grenzen liegt', async () => {
    stubFetch([{ match: 'classes=2', body: { field: 'hoehe', method: 'quantile', breaks: [0, 900], min: 0, max: 900, nullCount: 0 } }])

    renderWithQueryClient(
      <GraduatedEditor layerId="layer-1" geometryType="MULTIPOLYGON" renderer={graduatedRenderer()} fields={makeFields()} onChange={vi.fn()} />,
    )

    expect(await screen.findByLabelText('Untere Klassengrenze. Werte darunter zeigen dieselbe Farbe wie die unterste Klasse.')).toBeInTheDocument()
    expect(await screen.findByLabelText('Obere Klassengrenze. Werte darüber zeigen dieselbe Farbe wie die oberste Klasse.')).toBeInTheDocument()
  })

  /**
   * Die Daten liegen enger als die gespeicherten Grenzen -- "füllen sie nicht mehr aus"
   * (Vertrag). Jeder Wert landet weiterhin in seiner eigenen, richtig eingefärbten
   * Klasse; nichts klemmt an einer Randfarbe. Bewusst keine Warnung: die andere der
   * beiden Arten, wie Grenzen veralten können, ist keine Anzeigefalschheit.
   */
  it('warnt nicht, wenn die aktuelle Spanne die gespeicherten Grenzen nur nicht mehr ausfüllt', async () => {
    stubFetch([{ match: 'classes=2', body: { field: 'hoehe', method: 'quantile', breaks: [100, 800], min: 100, max: 800, nullCount: 0 } }])

    renderWithQueryClient(
      <GraduatedEditor layerId="layer-1" geometryType="MULTIPOLYGON" renderer={graduatedRenderer()} fields={makeFields()} onChange={vi.fn()} />,
    )

    expect(await screen.findByLabelText('Untere Klassengrenze. Werte darunter zeigen dieselbe Farbe wie die unterste Klasse.')).toBeInTheDocument()
    expect(await screen.findByLabelText('Obere Klassengrenze. Werte darüber zeigen dieselbe Farbe wie die oberste Klasse.')).toBeInTheDocument()
  })

  it('warnt, wenn die aktuelle Spanne über die gespeicherte Obergrenze hinausreicht', async () => {
    stubFetch([{ match: 'classes=2', body: { field: 'hoehe', method: 'quantile', breaks: [0, 1200], min: 0, max: 1200, nullCount: 0 } }])

    renderWithQueryClient(
      <GraduatedEditor layerId="layer-1" geometryType="MULTIPOLYGON" renderer={graduatedRenderer()} fields={makeFields()} onChange={vi.fn()} />,
    )

    expect(
      await screen.findByLabelText(
        'Obere Klassengrenze. Der aktuelle Datenbestand reicht bereits darüber hinaus -- die Klassifizierung passt nicht mehr zu den Daten. Werte darüber zeigen dieselbe Farbe wie die oberste Klasse.',
      ),
    ).toBeInTheDocument()
    // Die Untergrenze ist von dieser Überschreitung unberührt.
    expect(screen.getByLabelText('Untere Klassengrenze. Werte darunter zeigen dieselbe Farbe wie die unterste Klasse.')).toBeInTheDocument()
  })

  it('warnt, wenn die aktuelle Spanne unter die gespeicherte Untergrenze reicht', async () => {
    stubFetch([{ match: 'classes=2', body: { field: 'hoehe', method: 'quantile', breaks: [-200, 900], min: -200, max: 900, nullCount: 0 } }])

    renderWithQueryClient(
      <GraduatedEditor layerId="layer-1" geometryType="MULTIPOLYGON" renderer={graduatedRenderer()} fields={makeFields()} onChange={vi.fn()} />,
    )

    expect(
      await screen.findByLabelText(
        'Untere Klassengrenze. Der aktuelle Datenbestand reicht bereits darunter -- die Klassifizierung passt nicht mehr zu den Daten. Werte darunter zeigen dieselbe Farbe wie die unterste Klasse.',
      ),
    ).toBeInTheDocument()
  })

  it('zeigt „nicht geprüft“, wenn die aktuelle Spanne nicht geladen werden konnte', async () => {
    stubFetch([{ match: 'classes=2', body: {}, status: 500 }])

    renderWithQueryClient(
      <GraduatedEditor layerId="layer-1" geometryType="MULTIPOLYGON" renderer={graduatedRenderer()} fields={makeFields()} onChange={vi.fn()} />,
    )

    expect(
      await screen.findByLabelText(
        'Untere Klassengrenze. Konnte nicht geprüft werden, ob der aktuelle Datenbestand schon darunter reicht -- die Wertespanne ließ sich gerade nicht laden.',
      ),
    ).toBeInTheDocument()
    expect(
      screen.getByLabelText(
        'Obere Klassengrenze. Konnte nicht geprüft werden, ob der aktuelle Datenbestand schon darüber hinausreicht -- die Wertespanne ließ sich gerade nicht laden.',
      ),
    ).toBeInTheDocument()
  })

  it('zeigt keine Grenzen-Anzeige, solange noch keine Klassen berechnet wurden', () => {
    const renderer: Extract<Renderer, { type: 'graduated' }> = {
      type: 'graduated',
      field: '',
      classes: [],
      fallbackSymbol: defaultSymbolFor('MULTIPOLYGON'),
    }
    // Kein Stub-Aufruf erwartet: ohne Klassen bleibt der Live-Check ungestartet
    // (`enabled: classes.length > 0`); ein unbekannter Request würde
    // `stubFetch` selbst laut fehlschlagen lassen.
    stubFetch([])

    renderWithQueryClient(
      <GraduatedEditor layerId="layer-1" geometryType="MULTIPOLYGON" renderer={renderer} fields={makeFields()} onChange={vi.fn()} />,
    )

    expect(screen.queryByText('Grenzen:')).not.toBeInTheDocument()
  })
})

/**
 * Der Gegenpart zur Überschreitungs-Warnung oben: Daten, die die gespeicherten Grenzen
 * nicht mehr ausfüllen, sind kein Anzeigefehler -- jeder verbliebene Wert landet weiter
 * in seiner eigenen, richtig eingefärbten Klasse. Trotzdem gibt es einen scharfen,
 * methodenunabhängigen Fall, der es wert ist, gezeigt zu werden: die äußerste Klasse
 * ist *nachweislich* leer, wenn der aktuelle Höchst-/Tiefstwert nicht mehr bis an ihren
 * Start heranreicht (`classification.ts`'s `upperClassIsEmpty`/`lowerClassIsEmpty`).
 * Bewusst neutral (`text-muted-foreground`), nicht in derselben Warnfarbe wie die
 * Überschreitungs-Warnung -- sonst konkurrieren ein echter Korrektheitsfehler und ein
 * bloßer Auflösungsverlust um dieselbe Aufmerksamkeit (team review).
 */
describe('Leere Randklasse (neutraler Hinweis, keine Warnfarbe)', () => {
  function graduatedRenderer(): Extract<Renderer, { type: 'graduated' }> {
    return {
      type: 'graduated',
      field: 'hoehe',
      // Grenzen 0..300..600..900 -- oberste Klasse beginnt bei 600, unterste endet bei 300.
      classes: buildClasses([0, 300, 600, 900], 'MULTIPOLYGON', DEFAULT_RAMP),
      fallbackSymbol: defaultSymbolFor('MULTIPOLYGON'),
      method: 'quantile',
      classCount: 3,
      ramp: DEFAULT_RAMP,
    }
  }

  it('zeigt den Hinweis, wenn der aktuelle Höchstwert nicht mehr bis an den Start der obersten Klasse reicht', async () => {
    stubFetch([{ match: 'classes=2', body: { field: 'hoehe', method: 'quantile', breaks: [0, 450], min: 0, max: 450, nullCount: 0 } }])

    renderWithQueryClient(
      <GraduatedEditor layerId="layer-1" geometryType="MULTIPOLYGON" renderer={graduatedRenderer()} fields={makeFields()} onChange={vi.fn()} />,
    )

    const hint = await screen.findByText('Die oberste Klasse ist im aktuellen Datenbestand leer.')
    expect(hint).toBeInTheDocument()
    expect(hint.className).toContain('text-muted-foreground')
    expect(hint.className).not.toContain('amber')
  })

  it('zeigt keinen Hinweis, solange der Höchstwert die oberste Klasse noch erreicht', async () => {
    stubFetch([{ match: 'classes=2', body: { field: 'hoehe', method: 'quantile', breaks: [0, 600], min: 0, max: 600, nullCount: 0 } }])

    renderWithQueryClient(
      <GraduatedEditor layerId="layer-1" geometryType="MULTIPOLYGON" renderer={graduatedRenderer()} fields={makeFields()} onChange={vi.fn()} />,
    )

    // Wartet auf denselben Request, bevor negativ geprüft wird -- sonst prüft
    // `queryByText` unter Umständen, bevor die Antwort überhaupt verarbeitet wurde.
    await screen.findByLabelText('Obere Klassengrenze. Werte darüber zeigen dieselbe Farbe wie die oberste Klasse.')
    expect(screen.queryByText('Die oberste Klasse ist im aktuellen Datenbestand leer.')).not.toBeInTheDocument()
  })

  it('zeigt den Hinweis für die unterste Klasse spiegelbildlich', async () => {
    stubFetch([{ match: 'classes=2', body: { field: 'hoehe', method: 'quantile', breaks: [450, 900], min: 450, max: 900, nullCount: 0 } }])

    renderWithQueryClient(
      <GraduatedEditor layerId="layer-1" geometryType="MULTIPOLYGON" renderer={graduatedRenderer()} fields={makeFields()} onChange={vi.fn()} />,
    )

    expect(await screen.findByText('Die unterste Klasse ist im aktuellen Datenbestand leer.')).toBeInTheDocument()
  })

  it('nennt beide Randklassen, wenn beide leer sind', async () => {
    stubFetch([{ match: 'classes=2', body: { field: 'hoehe', method: 'quantile', breaks: [350, 400], min: 350, max: 400, nullCount: 0 } }])

    renderWithQueryClient(
      <GraduatedEditor layerId="layer-1" geometryType="MULTIPOLYGON" renderer={graduatedRenderer()} fields={makeFields()} onChange={vi.fn()} />,
    )

    expect(await screen.findByText('Die unterste und die oberste Klasse sind im aktuellen Datenbestand leer.')).toBeInTheDocument()
  })

  it('zeigt keinen Hinweis, wenn die Überschreitungs-Warnung bereits greift -- beides schließt sich aus', async () => {
    // Höchstwert 1200 überschreitet die obere Grenze (900); die oberste Klasse ist damit
    // per Konstruktion nicht leer (der Höchstwert selbst faellt farblich in sie).
    stubFetch([{ match: 'classes=2', body: { field: 'hoehe', method: 'quantile', breaks: [0, 1200], min: 0, max: 1200, nullCount: 0 } }])

    renderWithQueryClient(
      <GraduatedEditor layerId="layer-1" geometryType="MULTIPOLYGON" renderer={graduatedRenderer()} fields={makeFields()} onChange={vi.fn()} />,
    )

    await screen.findByLabelText(
      'Obere Klassengrenze. Der aktuelle Datenbestand reicht bereits darüber hinaus -- die Klassifizierung passt nicht mehr zu den Daten. Werte darüber zeigen dieselbe Farbe wie die oberste Klasse.',
    )
    expect(screen.queryByText('Die oberste Klasse ist im aktuellen Datenbestand leer.')).not.toBeInTheDocument()
  })
})
