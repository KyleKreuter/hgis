# hgis

Web-GIS mit QGIS-ähnlichen Funktionen. PostGIS ist nicht nur Ablage, sondern die
Rechen-Engine: Vector Tiles, Filter und später Geoprocessing laufen in der Datenbank,
nicht in Java.

**Stand: Phase 1 (Projektverwaltung) abgeschlossen.** Projekte lassen sich anlegen,
öffnen, umbenennen und löschen. Layer und Karte folgen in Phase 2 und 3.

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

## Zwei Dinge, die man wissen muss

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

## Fahrplan

| Phase | Inhalt | Status |
|---|---|---|
| 0 | Fundament: Compose, Backend-Skelett, Katalog-Migration, Dock-Layout | fertig |
| 1 | Projektverwaltung: Browser, Anlegen, Öffnen, Löschen | fertig |
| 2 | Import: Shapefile, GeoPackage, GeoJSON, CSV über GeoTools | offen |
| 3 | Karte: MVT-Endpunkt und MapLibre | offen |
| 4 | Layerverwaltung: Baum, Reihenfolge, Sichtbarkeit | offen |
| 5 | Attributtabelle und Identify | offen |
| 6 | Digitalisieren und Editieren | offen |
| 7 | Härtung: Integrationstests, Limits, Fehlerbilder | offen |

Der vollständige Plan mit allen Detailabschnitten liegt unter
`~/.claude/plans/melodic-greeting-lollipop.md`.
