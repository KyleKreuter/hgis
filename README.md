# hgis

Web-GIS mit QGIS-ähnlichen Funktionen. PostGIS ist nicht nur Ablage, sondern die
Rechen-Engine: Vector Tiles, Filter und später Geoprocessing laufen in der Datenbank,
nicht in Java.

**Stand: Phase 7 abgeschlossen, MVP steht.** Ein Shapefile, GeoPackage, GeoJSON oder CSV lässt sich
über den Import-Dialog hochladen und erscheint danach in der Karte. Layer lassen sich
ein- und ausblenden, umsortieren, umbenennen und löschen. Die Attributtabelle zeigt die
Daten mit Filter und Sortierung, Karte und Tabelle teilen sich eine Selektion, und ein
Klick auf die Karte liefert die Attribute des getroffenen Objekts. Geometrien lassen sich
zeichnen, verschieben und löschen — mit Einrasten an vorhandenen Geometrien; gespeichert
wird als Batch in einer Transaktion.
252 Backend- und 56 Frontend-Tests decken die Kette vom Upload bis zur Kachel ab.

## Architektur in drei Sätzen

Der Browser holt Geometrien als binäre Vector Tiles direkt aus PostGIS (`ST_AsMVT`),
Attribute separat als JSON. Das Backend bleibt dünn und übersetzt zwischen HTTP und SQL.
Die Datenbank hat zwei Schemas: `gis_meta` ist der Katalog und gehört Flyway, `gis_data`
enthält je eine Tabelle pro Layer, zur Laufzeit per DDL erzeugt und niemals migriert.

## Technik

| Ebene | Wahl |
|---|---|
| Datenbank | PostgreSQL 17 + PostGIS 3.5 (`imresamu/postgis`, arm64-nativ) |
| Backend | Java 21, Spring Boot 4.1, GeoTools 35, Flyway |
| Frontend | React 19, TypeScript 6, Vite 8, Tailwind v4, shadcn/ui, TanStack Router/Query |
| Speicher-CRS | EPSG:25832 (UTM 32N) — metrisch, Puffer und Flächen ohne Umrechnung |

## Voraussetzungen

Java 21+, Maven (via Wrapper), Node 20.19+ (empfohlen: 22 LTS), Docker mit Compose.

## Starten

```bash
# 1. Datenbank
docker compose up -d

# 2. Backend  (http://localhost:8080)
cd backend && ./mvnw spring-boot:run

# 3. Frontend (http://localhost:5173)
cd frontend && npm install && npm run dev
```

Das Frontend proxyt `/api` und `/actuator` auf das Backend, sodass im Browser nur eine
Herkunft existiert und weder CORS noch Upload-Sonderfälle auftreten.

### Ports

Die Datenbank liegt auf **5435**, nicht 5432 — die üblichen Ports sind auf diesem Rechner
von anderen Projekten belegt. Abweichend setzbar über `HGIS_DB_PORT`.

## Nützliche Befehle

```bash
# Datenbank-Konsole
docker compose exec db psql -U hgis -d hgis

# Katalog ansehen
docker compose exec db psql -U hgis -d hgis -c "\dt gis_meta.*"

# Layertabellen ansehen (nach dem ersten Import)
docker compose exec db psql -U hgis -d hgis -c "\dt gis_data.*"

# Frontend-Typprüfung und Produktionsbuild
cd frontend && npm run build
```

## Acht Dinge, die man wissen muss

**Achsenreihenfolge.** EPSG:4326 ist offiziell lat/lon, praktisch erwartet jede Software
lon/lat. `HgisBackendApplication` erzwingt deshalb in einem statischen Initialisierer
Longitude-First. Ohne das landen importierte Daten ohne jede Fehlermeldung an der
falschen Stelle der Erde.

**Tile-Abfragen.** Die Kachel-Bbox wird ins Speicher-CRS transformiert, nicht die
Geometrien nach 3857. Andersherum ist der GiST-Index wertlos und jede Kachel wird zum
Full Table Scan.

**Projekte löschen.** `ON DELETE CASCADE` in `gis_meta` genügt nicht — es entfernt nur
die Katalogzeilen und lässt die Tabellen in `gis_data` als Waisen zurück. Deshalb sammelt
`ProjectDeletionService` erst alle Tabellennamen, droppt sie und löscht dann den Katalog,
alles in einer Transaktion.

**Einrasten läuft nie gegen Kacheln.** `ST_AsMVTGeom` quantisiert Koordinaten auf ein
Raster von 4096 Einheiten je Kachel und vereinfacht die Geometrie. Ein daran
eingerasteter Stützpunkt *sieht* aus wie ein Treffer und liegt Zentimeter bis Meter
daneben — und solche Lücken zwischen benachbarten Flächen fallen erst Jahre später auf.
Der Editor lädt seine Snap-Kandidaten deshalb über die Feature-API in voller Präzision,
und die gefundene Koordinate wird unverändert durchgereicht, nie aus Bildschirmpixeln
zurückgerechnet. Die Toleranz dagegen zählt in Pixeln: eine feste Meterangabe wäre
herausgezoomt unbrauchbar und hineingezoomt wirkungslos. Gerastet wird auf Stützpunkte,
Schnittpunkte und Kanten — in dieser Reihenfolge, nicht nach Abstand: die ersten beiden
sind Orte, die die Daten auszeichnen, ein Kantenpunkt ist nur der, an dem der Zeiger
zufällig stand. Andere Layer lassen sich im Editiermodus als Fangquelle dazuschalten;
ihre Objekte sind dann Ziel, bleiben aber unveränderbar. Punkte rasten ebenfalls ein,
allerdings über einen anderen Weg: terra-draw bietet die Option nur für Linien und
Flächen an, deshalb wird ein gesetzter Punkt nachträglich auf das Ziel gezogen, das der
Marker angezeigt hat — was man sieht, ist was man bekommt.

