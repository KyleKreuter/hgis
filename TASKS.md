# Aufgaben in hGIS

Stand: 27.08.2026, Commit `6227496` plus die Fixes aus Abschnitt 5.0. Diese Datei ist für jemanden geschrieben, der neu
dazukommt und eine der offenen Aufgaben übernimmt. Sie enthält den Zustand des Projekts,
die Regeln der Zusammenarbeit und zu jeder Aufgabe genug Kontext, um ohne Rückfragen zu
beginnen.

**Das Ziel seit dem 26.08.: hGIS agent native.** Abschnitt 4 sagt, was das heißt und
woran es gemessen wird. Stufe A in Abschnitt 5 ist Prio 1; alles andere wartet, bis sie
fertig ist.

`PLAN.md` im selben Verzeichnis trägt die lange Fassung — Architektur, Begründungen,
Phasenberichte. Diese Datei ersetzt ihn nicht.

---

## 1. Der Zustand

| Teil | Tests | Wie prüfen |
|---|---|---|
| Backend (Spring Boot 4.1, Java) | **1198** | `cd backend && ./mvnw test` |
| Frontend (React 19, TypeScript) | **1306** | `cd frontend && npx vitest run` |
| Python-Bibliothek und MCP-Server | **506** | `cd python && .venv/bin/python -m pytest -q` |

Alle drei laufen lokal und in der CI grün. Die Zahlen sind am 27.08. gemessen, nicht
geschätzt.

**Aus der Stufenliste in `PLAN.md` ist nur noch Schritt 6 offen** (Editor mit Pyodide,
Aufgabe 6). Schritt 2 (MCP-Server) ist seit dem 23.08. umgesetzt, Schritt 5
(Kartenbilder serverseitig) wurde am 18.08. verworfen.

### Was läuft, und wie man es startet

```bash
docker compose up -d db          # PostGIS auf Port 5435
cd backend && ./mvnw spring-boot:run   # hGIS auf Port 8080
cd frontend && npm run dev             # Oberfläche auf Port 5173
```

Das Backend braucht rund 20 Sekunden bis zur ersten Antwort. Prüfen mit
`curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/projects`.

### Der MCP-Server

Seit dem 25.08. gibt `python/src/hgis/mcp/` hGIS als **32 Werkzeuge** an einen Agenten
(elf lesende, neunzehn schreibende, 101 Parameter). `.mcp.json` im Projektwurzel-
verzeichnis bindet ihn in Claude Code ein. Ein Werkzeugaufruf beantwortet eine kleine
Frage; für alles, was rechnet, gibt es die Bibliothek `hgis`. Näheres in
`python/README.md`, Kapitel „MCP-Server".

---

## 2. Regeln, die für jeden gelten

### Sicherheit

- **Port 8080 und 5173 gehören der laufenden Anwendung.** Lesen immer, schreiben nie —
  mit einer Ausnahme: Das Backend darf jederzeit neu gestartet werden.
- **In der Datenbank liegen zwölf echte Projekte des Nutzers.** Schreibende Zugriffe nur
  gegen ein selbst angelegtes Wegwerf-Projekt, das hinterher wieder verschwindet. Lesen
  ist überall erwünscht — echte Daten sind der bessere Prüfstein.
- **Ein Klick in der Oberfläche schreibt Projektdaten.** Sortierung und Auswahl landen im
  gespeicherten Arbeitsstand. Browsermessungen verändern also Nutzerdaten.
- **Kein maschinenweites `pkill`, `killall` oder `kill %1`.** Nur eigene PIDs beenden, und
  danach ein zweites Mal messen.
- **`browser_close` nicht benutzen** — es schließt das ganze Fenster samt fremder Tabs.

### Mehrere Agenten gleichzeitig

Jedes Team arbeitet in einem eigenen Git-Worktree unter `.teams/wt-<name>`, Branch
`orchestrator/<name>`. **Nie zwei Agenten an derselben Datei**, auch nicht in getrennten
Worktrees. `git -C <pfad>` statt nacktem `git`. Den Worktree eines noch laufenden Agenten
nicht anfassen.

### Wie geprüft wird

**Mutationsproben statt Durchsicht.** Ein Test, der grün bleibt, wenn die Sache kaputt
ist, ist keine Abdeckung. Produktivcode absichtlich kaputt machen, den Test rot sehen,
zurückbauen.

**Zuerst belegen, dass die Probe überhaupt greift.** Eine Mutation bauen, von der man
*erwartet*, dass sie rot wird. Wird sie es nicht, misst man nichts, und jede folgende
grüne Probe bedeutet nichts.

**Die Sicherung wird nach dem Fix angelegt**, nicht davor — sonst vergleicht der Diff
gegen den unreparierten Stand.

**Ausführen statt lesen.** `python/README.md` ist ausführbar; jedes Beispiel darin läuft
wirklich. Drei Stellen sind als Illustration gekennzeichnet
(`grep -n "^# Illustration" python/README.md`) und laufen nicht.

---

## 3. Fallstricke, die schon Zeit gekostet haben

