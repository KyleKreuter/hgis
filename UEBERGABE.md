# Übergabe — Stand 12.08.2026, Feierabend

Wir stehen mitten in der Behebung von 39 Befunden aus sechs parallelen Codeprüfungen.
Fünf Pakete arbeiten in eigenen Arbeitsverzeichnissen. **Nichts ist zusammengeführt,
nichts ist gepusht.** `main` steht auf `5c063c4` (README Geoportal).

Die vollständige Befundlage mit Fundstellen und Fehlerszenarien:
<https://claude.ai/code/artifact/95bbe3e2-0950-42a0-8843-e26634b9529d>

## Wo die Arbeit liegt

| Arbeitsverzeichnis | Branch | Stand |
|---|---|---|
| `.teams/wt-kern` | `fix/kern` | **WIP**, ungeprüft |
| `.teams/wt-geoportal` | `fix/geoportal` | **fertig**, zwei Commits, Bericht steht aus |
| `.teams/wt-zustand` | `fix/zustand` | **WIP**, ungeprüft |
| `.teams/wt-layout` | `fix/layout` | **WIP**, ungeprüft |
| `.teams/wt-werkzeuge` | `fix/werkzeuge` | **fertig und abgenommen** |

Alle Arbeitsverzeichnisse sind sauber, jeder Zwischenstand ist committet. Die drei
WIP-Commits sind ausdrücklich **ungeprüfte** Zwischenstände: kein Testlauf, kein
Lint, kein Typcheck. Vor dem Weiterarbeiten dort zuerst den Testlauf ansehen.

## Was als Erstes zu tun ist

1. **Die drei WIP-Pakete zu Ende bringen.** Jeweils ein neuer Agent mit dem
   ursprünglichen Auftrag plus dem Hinweis, dass ein Zwischenstand vorliegt. Die
   Aufträge stehen im Sitzungsverlauf; die Befundlage (Link oben) enthält alle
   Fundstellen.
2. **Geoportal-Paket abnehmen.** Zwei Commits liegen vor, der Abschlussbericht fehlt.
   Offen war die Frage nach einer **dritten** Stelle, die `sourceAttribution` als
   Kennzeichen missbraucht — zwei sind behoben (`LayerService.toSource`,
   `Layer.getProvenance`).
3. **Zusammenführen in dieser Reihenfolge:** kern → geoportal → zustand → layout →
   werkzeuge. Backend zuerst, weil konfliktfrei.

## Drei Dinge, die beim Zusammenführen zu tun sind

**`AttributeTable.tsx` ist zwischen zwei Paketen geteilt.** Das Zustands-Paket hat die
Effekte im Bereich Zeile 200–300, das Layout-Paket die Zeilendarstellung und die
Werkzeugleiste. Git sollte das mergen können, weil die Bereiche auseinanderliegen —
aber hinsehen.

**Zwei Tests sind absichtlich rot und müssen umgestellt werden.** Das Werkzeug-Paket
hat in `frontend/src/table/AttributeTable.test.tsx` zwei Tests als `test.fails`
angelegt, weil der Fehler „Sortierung wirkt auf den nächsten Layer weiter“ zu dem
Zeitpunkt noch bestand. Das Zustands-Paket behebt genau den. Nach dem Merge bestehen
die Tests — und `test.fails` macht sie dadurch rot. Markierung entfernen.

**`formatRelative` hat noch keinen Besitzer.** `frontend/src/lib/format.ts:38` wirft bei
ungültigem Zeitstempel `RangeError` und reißt die Projektkachel mit. Das Werkzeug-Paket
durfte keinen Produktivcode anfassen und hat nur das Ist-Verhalten in
`frontend/src/lib/format.test.ts` festgehalten. Wer den Fehler behebt, muss den Test
mitziehen.

## Danach: Lint scharfschalten

`npm run lint:strict` (`oxlint --deny-warnings`) existiert bereits, ist aber noch nicht
das, was `lint` aufruft. Das Werkzeug-Paket hat alle elf stehenden Warnungen einzeln
beurteilt: **keine ist ein echter Fehler.**

- Fünf betreffen Refs auf die MapLibre-Instanz im Cleanup — dort ist der aktuelle Wert
  gewollt. Per Zeilenkommentar stummschalten.
- Sechs betreffen Fast Refresh und nie das Verhalten. Per `overrides` für
  `src/components/ui/**` und `src/routes/**` abschalten.
- Zwei haben Substanz: `badgeVariants` in `components/ui/badge.tsx:52` wird nirgends
  benutzt (toter Export), und `toolsFor` in `editing/DrawController.tsx:38` ließe sich
  in eine eigene Datei ziehen.

Erst wenn alle Pakete zusammengeführt sind, `lint` auf `lint:strict` umstellen.

## Was schon steht

Auf `main`, fertig und geprüft: Phase 23 (Geoportal Hamburg, Stufe 1) samt README, die
reparierte Typprüfung (`npm run typecheck`), der Scroll-Fehler in `ScrollArea`, die
mitscrollende Kopfzeile der Attributtabelle, die responsive Seitenleiste und die
Zeilenmarkierung über die volle Breite.

Auf `fix/werkzeuge`, fertig und abgenommen: Komponententests unter jsdom (vitest in zwei
Projekten), `lint:strict`, Feldnamenprüfung für drei DTOs, vollständiger Stiltest, Tests
für `api/client.ts` und `lib/format.ts`. Backend 677 Tests, Frontend 612.

## Offen, nicht begonnen

- **Geoportal Stufe 2**: der Bildweg (WMS als Hintergrundkarte). War die nächste
  fachliche Entscheidung nach Stufe 1.
- **Die größte Lücke im Geoportal**: Führt ein Dienst mehrere Sammlungen, erreicht hGIS
  bisher nur die erste. Braucht eine Entscheidung, wie die Bedienung mehrere Sammlungen
  anbietet.
- Zuschnitt für Auswahl, Tabelle und Export; Geometrien teilen und zusammenführen;
  Kartenexport PNG/PDF; GeoPackage-Export.