**Berechnete Fangpunkte werden gerundet, gefundene nicht.** Ein Stützpunkt wird
unverändert durchgereicht; er ist die Koordinate, die in den Daten steht. Ein Punkt auf
einer Kante oder ein Schnittpunkt wird dagegen *gerechnet* und trägt die volle Genauigkeit
eines `double` — rund fünfzehn Nachkommastellen, wo die Feature-API neun liefert. Das ist
nicht nur erfundene Genauigkeit: terra-draw weist eine Geometrie mit mehr als neun Stellen
rundweg zurück, weshalb ein auf eine Kante gezeichnetes Objekt sich nicht wiederherstellen
ließ — Rückgängig leerte die Zeichenfläche endgültig, während der Zähler die Änderung als
zurückgeholt auswies. Deshalb werden nur die berechneten Positionen auf neun Stellen
gerundet.

**Editieren arbeitet auf Einzelgeometrien, gespeichert wird multi.** Layerspalten sind
immer multi-typisiert (`ST_Multi` beim Import), terra-draw kennt aber nur Point,
LineString und Polygon. Beim Laden in den Editor wird deshalb ausgepackt — und was
wirklich mehrteilig ist, bleibt unbearbeitbar, statt stillschweigend auf seinen ersten
Teil reduziert zu werden. Beim Schreiben hebt `ST_Multi` wieder an, genau wie im Import.

**Zeichenwerkzeug und Edit-Buffer sind zwei Kopien derselben Sache.** terra-draw hält die
Geometrien, die man auf dem Bildschirm anfasst; der Buffer hält, was gespeichert wird.
Jede Änderung muss in beide — und in beide Richtungen, was leicht übersehen wird:
Rückgängig setzt Patches auf den Buffer an, wovon das Zeichenwerkzeug nichts mitbekommt,
und das Löschen mit der Entf-Taste passiert im Zeichenwerkzeug, wovon der Buffer nichts
mitbekommt. Beides war zunächst nur einseitig verdrahtet, und beide Male log die
Oberfläche: einmal stand »keine Änderungen« unter einer Fläche, die noch zu sehen war;
einmal verschwand ein Objekt von der Karte und stand nach dem Neuladen wieder da. Der
Abgleich nach Rückgängig baut die Zeichenfläche deshalb aus dem Buffer neu auf statt
Patches nachzuspielen — ein Patch sagt, wie der Buffer sich geändert hat, nicht was die
Karte nun zeigen soll. Ein Flag schaltet dabei die Gegenrichtung stumm, sonst legte der
Abgleich die zurückgenommene Änderung sofort neu an.

**Was noch gezeichnet wird, gehört noch nicht in den Buffer.** Eine entstehende Fläche
wächst mit jeder Zeigerbewegung, und terra-draw meldet jeden Zwischenstand als Änderung.
Aufgezeichnet ergaben drei Ecken acht Verlaufseinträge — acht Klicks auf Rückgängig für
ein Dreieck, genau die Umständlichkeit, die Abschnitt D.2 des Plans vermeiden will. Ein
Objekt tritt einmal in den Buffer ein, wenn `finish` es für fertig erklärt.

**Die `fid` ist die Feature-ID der Kachel, kein Attribut.** `ST_AsMVT(…, 'geom', 'fid')`
benennt `fid` als ID-Spalte, deshalb taucht sie nicht unter den Properties auf.
MapLibre-Ausdrücke müssen `['id']` lesen — `['get','fid']` liefert für jedes Objekt
`null`, der Filter passt auf nichts, und es gibt keinerlei Fehlermeldung.

**Filterausdrücke sind kein SQL.** Der Client schickt einen Ausdruck über Feldnamen, die
er kennt; `FilterParser` löst jeden Bezeichner über `layer_field` auf und bindet jeden
Wert als Parameter. Nur diese beiden Regeln machen die Filterleiste ungefährlich — ein
durchgereichtes SQL-Fragment wäre eine offene Datenbank.

**Layerreihenfolge.** `z_index` zählt von unten: der höchste Wert wird zuletzt gezeichnet
und liegt damit obenauf. Der Layerbaum zeigt genau die umgekehrte Reihenfolge, weil eine
Liste von oben nach unten gelesen wird. Deshalb heißt das Feld im Reorder-Endpunkt
`layerIdsBottomToTop` und der Import vergibt beim Anlegen `max(z_index) + 1` — bei
gleichem Wert lösen Baum und Karte den Gleichstand gegenläufig auf, und derselbe
Datenstand sähe an beiden Stellen anders aus.

## Fahrplan

| Phase | Inhalt | Status |
|---|---|---|
| 0 | Fundament: Compose, Backend-Skelett, Katalog-Migration, Dock-Layout | fertig |
| 1 | Projektverwaltung: Browser, Anlegen, Öffnen, Löschen | fertig |
| 2 | Import: Shapefile, GeoPackage, GeoJSON, CSV über GeoTools | fertig |
| 3 | Karte: MVT-Endpunkt und MapLibre | fertig |
| 4 | Layerverwaltung: Baum, Reihenfolge, Sichtbarkeit, Import-Dialog | fertig |
| 5 | Attributtabelle, Filter, Identify, Selektion | fertig |
| 6 | Digitalisieren und Editieren | fertig |
| 7 | Härtung: Integrationstests, Limits, Fehlerbilder | fertig |

Der vollständige Plan mit allen Detailabschnitten liegt unter
`~/.claude/plans/melodic-greeting-lollipop.md`.