| Falle | Was stattdessen gilt |
|---|---|
| `tsc --noEmit` prüft im Frontend **null Dateien** | Immer `tsc -b --noEmit` |
| Eine Pipeline (`pytest \| tail`) meldet den Exit-Code von `tail` | In eine Datei umleiten, `$?` direkt lesen |
| macOS hat **kein `timeout`** — `timeout 8 cmd \|\| echo "hängt"` meldet den Fehlerzweig, ohne je gemessen zu haben | Befehl direkt laufen lassen, Frist des Werkzeugs setzen |
| `ruff` fehlt im PATH | `uvx ruff@0.16.3 check src tests` oder `python -m ruff` |
| Maven-Textreports melden bei `@Nested` „Tests run: 0" | Zahl aus `target/surefire-reports/*.xml` lesen |
| Mutationsproben in Python messen falsch, wenn alter Bytecode liegt | `find . -name __pycache__ -type d -exec rm -rf {} +` davor |
| Eine Wegwerf-Datenbank ohne `docker/initdb` gemountet hat kein `gis_data`; Layer anlegen scheitert mit nichtssagendem Fehler | `docker-compose.yml` verwenden, nicht von Hand starten |
| `create_layer` nimmt weder `Point` noch `POINT` | Gültig sind nur `MULTIPOINT`, `MULTILINESTRING`, `MULTIPOLYGON`, `GEOMETRY`. Seit dem 26.08. sagt die Meldung das selbst |
| Antwort eines MCP-Werkzeugs steht in `structured_content` **direkt** bei einer einzelnen Struktur, unter `"result"` bei einer Liste | Nachsehen statt raten |
| Beim MCP-Client heißen die Felder snake_case | `input_schema`, `output_schema`, `structured_content`, `is_error` |
| Docker antwortete tagelang nicht, weil die virtuelle Platte voll war (nicht weil es hing) | `~/Library/Containers/com.docker.docker/Data/log/host/monitor.log` lesen, bevor man neu startet |
| Der Tomcat-Connector steht seit dem 27.08. auf `PASS_THROUGH` statt `REJECT`, damit ein kodierter Schrägstrich in einer Geoportal-Id durchkommt (`GeoportalEncodedSlashConfig`) | Anwendungsweit, Tomcat kennt nichts Pfadbezogenes. Unbedenklich, solange es keine pfadbasierte Zugriffsprüfung gibt. **Wer Spring Security einbaut, bewertet diese Klasse neu** |
| Die CI führt für Python nur `ruff check src tests`, kein `ruff format --check` | `ruff format --check` schlägt baumweit auf Altbestand fehl und ist kein Prüfkriterium |
| Maven hält kompilierte Klassen und Migrationsressourcen für aktuell, wenn eine zurückgespielte Datei durch `mv` den alten Zeitstempel trägt — der Rot-Beweis läuft dann mit dem falschen Bytecode grün durch | Nach dem Zurückspielen `touch` auf die Datei; bei Migrationen zusätzlich `rm target/classes/db/migration/<datei>` |
| Ein Kacheldienst antwortet mit 200 und liefert trotzdem kein brauchbares Bild — CARTO legt ein Wasserzeichen über die Kachel, Thüringen schickt eine XML-Fehlerantwort, Sachsen ein Palettenbild | Kachel herunterladen und **ansehen**, nicht nur Status und Content-Type prüfen |

---

## 4. Das Ziel: hGIS agent native

**Seit dem 26.08. ist das die erste Priorität.** Alles in Abschnitt 5, Stufe A, dient ihr;
alles andere wartet.

### Was agent native heißt

Ein Agent erledigt eine GIS-Aufgabe von Anfang bis Ende: ohne Menschen am Bildschirm,
ohne `curl` neben den Werkzeugen, ohne einen Blick in den Quelltext. Sechs Zusagen tragen
das:

1. **Er hat eine eigene Fläche.** Er legt ein Projekt an und räumt es hinterher weg,
   statt in die Daten des Nutzers zu schreiben.
2. **Er holt Daten herein.** Datei-Import und Geoportal-Import, und er sieht, wann der
   Auftrag fertig ist.
3. **Er rechnet und schreibt.** Steht seit Phase 33.
4. **Er zeigt das Ergebnis** — auf dem Bildschirm des Menschen, sofort, ohne Neuladen.
5. **Er belegt das Ergebnis** — als Export, als Zahl, als Ausschnitt.
6. **Er kommt aus jedem Fehler heraus.** Jede Meldung nennt das Gültige, nicht nur das
   Abgelehnte.

**Alle sechs stehen seit dem 26.08.** 3 seit Phase 33; 1, 2, 4 und 6 durch die Aufgaben
17, 20, 9 und 18. Zusage 5 trägt für GeoJSON und für Zahlen; ein Export als Werkzeug
fehlt noch (Aufgabe 24).

### Die Abnahmeprobe

Ein Agent bekommt die MCP-Werkzeuge und einen Satz, sonst nichts:

> Lade `<Datei>` nach hGIS, style sie nach `<Feld>`, und zeig mir das Ergebnis.

Bestanden ist die Probe, wenn er ohne `curl`, ohne Quelltext und ohne Rückfrage
durchkommt und am Ende die Karte des Nutzers auf dem Ergebnis steht.

**Am 26.08. bestanden**, mit 28 Bäumen als Datei und `hoehe_m` als Feld. Ein Agent ohne
Vorwissen, ohne Doku und ohne Quelltext ging elf Aufrufe weit: `create_project`,
`inspect_import`, `import_file`, `field_classes`, `set_style`, `get_style`, `set_view`,
`query_features`, `get_view`, `delete_project`. **Kein einziger Rateversuch, keine
Wiederholung, keine Fehlermeldung.** Drei Stellen trugen ihn ohne Nachfrage:

- `inspect_import` nannte `hoehe_m` als `double precision` mit Beispielwerten, bevor
  etwas geschrieben war. Kein Raten des Feldnamens.
- `field_classes` sagt in seiner eigenen Beschreibung, dass man es vor `set_style` ruft
  und die `breaks` als `classes` weitergibt. Eine Anleitung, keine Vermutung.
- `set_view` mit nur `layer` rechnete den Ausschnitt selbst. Genau die Kurzform, die
  „zeig mir das Ergebnis" braucht.

Der einzige Befund der Probe lag im Prüfclient, nicht in hGIS: Er griff auf die
Antwortfelder im Drahtformat zu (`inputSchema`, `isError`, `structuredContent`) statt
snake_case — der Fallstrick, den Abschnitt 3 seit dem 25.08. führt. Der reparierte Client
liegt jetzt als `python/tools/mcp_call.py` im Projekt, damit die nächste Probe ihn nicht
neu baut.

### Wie weit es heute trägt, in Zahlen

Die Schranke `RequestGuard._ALLOWED` (`python/src/hgis/client.py`) lässt nach Stufe A
**fünfzehn von 24 schreibenden Endpunkten** des Backends durch. Die neun geschlossenen:

| Weg | Was dem Agenten fehlt | Aufgabe |
|---|---|---|
| `PATCH /api/layers/{id}/fields/{fid}` | ein Feld umbenennen | 21 |
| `PUT /api/projects/{id}/layers/order` | Layer neu ordnen | 21 |
| `POST .../features/{fid}/split` | ein Objekt teilen | 21 |
| `POST .../features/merge` | Objekte zusammenführen | 21 |
| `POST /api/projects/{id}/duplicate` | ein Projekt duplizieren | 21 |
| `POST /api/projects/{id}/map-layers` | einen WMS-Layer anlegen | 21 |
| `POST .../export.geojson` | Export mit Filter im Rumpf | 24 |
| `POST /api/places/refresh` | Ortsverzeichnis auffrischen | — |
| `POST /api/geoportal/catalog/refresh` | Katalog auffrischen | — |

