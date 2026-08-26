# Aufgaben in hGIS

Stand: 26.08.2026, Commit `ae17982`. Diese Datei ist für jemanden geschrieben, der neu
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
| Backend (Spring Boot 4.1, Java) | **1134** | `cd backend && ./mvnw test` |
| Frontend (React 19, TypeScript) | **1277** | `cd frontend && npx vitest run` |
| Python-Bibliothek und MCP-Server | **482** | `cd python && .venv/bin/python -m pytest -q` |

Alle drei laufen lokal und in der CI grün. Die Zahlen sind am 26.08. gemessen, nicht
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

Seit dem 25.08. gibt `python/src/hgis/mcp/` hGIS als **30 Werkzeuge** an einen Agenten
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

Drei Stufen. **Stufe A ist seit dem 26.08. fertig** — hGIS ist agent native, gemessen an
der Abnahmeprobe. Stufe B schliesst die Lücke zur Oberfläche, Stufe C hält das Ergebnis.
Innerhalb einer Stufe steht die Reihenfolge des Nutzens.

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
