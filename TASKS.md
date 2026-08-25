# Aufgaben in hGIS

Stand: 25.08.2026, Commit `77dd640`. Diese Datei ist für jemanden geschrieben, der neu
dazukommt und eine der offenen Aufgaben übernimmt. Sie enthält den Zustand des Projekts,
die Regeln der Zusammenarbeit und zu jeder Aufgabe genug Kontext, um ohne Rückfragen zu
beginnen.

`PLAN.md` im selben Verzeichnis trägt die lange Fassung — Architektur, Begründungen,
Phasenberichte. Diese Datei ersetzt ihn nicht.

---

## 1. Der Zustand

| Teil | Tests | Wie prüfen |
|---|---|---|
| Backend (Spring Boot 4.1, Java) | **1124** | `cd backend && ./mvnw test` |
| Frontend (React 19, TypeScript) | **1245** | `cd frontend && npx vitest run` |
| Python-Bibliothek und MCP-Server | **430** | `cd python && .venv/bin/python -m pytest -q` |

Alle drei laufen lokal und in der CI grün. Die Zahlen sind am 23.08. gemessen, nicht
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

Seit dem 23.08. gibt `python/src/hgis/mcp/` hGIS als **23 Werkzeuge** an einen Agenten
(zehn lesende, dreizehn schreibende, 73 Parameter). `.mcp.json` im Projektwurzel-
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
| `create_layer` nimmt weder `Point` noch `POINT` | Gültig sind nur `MULTIPOINT`, `MULTILINESTRING`, `MULTIPOLYGON`, `GEOMETRY` (siehe Aufgabe 18) |
| Antwort eines MCP-Werkzeugs steht in `structured_content` **direkt** bei einer einzelnen Struktur, unter `"result"` bei einer Liste | Nachsehen statt raten |
| Beim MCP-Client heißen die Felder snake_case | `input_schema`, `output_schema`, `structured_content`, `is_error` |
| Docker antwortete tagelang nicht, weil die virtuelle Platte voll war (nicht weil es hing) | `~/Library/Containers/com.docker.docker/Data/log/host/monitor.log` lesen, bevor man neu startet |

---

## 4. Offene Aufgaben

Reihenfolge nach meiner Einschätzung des Nutzens, nicht nach Nummer.

---

### 19 — Der Papierkorb ist nicht einsehbar, und er verfälscht die Objektzahl

**Klein, und es steckt ein echter Fehler in den Daten des Nutzers.**

Gefunden am 23.08. beim Agentenlauf, zweifach belegt.

**Erstens: Man kann nicht sehen, was im Papierkorb liegt.** `delete_layer`,
`restore_layer` und `purge_layer` brauchen alle die Layer-Id. Wer sie nicht selbst
notiert hat, kommt nicht mehr an sie heran — es gibt keinen Weg, den Papierkorb eines
Projekts aufzulisten. Für einen Menschen mit Oberfläche mag das reichen; für einen
Agenten, dessen Kontext zwischengespeichert wird, ist eine Id, die nirgends mehr steht,
verloren. `describe_layer` auf einen gelöschten Layer sagt „Verfügbar: keine", ohne den
Papierkorb zu erwähnen.

**Zweitens: `feature_count` eines Projekts zählt gelöschte Layer mit.** Belegt in einem
Wegwerf-Projekt (Layer mit zwei Objekten gelöscht → `layers: []`, aber `feature_count`
blieb bei 2; erst `purge_layer` setzte ihn zurück). **Dasselbe steckt in echten Daten:**
Das Projekt `Flurstücke` meldet `layer_count: 2` und `feature_count: 625`, zeigt aber nur
**einen** Layer mit 99 Objekten.

Ein Zähler, der etwas mitzählt, das man nicht sehen kann, ist die Sorte Zahl, die richtig
aussieht und falsch ist. Wer die 625 liest und die 99 zählt, sucht den Fehler bei sich.