Die letzten zwei bleiben zu. Sie sind Wartung des Servers, nicht Arbeit an Daten.

---

## 5. Offene Aufgaben

**Abschnitt 5.0 geht vor.** Dort stehen sechs Fehler aus dem Bustest vom 27.08.; einer
davon macht ein Projekt unbenutzbar. Danach folgen drei Stufen: **Stufe A ist seit dem
26.08. fertig** — hGIS ist agent native, gemessen an der Abnahmeprobe. Stufe B schließt
die Lücke zur Oberfläche, Stufe C hält das Ergebnis. Innerhalb einer Stufe steht die
Reihenfolge des Nutzens.

## 5.0 Fehler aus dem Bustest vom 27.08. — alle sechs behoben

Am 27.08. hat ein Agent versucht, eine Karte nach dieser Aufgabenstellung zu bauen:
Heatmap der Auslastung der Buslinien, Hintergrundkarte schwarz-weiß, Erreichbarkeit im
Fünf-Minuten-Radius um eine Haltestelle. Die Karte ist entstanden — mit HVV-Daten aus
dem Geoportal, 2.122 Bushaltestellen und 684 selbst gerechneten 400-Meter-Puffern. Auf
dem Weg dorthin sind sechs Fehler aufgefallen, einer davon machte die Anwendung
unbenutzbar. **Alle sechs sind am 27.08. behoben** — von vier Agenten in getrennten
Worktrees, jeder mit einem Test, der ohne seinen Fix rot ist. Jeder Befund wurde danach
am laufenden System gegen echte Daten nachgestellt. Die Beschreibungen bleiben stehen,
weil sie erklären, wonach künftig zu suchen ist.

**Das mentale Modell, an dem sich diese Aufgaben messen** (vom Nutzer am 27.08.
festgelegt): Die HTTP-Schnittstelle soll gar nicht benutzt werden. MCP ist für
Discovery und für das Verwalten von Karte und Projekt zuständig, die Python-Bibliothek
für alles, was gerechnet werden muss. Wer Daten braucht, holt sie sich und schreibt ein
Python-Skript, das die Karte anpasst. Jede Stelle, an der ein Agent zu `curl` greifen
muss, ist ein Mangel.

### 27 — Ein Stil ohne `fallback_symbol` legt die Anwendung lahm

**Behoben am 27.08.** auf allen drei Ebenen. Das Frontend fällt in `dataDriven` und
`representativeSymbol` auf `DEFAULT_FILL` zurück, das Backend lehnt einen solchen Stil
mit 400 ab, und die Werkzeugbeschreibung nennt `fallback_symbol` jetzt als Pflichtfeld.
Der Backend-Fix machte neun bestehende Tests rot, die die Lücke unbemerkt ausgenutzt
hatten.

**Zweite Hälfte, die beinahe liegen geblieben wäre:** Der erste Durchgang schützte nur
die Kartendarstellung. `GraduatedEditor.tsx`, `CategorizedEditor.tsx` und `renderer.ts`
lasen `fallbackSymbol` weiter ungeprüft — wer für einen Bestandsstil das Stil-Panel
öffnete, sah denselben weißen Bildschirm an anderer Stelle. Die Validierung allein
hilft dort nicht: Sie verhindert nur neue kaputte Stile, nicht die schon gespeicherten.
Merke für ähnliche Fälle: Nach dem Fix das ganze Verzeichnis nach demselben Zugriff
durchsuchen, nicht nur die Stelle aus dem Stackframe.

**Schwer. Ein Agent kann den Menschen aus seinem Projekt aussperren.**

Ein Renderer vom Typ `graduated` oder `categorized` ohne `fallbackSymbol` führt zum
weißen Bildschirm: „Das Programm konnte das Projekt nicht laden",
`TypeError: Cannot read properties of undefined (reading 'kind')`. Das Projekt lässt
sich danach nicht mehr öffnen. Nur ein erneuter Schreibzugriff über die API befreit
es — wer nur die Oberfläche hat, kommt nicht mehr heran.

Drei Ebenen lassen den Stil durch:

- `frontend/src/styling/styleToMapLibre.ts:629` — `valueOf(renderer.fallbackSymbol)`
  ohne Prüfung auf undefined, für beide Renderer-Typen
- `backend/.../catalog/LayerStyleService.java:405` — prüft das Symbol nur, wenn es da
  ist; fehlt es, geht der Stil durch
- `python/src/hgis/mcp/write_tools.py`, Beschreibung von `set_style` — nennt für
  `graduated` nur `field` und `classes`. Ein Agent, der der Beschreibung folgt, baut
  genau den Stil, der abstürzt.

### 28 — `basemap` nimmt jeden String an

**Behoben am 27.08.** Zuerst als Enum `common/Basemap.java` mit den fünf Werten,
geprüft auf Projekt- und Layer-Ebene. Der Lookup lief über `fromToken()` statt
`Enum.valueOf`, weil `osm-light` einen Bindestrich trägt und keine Enum-Konstante sein
kann. Die Längenprüfung `MAX_BASEMAP_LENGTH` ist entfallen, die Whitelist macht sie
überflüssig. `basemapOpacity` war bereits korrekt auf 0..1 geprüft.

**Noch am selben Tag abgelöst** durch den Katalog aus Abschnitt 5.0b: Das Enum konnte
ein Token prüfen, hatte aber keinen Platz für URL, Quellenvermerk, Zoombereich oder
Gruppe — und fünf Karten sind keine Auswahl. `BasemapCatalog` prüft jetzt dieselbe
Sache gegen 49 Einträge und lässt zusätzlich eine eigene URL-Vorlage zu.

`PATCH /api/projects/{id}` mit `{"basemap": "grayscale"}` antwortet 200. Den Wert gibt
es nicht. Das Frontend fällt still auf OpenStreetMap zurück
(`frontend/src/map/basemap.ts:171`), der ungültige Wert bleibt in der Datenbank stehen.
Kein Fehler, keine Meldung, und der Aufrufer hält für erledigt, was nie passiert ist.

Gültig sind `osm`, `osm-light`, `osm-dark`, `opentopo`, `none`. Keiner heißt
„Graustufen"; `osm-light` ist die entsättigte Variante. Betroffen sind Projekt- und
Layer-Ebene, denn ein Layer kann die Hintergrundkarte des Projekts überschreiben.

### 29 — Groß- und Kleinschreibung bricht drei Aufrufe hintereinander

**Behoben am 27.08.** `FieldType.fromToken()` löst jetzt case-insensitiv auf, und zwar
gegen den Enum-Namen wie gegen `pgType()` — `describe_layer` meldet den zweiten Weg.
Objekteigenschaften löst `EditService` über `LayerFields` auf, dieselbe Klasse, die
`FilterParser` und `QueryFields` längst benutzen; damit zählt weder Schreibweise noch
die Wahl zwischen Anzeige- und Spaltenname. Die „Verfügbar"-Liste in der Fehlermeldung
nennt seitdem Anzeigenamen statt Spaltennamen.

**Nebenwirkung auf den Wachtest:** `EnumValueOfNamesValidValuesTest` findet `FieldType`
nicht mehr, weil dort kein `Enum.valueOf` mehr steht. Die Sanity-Schwelle sank von 5 auf
3 — richtig, aber `fromToken` ist jetzt ein zweites Muster, das kein Test bewacht. Wer
es künftig ohne `unknownTypeMessage` baut, merkt nichts.

Ein Python-Skript, das einen Layer anlegt und Objekte einfügt, ist dreimal abgebrochen:

1. `describe_layer` gibt Feldtypen klein aus (`text`, `bigint`), `create_layer` verlangt
   sie groß (`TEXT`). Wer den Typ eines bestehenden Feldes abliest und weiterreicht,
   läuft auf.
2. `create_layer(fields={"Haltestelle": ...})` legt die Spalte `haltestelle` an.
   `insert_many` verlangt danach `haltestelle` — der Aufrufer bekommt seinen eigenen
   Feldnamen nicht zurück.

Die Meldungen selbst waren gut und nannten jedes Mal die gültigen Werte; das ist
Aufgabe 18, die wirkt. Nötig gewesen wären sie trotzdem nicht.

### 30 — `z_index`, `min_zoom` und `max_zoom` sind schreibbar, aber nicht lesbar

**Behoben am 27.08.** Dazu kamen `basemap`, `basemap_opacity`, `clip_mode` und `source`
(neue Datenklasse `LayerSource` mit Attribution und Lizenz), und bei `Project` fehlten
`basemap` und `basemap_opacity` ebenfalls ganz. Fehlt ein Feld in der Antwort, liefern
die Properties `None` statt eines geratenen Werts: 0 ist eine gültige Position und wäre
von einem echten Wert nicht zu unterscheiden. Die Versionszähler (`dataVersion`,
`styleVersion`, `renderVersion`, `clipVersion`) bleiben bewusst draußen — Cache-Buster
für die Kachel-URL, keine Aussage für einen Aufrufer.

`Layer.update()` (`python/src/hgis/layer.py:348`) nimmt alle drei entgegen, und die API
liefert sie auch (`zIndex`, `minZoom`, `maxZoom`). Die Bibliothek hat keine Property
dafür. Wer die Layer-Reihenfolge ändert, kann nicht nachsehen, was jetzt gilt.

### 31 — Jeder Geoportal-Import legt ein leeres Textfeld `geom` an

**Behoben am 27.08.** in `ingest/reader/QueryablesSchema.java`. Die Geometriespalte wird
an ihrer Rolle erkannt, nicht am Namen: Das queryables-Schema (OGC API Features Part 3)
markiert sie mit `x-ogc-role: primary-geometry`. Ein Datensatz, dessen echtes Attribut
zufällig `geom` heißt, bleibt damit erhalten. Der Fix wirkt an einer Stelle für beide
Symptome, weil Import und Katalogvorschau dieselbe Klasse benutzen.

Nach dem Import steht ein Feld `geom` vom Typ `text` im Layer, zu 100 Prozent `NULL` —
in allen drei geprüften Datensätzen. Der Katalog führt die Geometriespalte in
derselben Liste wie die Attribute, und der Import übernimmt sie unverändert.

### 32 — Katalog-Detail mit URL-kodiertem Schrägstrich gibt 400

**Behoben am 27.08.** Die Ursache lag unterhalb von Spring: Tomcats Connector lehnt
`%2F` per Default ab (`EncodedSolidusHandling.REJECT`), bevor die Anfrage einen
Controller erreicht. Neue Klasse `geoportal/GeoportalEncodedSlashConfig` setzt
`PASS_THROUGH` — nicht `DECODE`, das würde das Routing überall ändern. Siehe den
Fallstrick in Abschnitt 3: Die Einstellung gilt anwendungsweit und ist bei einer
späteren Zugriffsprüfung neu zu bewerten.

```
/api/geoportal/datasets/elektrobusdisposition%2Flinienranking  ->  400
/api/geoportal/datasets/elektrobusdisposition/linienranking    ->  200
```

Die Dataset-Id enthält einen Schrägstrich. Wer sie korrekt kodiert, bekommt eine
nackte Tomcat-Fehlerseite ohne Erklärung.

### Was der Bustest sonst gezeigt hat

Kein Fehler, aber offen, und Kandidaten für Stufe B:

- **Keine Katalogsuche.** 1.217 Datensätze im Geoportal, kein Werkzeug, das sie
  durchsucht. Der Docstring von `import_geoportal` verweist selbst auf
  `GET /api/geoportal/datasets` — die einzige Stelle, an der hGIS einen Agenten
  ausdrücklich zur HTTP-Schnittstelle schickt.
- **Kein Werkzeug für die Hintergrundkarte.** Kartensteuerung ist MCP-Aufgabe, aber es
  gibt weder ein `set_basemap` noch eine Liste der Auswahl.
- **`update_layer` kann zu wenig.** Es kennt `name` und `visible`; die Bibliothek kann
  zusätzlich `z_index`, `min_zoom` und `max_zoom` — alles drei Kartensteuerung.
- **`Client.get()` und `Client.request()`** (`python/src/hgis/client.py:618` und `:350`)
  sind generische Wege in die HTTP-Schnittstelle. Solange sie da sind und ein Docstring
  auf sie zeigt, nimmt sie jeder Agent, sobald etwas fehlt.