**Vor dem Bauen zu entscheiden:**
1. Soll `feature_count` den Papierkorb mitzählen? Falls ja, muss die Zahl benannt sein
   („625, davon 526 im Papierkorb"). Falls nein, ist es ein Fehler im Backend-Zähler.
2. Wo gehört das Auflisten hin — Backend-Endpunkt, Bibliotheksmethode, MCP-Werkzeug?
   Wahrscheinlich alle drei, in dieser Reihenfolge.
3. Zeigt die Oberfläche den Papierkorb heute an? Falls ja, ist der Weg schon da und nur
   nicht bis zur Bibliothek durchgereicht.

**Betroffen:** `backend/.../catalog/` (Zähler, Auflist-Endpunkt), `python/src/hgis/`
(`TrashEntry` existiert bereits), `python/src/hgis/mcp/read_tools.py`.

---

### 18 — Drei Fehlermeldungen nennen das Ungültige, aber nicht das Gültige

**Klein, und es bricht eine Kernzusage des Projekts.**

„Fehler nennen das Gültige" ist eine der sechs Zusagen aus `PLAN.md` 28.8 — eine, auf die
zwei getrennt entstandene Entwürfe unabhängig gekommen sind. An drei Stellen ist sie
gebrochen, alle im selben Muster: ein `enum.valueOf()`, dessen Ausnahme nur den
abgelehnten Wert weiterreicht.

| Datei | Zeile | Meldung |
|---|---|---|
| `catalog/LayerService.java` | 363 | `Unbekannter Geometrietyp: <raw>` |
| `catalog/LayerService.java` | 406 | `Unbekannter Feldtyp: <raw>` |
| `catalog/LayerFieldService.java` | 244 | `Unbekannter Feldtyp: <raw>` |

**Warum das teuer ist:** Die gültigen Geometrietypen sind `MULTIPOINT`,
`MULTILINESTRING`, `MULTIPOLYGON`, `GEOMETRY` — **kein `POINT`**. Beim Nachmessen habe
ich zweimal hintereinander geraten (`Point`, dann `POINT`) und beide Male nur
„Unbekannter Geometrietyp" bekommen. Erst ein Blick in `GeometryType.java` hat es
geklärt. Ein Agent hat diesen Blick nicht.

Zum Vergleich, wie es richtig geht und im selben System schon funktioniert: Ein
unbekannter **Feldname** in einer Filterabfrage liefert alle vorhandenen Feldnamen, ein
mehrdeutiger Projektname nennt alle Kandidaten mit Id.

**Umsetzung:** In allen drei `catch`-Blöcken die `values()` des Enums an die Meldung
hängen. **Zusätzlich zu entscheiden:** Ob `POINT` als Eingabe akzeptiert und auf
`MULTIPOINT` abgebildet werden sollte — das gehört in den Vertrag, bevor jemand baut.

---

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

---

### 17 — Ein Agent kann keine Projekte anlegen

**Klein, aber Voraussetzung für jede weitere Agentenarbeit.**

`POST /api/projects` steht nicht in `RequestGuard._ALLOWED` (`python/src/hgis/client.py`),
und `Client` hat keine Methode dafür. Die Bibliothek kann Layer anlegen, ändern, löschen,
wiederherstellen und endgültig löschen — aber kein Projekt.

**Warum das mehr ist als eine fehlende Methode:** Ein Agent, der etwas ausprobieren soll,
hat heute keinen Ort dafür. Er muss entweder in ein bestehendes Projekt des Nutzers
schreiben — genau das, was man ihm verbietet — oder er kann nicht arbeiten. Beide
Prüfagenten der letzten Runde mussten zu `curl` greifen, um sich eine Arbeitsfläche zu
schaffen, und dasselbe zum Aufräumen.

**Vor dem Bauen zu entscheiden:**
1. Gehört `POST /api/projects` in die Schranke? Sie ist bewusst eng. Ein Projekt
   anzulegen ist folgenloser als eines zu löschen — aber `DELETE /api/projects/{id}`
   gehört zum Aufräumen dazu, und das ist folgenreich.
2. Ein Projekt zu löschen ist heute durch nichts gedeckt: Der Papierkorb aus Phase 30
   deckt Layer, nicht Projekte. Braucht es erst einen Papierkorb für Projekte?

**Betroffen:** `python/src/hgis/client.py` (`_ALLOWED`, neue Methoden),
`python/src/hgis/mcp/write_tools.py`, `python/tests/test_guard.py` (Pfadprüfung nach dem
vorhandenen Muster — dort steht auch, wie die vier geschlossenen Angriffswege geprüft
werden).

---

### 6 — Schritt 6: Editor mit Pyodide im Web Worker

**Groß. Der letzte offene Schritt der Stufenliste; seine Vorbedingung ist seit dem 23.08.
erfüllt.**

Ein Editor in der Anwendung, in dem sich Python gegen die eigenen Daten schreiben lässt —
Pyodide in einem Web Worker, damit ein langer Lauf die Oberfläche nicht einfriert.

`PLAN.md` ordnet ein: „Nach Schritt 3 ist das Werkzeug fertig. Schritt 4 fügt keinen
neuen Nutzen hinzu, er macht den vorhandenen für Menschen erreichbar." Der Editor ist
also kein neuer Nutzen, sondern der Zugang für Menschen zu dem, was die Bibliothek dem
Agenten schon gibt.

Er kommt nach dem MCP-Server, weil dessen Lauf die Form der Bibliothek klärt, bevor sie
in einer zweiten Umgebung festgeschrieben wird. **Das hat sich gelohnt:** Der MCP-Lauf
hat acht Befunde an der Bibliothek zutage gefördert, alle inzwischen behoben. Wer den
Editor baut, setzt auf eine Bibliothek, die einmal ernsthaft benutzt worden ist.

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

---

### 4 — Legende im Kartenbild

**Mittelgroß. Blockiert war sie durch Aufgabe 1; die ist erledigt.**

Im README offen benannt: „Eine Legende fehlt noch."

Auf dem Kartenbild stehen heute Titel, Maßstabsbalken, Quellenangabe der Hintergrundkarte
und die Angaben der sichtbaren Geoportal-Layer. Der Nordpfeil erscheint nur bei gedrehter
oder geneigter Karte. Was fehlt, ist die Zuordnung von Farbe zu Bedeutung — bei
klassifizierter und abgestufter Darstellung ist das Bild ohne sie nicht auswertbar. Mit
der Heatmap wird die Lücke größer: Ein Farbverlauf ohne Legende ist gar keine Aussage
mehr.

**Es gibt nichts zum Wiederverwenden.** Eine Suche nach Legenden-Komponenten außerhalb
von `styling/` ist leer. Was der Nutzer heute als Legende liest, ist die Klassenliste im
Eigenschaften-Panel, sichtbar nur für den einen aktiven Layer.

**Der frühere Vorbehalt ist entfallen:** Die Legende sollte nicht zweimal gebaut werden,
einmal im Browser und einmal serverseitig. Das serverseitige Kartenbild ist am 18.08.
verworfen worden — sie wird also nur einmal gebaut.

**Der Ort im Code:** alles unter `frontend/src/map/imageExport/`.
- `furniture.ts` — **getestet**, entscheidet was gezeichnet wird.
- `drawFurniture.ts` — **ungetestet**, 240 Zeilen Canvas-2D. Hier käme die Legende dazu.
  Vorhandene Regeln: Schrift `Geist Variable`, Tinte `#171717`, Box
  `rgba(255,255,255,0.86)`, Margin 12 CSS-px, alle Längen über `scaled(v) = v *
  pixelRatio`.
- Die Quellenangabe zeigt, wie mit Platzmangel umgegangen wird: Schrift schrumpft in
  0,5-px-Schritten von 10 auf minimal 7 px, statt zu kürzen. Eine Legende braucht eine
  eigene Antwort auf die Frage, was bei zwölf Klassen und wenig Platz passiert.

**Berührt sich mit Aufgabe 16:** Eine Legende an der Karte wäre auch der Ort, an dem sich
Stil-Warnungen zeigen ließen.

---

### 9 — Entscheidung: aktiver Layer beim Live-Kanal

**Wartet auf eine Entscheidung des Nutzers, nicht auf Arbeit.**

Der Live-Kanal zieht den aktiven Layer nicht mit: Ändert ein anderer Client den
Arbeitsstand, wechselt die eigene Ansicht den aktiven Layer nicht.

Empfehlung aus Phase 29: so lassen, aber einen Hinweis mit Sprungmöglichkeit zeigen. Der
Grund gegen automatisches Mitziehen ist, dass die Ansicht dem Menschen sonst unter den
Händen wegspringt — mitten in einer Bearbeitung ist das ein Datenverlustrisiko.

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

**Nachtrag vom 23.08.: Der Kartenausschnitt gehört zur selben Frage.** Ein Agent kann
über `set_view` Zentrum und Zoom setzen. Im Browser nachgesehen: Der Server-Stand war
sofort korrekt, **aber ein bereits offener Tab zog nicht nach** — erst ein Neuladen
zeigte die neue Position.

Die Abwägung fällt hier möglicherweise anders aus. Beim aktiven Layer ist Nicht-Mitziehen
ein Schutz. Beim Kartenausschnitt, den ein **Agent auf Bitte des Menschen** gesetzt hat,
ist es ein Fehlschlag: Der Mensch hat „zeig mir das" gesagt, der Agent meldet Erfolg, und
der Bildschirm bleibt stehen.

Zu klären: ob ein vom Agenten gesetzter Ausschnitt anders behandelt wird als einer von
einem zweiten Menschen. Der Live-Kanal kennt über `origin` bereits den Urheber.

---

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

---

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

## 5. Erledigte Aufgaben, als Kontext

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