- **Eine Heatmap auf einem Polygon-Layer** wird angenommen und gezeichnet, zählt aber
  die Stützpunkte statt der Fläche. Bei Zoom 14 blieben von 733 Einzugsbereichen zwei
  Farbflecken übrig. Die Karte ist dann still falsch.
- **Der Heatmap-Parameter `radius` zählt Bildschirmpixel**, nicht Meter. Ein Radius in
  Gehminuten lässt sich damit nicht ausdrücken, er ändert sich mit jedem Zoomschritt.
  Wer so etwas braucht, rechnet den Puffer in Python — das dauerte für 684 Haltestellen
  0,7 Sekunden.

---

## 5.0b Hintergrundkarten aus einem Katalog — abgeschlossen am 27.08.

Aufgabe 28 hatte nur verhindert, dass `basemap` jeden String annimmt. Damit blieben es
fünf Karten, und ein Agent konnte nicht erfahren, welche. **Seit dem 27.08. gibt es
einen Katalog mit 49 Einträgen** in sechs Gruppen, gebaut von vier Agenten in
getrennten Worktrees.

`GET /api/basemaps` ist die eine Quelle der Wahrheit; Oberfläche und MCP lesen von
dort, statt eigene Listen zu pflegen. Ein Agent ruft `list_basemaps()` und setzt mit
`set_basemap(project, basemap, layer=None, opacity=None)` — für das Projekt oder für
einen einzelnen Layer. Die fünf alten Ids und ihre URLs sind unverändert; zwölf
Projekte tragen sie in der Datenbank.

**Eine Hintergrundkarte ist entweder eine Katalog-Id oder eine eigene URL-Vorlage**,
erkennbar am `https://`-Präfix. Eine Vorlage ist gültig, wenn sie `{z}`, `{x}` und
`{y}` trägt oder `{bbox-epsg-3857}` für eine WMS-GetMap-Adresse. Die zweite Form gibt
es, weil die meisten deutschen Landesvermessungen keinen WMTS anbieten — die Hamburger
Luftbilder existieren nur so. MapLibre ersetzt beide Platzhalter-Arten in derselben
Kette, `wmsTiles.ts` baut die Form für Kartenbild-Layer schon länger.

**Was der Katalog trägt:** die fünf bestehenden Karten, sechs amtliche für Deutschland,
neun thematische, die neun Esri-Dienste (mit `requiresAccount`, sichtbar statt versteckt),
drei EOX-Satellitenbilder und 18 Landesdienste aus 14 Bundesländern. Jede URL ist mit
einem abgerufenen und angesehenen Bild belegt. Die Belege, die verworfenen Kandidaten
und die wörtlichen Lizenzzitate stehen in `docs/basemap-recherche.md`.

**Was bewusst fehlt.** Rheinland-Pfalz und das Saarland erlauben die kostenfreie
Anzeige nur im landeseigenen Portal; jede Einbindung anderswo ist vertragspflichtig.
Baden-Württemberg funktioniert nur mit fremden Zugangsdaten aus einem öffentlich
indexierten Treffer. Sachsen-Anhalt nennt keinen Lizenzstatus. CARTO legt seit einer
Umstellung ein Wasserzeichen über jede Kachel, Stamen steht hinter einem Schlüssel.
Ein WMTS mit eigenem Kachelgitter (Brandenburg, NRW) braucht Rechnen statt
Textersetzung und passt in keine URL-Vorlage — für diese Länder steht der WMS-Zwilling
desselben Datensatzes im Katalog.

**Zwei Fehler, die erst die neuen Tests sichtbar machten:** `layer.basemap` trug eine
Längengrenze von 64 Zeichen aus der Zeit der kurzen Tokens und wies jede echte
Kachel-URL mit 500 statt 400 ab (`V15`). Und ein Test der Oberfläche verbot pauschal
jedes `?` in einer Kachel-URL — richtig für ein Kachelraster, unmöglich für eine
WMS-Adresse, die fast nur aus Parametern besteht.

---

## 5.1 Stufe A — abgeschlossen am 26.08.

Alle vier Aufgaben sind erledigt: 18, 17, 9 und 20 (Abschnitt 7). Die Abnahmeprobe ist
bestanden, ihr Protokoll steht oben in Abschnitt 4.

**Ein Befund aus Aufgabe 20 ist offen** und steht als Aufgabe 26 in Abschnitt 5.2.

---

## 5.2 Stufe B — Parität mit der Oberfläche

Beginnt, wenn Stufe A ganz fertig ist. Danach kann ein Agent alles, was ein Mensch kann.

### 26 — Der Import sagt nicht, was an der Datei kaputt ist

**Klein, und der letzte bekannte Bruch der Zusage „Fehler nennen das Gültige".** Befund
aus Aufgabe 20, gemessen am 26.08.

Eine unlesbare Datei wird abgelehnt mit:

```
Der Import kann die Datei nicht lesen: Der Import kann das GeoJSON nicht
lesen: /var/.../kaputt.geojson
```

Die Meldung nennt die Datei, aber nicht, **was** an ihr ungültig ist und was zu tun wäre.
Ein Mensch öffnet die Datei und sieht nach. Ein Agent, der eine Datei aus einer fremden
Quelle importiert, hat diesen Blick nicht — und er hat die Datei womöglich nicht einmal
selbst geschrieben.

Zum Vergleich, was im selben System schon geht: Seit Aufgabe 18 nennt ein abgelehnter
Geometrietyp alle gültigen. Ein unbekannter Feldname nennt alle vorhandenen.

**Wo es sitzt:** Backend, `ingest/` — `InspectionService` und die `SourceReader` je
Format. Die doppelte Verschachtelung der Meldung („kann die Datei nicht lesen: kann das
GeoJSON nicht lesen") deutet darauf hin, dass zwei Schichten dieselbe Auskunft geben und
keine die eigentliche.

**Zu klären:** Was die Bibliothek (GeoTools, Jackson) an Ursache überhaupt hergibt —
Zeile und Spalte eines JSON-Fehlers sind üblich, ein fehlendes `type`-Feld ebenso. Was
davon durchgereicht werden kann, gehört in die Meldung.

**Nicht vergessen:** Die Probe ist eine kaputte Datei, nicht der Testfall aus
`python/tests/data/kaputt.geojson` allein. Mindestens drei Arten prüfen: ungültiges JSON,
gültiges JSON ohne GeoJSON-Struktur, GeoJSON mit unbekanntem CRS.

---

### 21 — Sechs Schreibwege, die nur die Oberfläche hat

**Mittelgroß, gut teilbar.** Neu am 26.08.

Feld umbenennen (`PATCH /api/layers/{id}/fields/{fid}`), Layer neu ordnen
(`PUT /api/projects/{id}/layers/order`), Objekt teilen und Objekte zusammenführen
(`POST .../features/{fid}/split`, `POST .../features/merge`), Projekt duplizieren
(`POST /api/projects/{id}/duplicate`), WMS-Layer anlegen
(`POST /api/projects/{id}/map-layers`).

Jeder dieser Wege steht im Backend und ist geprüft. Es fehlt jeweils nur der Eintrag in
`_ALLOWED`, eine Methode auf `Client` oder `Layer`, und ein Werkzeug.

**Die Regel aus dem Kommentar über `_ALLOWED` gilt weiter:** Jeder Eintrag hat genau eine
benannte Methode, die seinen Rumpf baut. Kein allgemeines `request(method, path, body)` —
sonst prüft die Schranke nur noch, *wohin* eine Anfrage geht, und nie mehr, was drin
steht.

**Duplizieren ist der nützlichste der sechs:** Es gibt einem Agenten eine Arbeitsfläche
mit echten Daten darin, ohne die Originale anzufassen. Antwortet mit einem Job, hängt
also an Aufgabe 20.

### 22 — Die Paritätsliste als Test

**Klein, und sie hält das Ergebnis von Stufe A und B.** Neu am 26.08.

Heute ist „was kann die Oberfläche, was der Agent nicht kann" eine Handzählung — diese
Datei enthält sie zweimal, und beide Male ist sie beim nächsten Endpunkt veraltet.

**Umsetzung:** Ein Test, der alle `@PostMapping`/`@PutMapping`/`@PatchMapping`/
`@DeleteMapping` im Backend einsammelt und gegen `_ALLOWED` hält. Jeder Weg, der nicht in
der Schranke steht, braucht einen Eintrag in einer Liste bewusst geschlossener Wege — mit
Begründung. Ein neuer Endpunkt ohne Entscheidung macht den Test rot.

Das ist dieselbe Bauart wie der Test für die Vorgabesymbole vom 20.08.: zwei Orte, die
auseinanderlaufen können, durch einen Test zusammengehalten.

### 24 — Export als Werkzeug

**Klein.** Neu am 26.08.

`GET /api/layers/{id}/export.geojson` ist lesend und damit schon erreichbar
(`client.get(...)`), aber es gibt weder Methode noch Werkzeug. Ein Agent, der sein
Ergebnis abliefern soll, kann es heute nur beschreiben.

Die POST-Variante (`export/ExportController.java:72`) nimmt den Filter im Rumpf und
gehört mit in die Schranke — sonst ist ein Export auf eine Auswahl nicht möglich.
GeoPackage kommt mit Aufgabe 7 dazu, nicht hier.

---

## 5.3 Stufe C — dass es agent native bleibt

### 23 — Ein Agent benutzt hGIS eine Stunde lang

**Klein im Aufwand, hoch im Ertrag. Nach jeder Stufe zu wiederholen.** Neu am 26.08.

Die Lehre aus Aufgabe 5, wörtlich: Vier Prüfagenten mit Mutationsproben fanden keinen
einzigen Sachfehler. Ein Agent, der die Werkzeuge eine Stunde lang *benutzte*, fand
stillen Datenverlust und eine Zahl, die seit Wochen falsch in echten Daten stand.

**Der Auftrag:** Ein Agent bekommt die MCP-Werkzeuge, ein Wegwerf-Projekt (nach Aufgabe
17 legt er es selbst an) und eine echte Aufgabe — nicht „prüfe die Werkzeuge", sondern
„beantworte diese Frage mit diesen Daten". Er führt Protokoll über jede Stelle, an der er
raten, nachschlagen oder zu `curl` greifen musste.

**Jede solche Stelle ist ein Befund**, auch wenn nichts kaputt war. Die acht Befunde aus
Phase 33 waren zur Hälfte von dieser Art.

**Der erste Durchlauf ist am 26.08. gelaufen** und steht in Abschnitt 4. Sein Werkzeug
liegt als `python/tools/mcp_call.py` im Projekt: ein Werkzeugaufruf über das Protokoll,
von der Kommandozeile aus. Wer die Probe wiederholt, braucht ihn nicht neu zu bauen —
und geht denselben Weg, den ein Fehler nimmt.

---

## 5.4 Der Rest, nach Stufe C

### 25 — Entscheidung: Ersatzwahl beim aktiven Layer

**Wartet auf eine Entscheidung, nicht auf Arbeit.** War bis zum 26.08. der zweite Teil
von Aufgabe 9; der Ausschnitt ist dort herausgelöst, weil er Prio 1 ist und diese Frage
nicht.

Der Live-Kanal zieht den aktiven Layer nicht mit: Ändert ein anderer Client den
Arbeitsstand, wechselt die eigene Ansicht den aktiven Layer nicht. Empfehlung aus Phase
29: so lassen, aber einen Hinweis mit Sprungmöglichkeit zeigen. Der Grund gegen
automatisches Mitziehen ist, dass die Ansicht dem Menschen sonst unter den Händen
wegspringt — mitten in einer Bearbeitung ist das ein Datenverlustrisiko.

**Eine Bedingung muss vor der Umsetzung feststehen.** Heute wählt nichts automatisch
einen Ersatz, wenn der aktive Layer verschwindet. Das ist Absicht: `jumpToLayer` ruft
ausdrücklich **nicht** `selectLayer` auf — „Writing it back would answer someone else's
change with a change of our own."

Wer eine Ersatzwahl einbaut, öffnet eine Rückkopplung: `viewState.writeActiveLayer()`
löst ein `project-view-state`-Ereignis aus, das alle offenen Browser erreicht. Die
Sicherheit hängt dann an einer Eigenschaft: **Die Ersatzwahl muss eine reine,
deterministische Funktion der geteilten Layerliste sein** — etwa „erster verbleibender
nach `zIndex`". Dann kommen zwei Browser unabhängig zur selben Wahl und finden vor, was
sie selbst geschrieben haben. Hängt die Wahl an etwas Client-Lokalem, laufen sie
dauerhaft auseinander.

### 16 — Stil-Warnungen erreichen ihren Adressaten nicht

**Mittelgroß, und sie macht bereits geleistete Arbeit erst wirksam.**

Befund vom 18.08. Betrifft die Heatmap-Warnung aus Aufgabe 12 genauso wie jede künftige.

Die Warnung für veraltete Heatmap-Grenzen sitzt ausschließlich in `HeatmapEditor.tsx`.
`BoundCheckState` und `HeatmapLegend` kommen in keiner anderen Datei vor.

Sichtbar wird sie nur, wenn drei Bedingungen zugleich gelten
(`routes/projects.$projectId.tsx:560-565`): Ein Layer ist `activeLayer`, es wird nicht
gerade bearbeitet, und genau dieser Layer ist als Heatmap gestylt.

**Die Verschärfung:** Die Sichtbarkeit hängt nicht daran, ob jemand den Stil-Editor
öffnet, sondern daran, ob *dieser eine* Layer zufällig der aktive ist — was aus einer
früheren Sitzung stammen kann, weil `activeLayerId` im Arbeitsstand gespeichert wird.
Liegen mehrere Layer mit veralteten Klassen auf der Karte, zeigt das Panel immer nur den
aktiven. Die anderen bleiben stumm.

Wer die Karte nur betrachtet, sieht nichts. Genau der wird getäuscht: Die Karte sieht
richtig aus, also hat er keinen Anlass nachzusehen.

**Was es nicht gibt:** keine kartennahe Legende (Suche außerhalb `styling/` ist leer).
`LayerTree.tsx` hat zwei Abzeichen-Arten (Zoom-Fenster, Geltungsbereich-Maske), aber
keinen Kanal für Stil-Warnungen.

**Mögliche Richtungen:** Ein Abzeichen an der Layer-Zeile — dort stehen alle Layer
nebeneinander, nicht nur der aktive, und `LayerTree.tsx` hat die Struktur schon. Oder
eine kartennahe Legende; größer, berührt sich mit Aufgabe 4.

**Neu seit dem 23.08.:** Mit `Layer.classify()` und dem MCP-Werkzeug `field_classes` gibt
es jetzt einen Weg, die tatsächlich geltenden Klassengrenzen abzufragen. Eine Warnung
könnte also nicht nur sagen „veraltet", sondern auch, was stattdessen gälte.

**Warum es zählt:** Eine Warnung, die nur der findet, der ohnehin nachsieht, ist teurer
als keine — sie erzeugt den Eindruck, das Problem sei abgedeckt.

### 6 — Schritt 6: Editor mit Pyodide im Web Worker

**Groß. Der letzte offene Schritt der Stufenliste.**

Ein Editor in der Anwendung, in dem sich Python gegen die eigenen Daten schreiben lässt —
Pyodide in einem Web Worker, damit ein langer Lauf die Oberfläche nicht einfriert.

`PLAN.md` ordnet ein: „Nach Schritt 3 ist das Werkzeug fertig. Schritt 4 fügt keinen
neuen Nutzen hinzu, er macht den vorhandenen für Menschen erreichbar." Der Editor ist
also kein neuer Nutzen, sondern der Zugang für Menschen zu dem, was die Bibliothek dem
Agenten schon gibt.

**Warum er hinter Stufe A und B steht:** Er schreibt die Form der Bibliothek in einer
zweiten Umgebung fest. Was Stufe A an ihr ändert — Projekte, Jobs, Import — soll darin
schon stehen, sonst wird es zweimal gebaut. Derselbe Grund, aus dem er hinter dem
MCP-Server stand, und der hat sich gelohnt: Der MCP-Lauf hat acht Befunde an der
Bibliothek zutage gefördert, alle inzwischen behoben.

**Zu klären:**
- Wie kommt die Bibliothek in Pyodide an das Backend? `httpx` läuft dort nicht
  unverändert. **Vorarbeit liegt:** `hgis.transport` hat zwei Böden, `PyodideTransport`
  existiert bereits.
- Was passiert mit einem Lauf, der nicht endet?
- Wie kommen die Pyodide-Pakete zum Browser, wenn hGIS ohne Netz läuft? Sie müssen
  mitgeliefert werden (`PLAN.md` 28.7, Punkt 2).

**Nützlich aus der letzten Runde:** `hgis.NewFeature` und `hgis.Style` lassen sich
unverändert als Parametertypen wiederverwenden; Schema und Deserialisierung entstehen von
selbst. Derselbe Weg dürfte im Editor tragen.

### 7 — GeoPackage importieren und exportieren

**Mittelgroß. Aus dem ursprünglichen MVP-Umfang offen.**

Der Layer-Export kann heute GeoJSON. GeoPackage fehlt auf beiden Wegen. Für den Austausch
mit QGIS ist es das übliche Format, weil es Geometrie, Attribute und Stil in einer Datei
hält.

Der Import-Weg steht bereits (`ingest/`, GeoTools, Typmapping in Detailabschnitt A von
`PLAN.md`) und trägt Shapefile, GeoJSON und CSV. GeoTools kann GeoPackage; der Aufwand
liegt vermutlich weniger im Lesen als in den Randfällen, die für die anderen Formate
schon kartiert sind: Zeichenkodierung, Achsenreihenfolge, Multi-Varianten, fehlendes
Quell-CRS.

**Beim Export mitzudenken:** Das Stil-Schema in `layer.style` ist bewusst kein
MapLibre-Format, damit ein Export nach QGIS möglich bleibt. GeoPackage ist die Stelle, an
der sich zeigt, ob diese Entscheidung getragen hat.

### 10 — Karte mit Git versionieren

**Unklar im Zuschnitt. Vor jeder Arbeit ist zu klären, was gemeint ist.**

Wunsch des Nutzers vom 17.08. Drei Lesarten führen zu sehr verschiedener Arbeit:

1. Die **Projektdefinition** (Layer, Stile, Ansicht, Feldlisten, Zuschnitt) in
   Textdateien, die in einem Git-Repo liegen. Änderungen wären nachvollziehbar,
   vergleichbar, verzweigbar und rückrollbar — mit vorhandenen Git-Werkzeugen.
2. Auch die **Geodaten** mit hinein.
3. Eine Versionshistorie **wie** Git *innerhalb* der Anwendung, ohne echtes Git darunter.

**Die tragende Unterscheidung ist die zwischen Definition und Daten.** Die
Projektdefinition ist klein und textförmig; sie versioniert sich in Git gut, und ein
Vergleich zweier Stände ist lesbar. Die Nutzdaten in `gis_data` sind Millionen
Geometrien — für Git das falsche Werkzeug, jeder Vergleich unlesbar. Lesart 1 ist billig
und nützlich, Lesart 2 teuer und fragwürdig.

**Verhältnis zum Änderungsprotokoll:** Seit Phase 30 gibt es `changelog/` (Migration
V13), das jede Änderung mit der vollständigen Zeile führt und die einzige Rückfallebene
für einzeln gelöschte Objekte ist. Zwei Historien nebeneinander wären Doppelarbeit — vor
dem Bau ist zu entscheiden, ob Git das Protokoll ergänzt oder dieselbe Frage zweimal
beantwortet.

**Was als Ausgangspunkt taugt:** Der Layer-Export kann GeoJSON. Das Stil-Schema ist
semantisch und damit lesbar genug für einen sinnvollen Vergleich. Ein Projektduplikat
gibt es bereits — die Definition lässt sich also schon vollständig erfassen und
wiederherstellen.

---

## 6. Was bewusst nicht gebaut wird

**Kartenbilder serverseitig** (Schritt 5, gestrichen am 18.08.). Am 26.08. noch einmal
geprüft, weil ein Grund der Streichung inzwischen hinfällig ist: „Serverseitig käme
hinzu, dass ein Agent eins anfordern kann — aber diesen Agenten gibt es erst mit dem
MCP-Server." Den Agenten gibt es jetzt.

**Die Streichung bleibt trotzdem.** Die anderen Gründe stehen unverändert
(`PLAN.md`, „Verworfen: Kartenbilder serverseitig"): ein Node-Dauerprozess neben der JVM,
maplibre-native Issue #3169 (ein fehlgeschlagener Teil bricht den ganzen Render-Aufruf
ab, der zweite Versuch hängt), oder ein zweiter Kartenrenderer in Java, den man dauerhaft
mit dem ersten in Übereinstimmung hält.

**Was stattdessen trägt:** Nach Aufgabe 9 zeigt der Agent sein Ergebnis auf dem
Bildschirm des Menschen, und der Knopf für das Bild sitzt dort, wo er schon ist. Der
Agent führt, der Mensch drückt. Das löst den Anwendungsfall zu den Kosten von Null.

**Ortsverzeichnis und Geoportal-Katalog auffrischen** (`POST /api/places/refresh`,
`POST /api/geoportal/catalog/refresh`) bleiben außerhalb der Schranke. Sie warten den
Server, sie bearbeiten keine Daten. Ein Agent, der sie braucht, hat ein anderes Problem.

---

## 7. Erledigte Aufgaben, als Kontext

| Nr. | Aufgabe | Wann |
|---|---|---|
| 1 | Heatmap zusammenführen und im Browser belegen | Phase 31, 17.08. |
| 2 | Heatmap in README und PLAN dokumentieren | 17.08. |
| 5 | **Schritt 2 — MCP-Server auf der Bibliothek** | Phase 33, 23.08. |
| 8 | Bibliothek an den Live-Kanal anschließen | Phase 32, 18.08. |
| 11 | Vorgabesymbole zwischen Backend und Frontend absichern | 20.08. |
| 12 | Heatmap-Gewicht: beide Anker der Normierung wählbar | Phase 32, 18.08. |
| 13 | Live-Kanal meldet auch Datenänderungen | Phase 32, 18.08. |
| 14 | Klassengrenzen und Werteliste veralten still | 18.08. |
| 15 | Beispieldaten im Python-README entscheiden | 20.08. |
| 19 | Papierkorb einsehbar machen (`Project.trash()`, MCP `list_trash`) und Zähler korrigieren | 25.08. |
| 4 | Legende im Kartenbild-Export (Single, Categorized, Graduated, Heatmap) | 25.08. |
| 18 | **Fehlermeldungen nennen die gültigen Werte**, plus ein Test über jeden `valueOf`-Aufruf im Backend | 26.08. |
| 17 | **Ein Agent legt Projekte an und löscht sie** — `create_project`, `delete_project` mit wörtlicher Namensbestätigung, `deletion_impact()` | 26.08. |
| 9 | **Der Kartenausschnitt erreicht den offenen Tab** — neues Ereignis `project-viewport`, `RemoteViewport` zieht sanft nach | 26.08. |
| 20 | **Ein Agent holt Daten herein** — `inspect_import`, `import_file`, `import_geoportal`, `job_wait`, dazu `hgis.Job` auf dem Ereigniskanal | 26.08. |

### Was Aufgabe 5 gelehrt hat

Der MCP-Server war nicht das Ergebnis, sondern das Werkzeug: Ihn zu bauen hat **acht
Befunde an der Python-Bibliothek** zutage gefördert, von denen keiner beim Lesen
aufgefallen wäre. Sechs beim Bauen, zwei beim Benutzen. Alle behoben.

Zwei Beobachtungen daraus sind für die nächste Aufgabe brauchbar:

**Prüfen und Benutzen messen Verschiedenes.** Vier Prüfagenten mit Mutationsproben fanden
keinen einzigen Sachfehler — dafür acht Stellen, an denen ein *künftiger* Fehler
unbemerkt durchgegangen wäre. Ein Agent, der die Werkzeuge eine Stunde lang *benutzte*,
fand stillen Datenverlust und eine Zahl, die seit Wochen falsch in echten Daten steht.
Wer eine Schnittstelle baut, sollte beides tun.

**Ein Test muss den Weg gehen, den der Fehler nimmt.** Der Stil-Datenverlust ließ sich
nur über `server.call_tool()` reproduzieren, nicht über einen direkten Python-Aufruf: Nur
der Protokollweg baut die Struktur über pydantic auf. Der bequemere Test wäre grün
gewesen und hätte nichts gemessen.
