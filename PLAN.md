# hgis — Web-GIS mit PostGIS, Spring Boot und React

> **Stand: 11. August 2026.** Phase 0–7 ist umgesetzt, dazu Einrasten, Import-Vorschau
> und Symbologie. Dieses Dokument ist ab hier ein *gepflegtes* Dokument: Wo die Umsetzung
> vom ursprünglichen Entwurf abwich, steht der tatsächliche Stand — mit dem Grund, denn
> fast jede Abweichung entstand aus etwas, das sich erst beim Bauen zeigte. Solche Stellen
> sind mit **[Abweichung]** markiert. Der Entwurfsstand von vor der Umsetzung liegt
> unverändert unter `~/.claude/plans/melodic-greeting-lollipop.md`.
>
> Kurzstand und offene Punkte stehen im README; der ausführliche Grund für eine
> Entscheidung steht hier.

## Context

`/Users/kylekreuter/IdeaProjects/Privat/hgis` war leer — echtes Greenfield, kein Git-Repo. Ziel ist eine Web-Anwendung mit QGIS-ähnlichen Funktionen: Geodaten importieren, als Layer verwalten, in einer Karte darstellen, in einer Attributtabelle auswerten und Geometrien digitalisieren. PostGIS ist nicht nur Ablage, sondern die Rechen-Engine — Tiles, Filter und später Geoprocessing laufen in der Datenbank, nicht in Java.

Der Plan beschrieb Phase 0–7 bis zu einer lauffähigen App mit **Projektverwaltung + Viewer + Attributtabelle + Digitalisieren**. Styling war ausdrücklich nicht Teil des MVP — es ist inzwischen dennoch gebaut, weil das Schema aus Abschnitt C vorlag und `MvtService` seine Attributauswahl ohnehin daraus ableiten musste. Geoprocessing, Export und OGC-Dienste stehen weiterhin aus (siehe *Roadmap*).

### Getroffene Entscheidungen

| Thema | Entscheidung |
|---|---|
| Rendering | MapLibre GL JS + Vector Tiles, MVT direkt aus PostGIS via `ST_AsMVT` |
| MVP-Scope | Projektverwaltung, Viewer + Attributtabelle, Digitalisieren & Editieren |
| Frontend-Basis | shadcn/ui, TanStack und Tailwind CSS zuerst — Eigenbau nur, wo sie nicht tragen |
| Betrieb | Single-User, lokal via Docker Compose, keine Authentifizierung |
| Datenquelle | Datei-Import (Shapefile, GeoPackage, GeoJSON, CSV) |
| Datenmodell | Eine PostGIS-Tabelle pro Layer mit echten typisierten Spalten |
| Speicher-CRS | EPSG:25832 (UTM 32N), pro Projekt konfigurierbar |
| Import-Engine | GeoTools (pure Java), kein natives GDAL |

### Geprüfte lokale Umgebung

Java 21.0.5 Corretto (aktiv) und JDK 25 verfügbar · Maven 3.9.16 · Node 20.19.0 / npm 10.8.2 · Docker 28.0.4 + Compose v2.34.
**Nicht vorhanden:** `ogr2ogr`, `gdalinfo`, `psql`. Der Import ist deshalb bewusst reines Java; `psql` wird nur für optionale manuelle Checks gebraucht und ist über den PostGIS-Container erreichbar (`docker compose exec db psql`).

> Node 20.19.0 ist exakt die Mindestversion für Vite 7 — funktioniert, aber ein Upgrade auf Node 22 LTS ist empfehlenswert.

---

## Architektur

```
Browser (React + TS)
  │  MapLibre GL  ──►  GET /api/layers/{id}/tiles/{z}/{x}/{y}.mvt   (binäre Vector Tiles)
  │  Attributtabelle ─►  GET /api/layers/{id}/features?…            (JSON, serverseitig paginiert)
  │  Editieren     ──►  POST /api/layers/{id}/edits                 (Batch, eine Transaktion)
  ▼
Spring Boot (Java 21, Maven)
  ├─ catalog   JPA/Hibernate auf gis_meta  → Projekte, Layer, Felder
  ├─ ingest    GeoTools liest Datei → DDL + Batch-Insert nach gis_data
  ├─ tiles     JdbcTemplate → ST_AsMVT, byte[] direkt an den Client
  └─ features  JdbcTemplate → dynamisches SELECT/INSERT/UPDATE/DELETE
  ▼
PostGIS 16 (Docker)
  ├─ gis_meta   Katalog, von Flyway migriert
  └─ gis_data   layer_<hex> Tabellen, zur Laufzeit erzeugt — kein Flyway
```

**Leitprinzip:** Das Katalogschema ist statisch und wird von Flyway und JPA verwaltet. Die Layertabellen sind *Daten*, kein Schema — sie werden zur Laufzeit per DDL erzeugt und ausschließlich über `JdbcTemplate` angesprochen. Diese Trennung in zwei Schemas verhindert, dass Flyway je über Nutzerdaten stolpert.

---

## Datenmodell

### Katalog (`gis_meta`, Flyway-verwaltet, JPA-Entities)

```sql
project (id uuid pk, name text, description text,
         srid int default 25832,        -- nach dem Anlegen unveränderlich, siehe E.2
         center geometry(Point, 25832), -- zuletzt betrachtete Ansicht, siehe E.3
         zoom double precision,
         basemap text,                  -- Kennung der Hintergrundkarte
         extent geometry(Polygon, 25832),
         last_opened_at timestamptz, created_at, updated_at)

layer   (id uuid pk, project_id uuid fk, name text, table_name text unique,
         geometry_type text,             -- MULTIPOINT | MULTILINESTRING | MULTIPOLYGON | GEOMETRY
         srid int, feature_count bigint,
         data_version  bigint default 1, -- Cache-Buster für Tiles bei Datenänderung
         style_version bigint default 1, -- Cache-Buster bei Änderung der Tile-Attributauswahl
         visible boolean, z_index int, min_zoom int, max_zoom int,
         style jsonb,                    -- Schema siehe Detailabschnitt C
         extent geometry(Polygon, 25832), created_at, updated_at)

layer_field (id uuid pk, layer_id uuid fk, source_name text, column_name text,
             data_type text, ordinal int)   -- Mapping Originalspalte → sicherer SQL-Name

import_job  (id uuid pk, project_id uuid fk, layer_id uuid null, filename text,
             status text, message text, feature_count bigint, started_at, finished_at)
```

`layer_field` ist der Schlüssel zur Sicherheit: Der Client sendet nie SQL-Bezeichner, sondern immer `layer_field.id` oder `source_name`. Das Backend löst daraus den echten Spaltennamen auf — nur diese Auflösung darf einen Bezeichner in eine Query setzen.

### Nutzdaten (`gis_data`, zur Laufzeit erzeugt)

```sql
CREATE TABLE gis_data.layer_<32-hex> (
  fid  bigserial PRIMARY KEY,
  geom geometry(MultiPolygon, 25832),   -- konkreter Typ aus dem Import
  <spalte_1> text, <spalte_2> numeric, …
);
CREATE INDEX ON gis_data.layer_<hex> USING gist (geom);
```

Tabellenname ist immer `layer_` + Hex der Layer-UUID — nie vom Nutzer beeinflusst. Attributspalten werden normalisiert (lowercase, nur `[a-z0-9_]`, Präfix bei Ziffernstart, Kollisionssuffix, Kürzung auf 63 Zeichen) und über eine `SqlIdentifier`-Utility gequotet. **Ein einziger Codepfad darf Bezeichner interpolieren; alle Werte laufen ausnahmslos über Bind-Parameter.**

---

## Kernmechanismen

### 1. Tile-Erzeugung — der Index muss greifen

Der naive Ansatz transformiert `geom` nach 3857 und vergleicht dort mit der Tile-Bbox. Das macht den GiST-Index auf `geom` wertlos und führt zu Full Table Scans. Richtig ist, die **Tile-Bbox ins Speicher-CRS zu transformieren**:

```sql
WITH bounds AS (
  SELECT ST_TileEnvelope(:z, :x, :y) AS merc,
         ST_Transform(ST_TileEnvelope(:z, :x, :y), :srid) AS native
)
SELECT ST_AsMVT(tile, :layerName, 4096, 'geom', 'fid') FROM (
  SELECT l.fid,
         ST_AsMVTGeom(ST_Transform(l.geom, 3857), b.merc, 4096, 64, true) AS geom,
         <ausgewählte Attributspalten>
  FROM gis_data.layer_<hex> l, bounds b
  WHERE l.geom && b.native            -- ◄ nutzt den GiST-Index
) AS tile WHERE tile.geom IS NOT NULL;
```

Nur Attribute, die fürs Rendering oder Labeling gebraucht werden, wandern in die Tiles — der Rest kommt über die Feature-API. Welche das sind, leitet `MvtService` aus `layer.style` ab (`renderer.field` und `labels.field`, siehe Detailabschnitt C); im MVP mit Einzelsymbol ist das die leere Menge und es geht nur `fid` mit. Antwort als `application/vnd.mapbox-vector-tile`, Body ist das rohe `byte[]`.

### 2. Tile-Invalidierung — zwei unabhängige Versionen

MapLibre cacht Tiles aggressiv; nach einer Änderung zeigt die Karte sonst veraltete Daten. Der Cache-Schlüssel ist die URL, also steckt die Version darin: `…/{z}/{x}/{y}.mvt?v=<data_version>.<style_version>`.

- `data_version` steigt bei **jeder schreibenden Operation** (Edit-Batch, Import-Nachtrag).
- `style_version` steigt, wenn ein Style-Wechsel die **Attributauswahl der Tiles** ändert — also wenn `renderer.field` oder `labels.field` sich ändern. Eine reine Farbänderung ändert die Tiles nicht und darf den Cache nicht verwerfen; sie wird allein clientseitig über `map.setPaintProperty` angewandt.

Server setzt `ETag` aus beiden Versionen und `Cache-Control: public, max-age=31536000, immutable` — die URL ist ja versioniert.

### 3. Editieren als transaktionaler Batch

Das Frontend sammelt Änderungen lokal in einem Edit-Buffer (Undo/Redo rein clientseitig) und schickt beim Speichern **einen** Request:

```jsonc
POST /api/layers/{id}/edits
{ "creates": [{ "geometry": <GeoJSON>, "properties": { … } }],
  "updates": [{ "fid": 42, "geometry": <GeoJSON|null>, "properties": { … } }],
  "deletes": [17, 23] }
```

Serverseitig eine `@Transactional`-Methode: GeoJSON → JTS (GeoTools `GeoJSONReader`) → Reprojektion nach Layer-SRID → Batch-Statements → `data_version++`, `feature_count` und `extent` aktualisieren. Antwort liefert die vergebenen `fid`s und die neue `data_version` zurück, damit das Frontend Buffer und Tile-URL in einem Schritt aktualisiert.

### 4. Import-Pipeline

Upload → `import_job` mit Status `PENDING` → `202 Accepted` + Job-ID. Verarbeitung asynchron (`@Async` mit eigenem Executor, in Phase 2 bewusst kein Message-Broker):

1. Datei in temporäres Verzeichnis, bei ZIP entpacken und `.shp` suchen (Zip-Slip-Prüfung).
2. GeoTools `DataStoreFinder` öffnet die Quelle, `SimpleFeatureType` liefert Schema und Quell-CRS.
3. Spaltennamen normalisieren, Typen mappen (`String→text`, `Integer/Long→bigint`, `Double→double precision`, `Date→timestamptz`, `Boolean→boolean`, Rest→`text`).
4. `CREATE TABLE` + GiST-Index, `layer`- und `layer_field`-Zeilen anlegen.
5. Features streamen, mit `ST_Transform` nach Projekt-SRID, Batch-Insert in Blöcken (~1000).
6. `feature_count`, `extent` setzen, Job auf `SUCCEEDED`. Bei Fehler: Tabelle droppen, Job auf `FAILED` mit Meldung.

Frontend pollt `GET /api/imports/{jobId}` bis der Job endet.

---

## API-Oberfläche (MVP)

| Methode | Pfad | Zweck |
|---|---|---|
| `GET` | `/api/projects` | Projektliste für den Startbildschirm, sortiert nach `last_opened_at` |
| `POST` | `/api/projects` | Projekt anlegen (Name, Beschreibung, SRID, Hintergrundkarte) |
| `GET` | `/api/projects/{id}` | Projekt mit Layerbaum; setzt `last_opened_at` |
| `PATCH` | `/api/projects/{id}` | Name, Beschreibung, Ansicht (`center`/`zoom`), Hintergrundkarte |
| `DELETE` | `/api/projects/{id}` | Projekt samt aller Layertabellen entfernen (E.4) |
| ~~`POST`~~ | ~~`/api/projects/{id}/duplicate`~~ | **nicht gebaut** — Projekt kopieren als Job (E.5) |
| `POST` | `/api/projects/{id}/imports` | Multipart-Upload → `202` + Job. Nimmt `file` **oder** `uploadId` |
| `POST` | `/api/projects/{id}/imports/inspect` | **neu** — inspiziert ohne zu schreiben, siehe Phase 4 |
| `GET` | `/api/jobs/{jobId}` | Job-Status pollen |
| `GET` | `/api/layers/{id}` | Layer-Metadaten inkl. Felder und Extent |
| `PATCH` | `/api/layers/{id}` | Name, Sichtbarkeit, Reihenfolge, Zoom-Grenzen, Style |
| `DELETE` | `/api/layers/{id}` | Layer + Tabelle entfernen |
| `GET` | `/api/layers/{id}/tiles/{z}/{x}/{y}.mvt` | Vector Tile |
| `GET` | `/api/layers/{id}/features` | Attributtabelle: `page`, `size`, `sort`, `filter`, `bbox` |
| `GET` | `/api/layers/{id}/features/{fid}` | Identify — ein Feature mit allen Attributen |
| `POST` | `/api/layers/{id}/edits` | Editier-Batch |
| `GET` | `/api/layers/{id}/classify` | **neu** — Klassengrenzen, siehe C.3 |
| `GET` | `/api/layers/{id}/values` | **neu** — vorkommende Werte einer Spalte, für die Kategorien |

Fehler einheitlich als RFC-7807 `ProblemDetail` über einen `@RestControllerAdvice`.

> **[Abweichung]** Der Job-Status liegt unter `/api/jobs/{jobId}`, nicht unter
> `/api/imports/{jobId}`. Die Job-Infrastruktur wurde wie in C.4 vorgesehen generisch
> gebaut, und ein importspezifischer Pfad hätte dem widersprochen — Geoprocessing wird
> denselben Endpunkt nutzen.
>
> `/classify` und `/values` nehmen den Quell- **oder** den Spaltennamen entgegen und
> antworten immer mit dem aufgelösten Spaltennamen. Das ist derselbe Umgang mit
> Bezeichnern wie im `FilterParser`: Der Client nennt nie einen SQL-Namen, die Auflösung
> über `layer_field` ist die einzige Stelle, die einen Bezeichner in eine Query setzt.

---

## Projektstruktur

```
hgis/
├── docker-compose.yml            # postgis:16-3.4, Volume, Healthcheck
├── README.md
├── backend/
│   ├── pom.xml                   # Java 21, Spring Boot 3.x, GeoTools, Flyway, Testcontainers
│   └── src/main/
│       ├── java/de/kreuter/hgis/
│       │   ├── HgisApplication.java
│       │   ├── catalog/          # Project/Layer/LayerField Entity, Repository, Service, Controller
│       │   ├── ingest/           # ImportController, ImportService, GeoToolsReader, TypeMapper, CharsetDetector
│       │   ├── tiles/            # TileController, MvtService
│       │   ├── features/         # FeatureController, FeatureQueryService, EditService, FilterParser
│       │   ├── jobs/             # Job-Entity, AsyncJobService, JobController, JobJanitor
│       │   └── common/           # SqlIdentifier, TableCreator, GeometryJson, ProblemDetailAdvice, AsyncConfig
│       └── resources/
│           ├── application.yml
│           └── db/migration/     # V1__catalog.sql, V2__…
└── frontend/
    ├── components.json           # shadcn/ui CLI-Konfiguration
    ├── package.json
    └── src/
        ├── index.css             # Tailwind v4 @theme — Design-Tokens, kompakte Skala (B.8)
        ├── components/ui/        # shadcn-Komponenten, per CLI kopiert und projekteigen
        ├── routes/               # TanStack Router: index (Browser), projects.$projectId
        ├── projects/             # ProjectBrowser, ProjectCard, CreateDialog, DeleteDialog
        ├── layout/               # WorkspaceLayout (resizable), Toolbar, LeftDock
        ├── map/                  # MapProvider, MapCanvas, MapLayerSync, IdentifyPopup
        ├── layers/               # LayerTree, LayerItem, LayerProperties, ImportDialog
        ├── table/                # AttributeTable (TanStack Table + Virtual), FilterBar
        ├── editing/              # DrawController (terra-draw), editBuffer, snapping, AttributeForm
        ├── styling/              # styleToMapLibre — reine Funktion, Snapshot-getestet (C.2)
        ├── api/                  # typisierter Client, TanStack-Query-Hooks
        └── state/                # Zustand-Stores: selection, editing (immer-Middleware), layout
```

Package-Root `de.kreuter.hgis` — beim Bootstrap anpassbar.

### Bibliotheken

**Backend:** Spring Boot Web, Data JPA, Validation · PostgreSQL-Treiber · `hibernate-spatial` (JTS-Typen in JPA für `extent`) · GeoTools `gt-shapefile`, `gt-geopkg`, `gt-geojson`, `gt-csv`, `gt-epsg-hsql` · Flyway · Testcontainers.

> **Stolperfalle:** GeoTools liegt nicht auf Maven Central. In der `pom.xml` muss `https://repo.osgeo.org/repository/release/` als Repository stehen, sonst schlägt der erste Build fehl.

**Frontend — gesetzte Basis: shadcn/ui, TanStack und Tailwind CSS zuerst.** Jede UI-Anforderung wird zunächst mit einer shadcn-Komponente gelöst, jedes Datenproblem mit TanStack, jedes Layout mit Tailwind-Utilities. Eigene Komponenten oder Fremdbibliotheken nur, wo diese drei nachweislich nicht tragen — das betrifft im Wesentlichen die Karte selbst. Details in B.8.

Vite + React 19 + TypeScript · Tailwind CSS v4 über `@tailwindcss/vite` · shadcn/ui (Radix-Primitives, per CLI ins Projekt kopiert) · TanStack Router / Query / Table / Virtual · `react-hook-form` + `zod` für Formulare · Zustand für UI-State · `maplibre-gl` v5 · `terra-draw` + `terra-draw-maplibre-gl-adapter` fürs Digitalisieren (aktiv gepflegt, MapLibre-nativ, Snapping-Hooks vorhanden — im Gegensatz zu `@mapbox/mapbox-gl-draw`, das auf ältere Mapbox-APIs zielt).

---

## Phasen

Alle acht Phasen sind umgesetzt. Was in einer Phase anders kam als geplant, steht unter
der jeweiligen Beschreibung.

**Phase 0 — Fundament.** `git init`, Docker Compose mit PostGIS (Extension `postgis`, Schemas `gis_meta`/`gis_data`), Spring-Boot-Skelett mit Actuator-Healthcheck und gesetzter Achsenreihenfolge (A.4), Flyway-Migration V1 für den Katalog. Frontend: Vite mit Dev-Proxy, Tailwind v4 über `@tailwindcss/vite`, `shadcn init` mit der kompakten Design-Skala aus B.8, `resizable` als erstes Panel-Gerüst. *Ergebnis:* `docker compose up` startet die DB, Backend und Frontend laufen, `/actuator/health` ist grün, das Dock-Layout steht leer aber bedienbar.

**Phase 1 — Projektverwaltung** (Details in Abschnitt E). Projekt-CRUD im Backend inklusive `ProjectDeletionService`, der auch die physischen Tabellen aufräumt (E.4). Im Frontend das TanStack-Router-Gerüst mit Projektbrowser als Startseite und Arbeitsbereich-Route (E.6), Anlege-Dialog mit CRS-Auswahl (E.2), Persistenz des Ansichtszustands (E.3). *Ergebnis:* Beim Start erscheint die Projektliste, ein Projekt lässt sich anlegen, öffnen, umbenennen und löschen. Der Arbeitsbereich ist noch leer, aber projektbezogen — alles Folgende hängt daran.

**Phase 2 — Import.** Generische Job-Infrastruktur (`jobs/`, siehe C.4 — sie trägt später auch das Geoprocessing), Upload-Endpunkt mit ZIP-Absicherung, GeoTools-Reader, `TableCreator` mit `SqlIdentifier`, Typmapping, Kodierungs- und CRS-Erkennung samt Plausibilitätsprüfung, Janitor für verwaiste Jobs. *Ergebnis:* Ein Shapefile-ZIP wird hochgeladen und liegt als PostGIS-Tabelle mit Katalogeintrag vor — Umlaute korrekt, Koordinaten am richtigen Ort.

**Phase 3 — Karte.** `MvtService` mit der indexfreundlichen Query, Tile-Controller mit ETag, MapLibre-Setup mit OSM-Raster als Hintergrund, dynamische Source/Layer-Registrierung, Zoom auf Layer-Extent. *Ergebnis:* Importierte Daten sind in der Karte sichtbar und flüssig navigierbar.

**Phase 4 — Layerverwaltung.** Layerbaum mit Sichtbarkeit, Drag-and-drop-Reihenfolge (`z_index`), Umbenennen, Löschen, maßstabsabhängige Sichtbarkeit. Import-Dialog mit echtem Fortschritt sowie den Korrekturmöglichkeiten aus A.3 und A.7: erkannte Kodierung mit Wertevorschau, erkanntes CRS mit Klartext-Verortung, beides überschreibbar. *Ergebnis:* Mehrere Layer lassen sich wie in QGIS ordnen und schalten; fehlerhafte Importe werden vor dem Schreiben abgefangen.

> **[Abweichung] Die Vorschau kam später und brauchte einen eigenen Endpunkt.** Zunächst
> waren Kodierung und CRS nur überschreibbar, aber blind — man sah nicht, was erkannt
> wurde. Nachgereicht als `POST /api/projects/{id}/imports/inspect`, das inspiziert statt
> zu importieren. Entscheidend ist die zurückgegebene `uploadId`: Ein zweiter Aufruf
> referenziert dieselbe Datei, sodass das Umstellen der Kodierung die Vorschau erneuert,
> **ohne** die Datei erneut zu übertragen. Ohne das wäre bei den erlaubten 500 MB jede
> Korrektur ein neuer Upload und die Vorschau damit unbenutzbar.
>
> **Der Import legt `z_index` nicht auf 0.** Neue Layer bekommen `max(z_index) + 1`.
> Beim Gleichstand lösten Layerbaum und Karte die Reihenfolge gegenläufig auf, sodass die
> Anzeige nicht zur Karte passte.
>
> **Offen:** Die maßstabsabhängige Sichtbarkeit hat keine Bedienung. `min_zoom` und
> `max_zoom` sind validiert und per PATCH setzbar, es fehlt nur die UI.

**Phase 5 — Attributtabelle und Identify.** Serverseitige Paginierung, Sortierung und Filter (eigener Parser für ein kleines, whitelist-basiertes Ausdrucksformat — kein durchgereichtes SQL), virtualisierte Tabelle, Klick auf Karte → Identify-Popup, Zeile → Zoom auf Feature, Selektion beidseitig synchron. *Ergebnis:* Daten sind auswertbar, Karte und Tabelle sind gekoppelt.

**Phase 6 — Digitalisieren** (Details in Abschnitt D). terra-draw für Punkt/Linie/Polygon, Stützpunkte verschieben, Snapping gegen vollpräzises GeoJSON statt gegen Tiles (D.1), Edit-Buffer mit Ausblendfilter auf den Tile-Layern (D.3), Undo/Redo über Immer-Patches mit Zusammenfassen beim Ziehen (D.2), generiertes Attributformular (D.4), serverseitige Validierung ohne stille Reparatur (D.5), `xmin` als Zeilenversion (D.7). *Ergebnis:* Geometrien lassen sich erzeugen, ändern und löschen; Änderungen sind sofort sichtbar, und ungültige Geometrien werden abgefangen statt heimlich umgeformt.

**Phase 7 — Härtung.** Testcontainers-Integrationstests über die ganze Kette (Import → Tile → Edit), Sicherheitstests für Bezeichner und Filterparser, Upload-Limits und Timeouts, strukturierte Fehler, README mit Setup-Anleitung.

> **[Abweichung] Testcontainers laufen über eine einzige Konfiguration.** Es waren drei
> entstanden, was pro Testlauf fünf Container startete. Zusammengelegt sind es zwei,
> gemessen 19,7 s → 13,7 s.

**Phase 8 — Symbologie** (nachgezogen, war Roadmap). Style-Schema aus C.1 gespeichert und
validiert, `styleToMapLibre` als reine Funktion (C.2), Klassifizierung serverseitig (C.3),
`MvtService` leitet seine Attributauswahl aus dem Style ab. Einzelsymbol, kategorisiert,
abgestuft und Beschriftung. *Ergebnis:* Layer sind unterscheidbar, kategorisierte und
abgestufte Darstellung ohne zusätzlichen Server-Roundtrip.

---

## Roadmap nach dem MVP

Geoprocessing als serverseitige PostGIS-Jobs (Abschnitt C) · Projekt- und Layerexport nach GeoPackage/GeoJSON als Gegenstück zur `.qgz` (E.7) · Projekt umprojizieren als bewusste Operation (E.2) · Druck-Layout · WMS/WMTS/WFS-Einbindung mit Backend-Proxy · Multi-User mit Spring Security. Die Endpunkte sind bereits projektbezogen geschnitten, sodass Auth später ohne Umbau der API ergänzt werden kann.

**Aus dem MVP-Umfang noch offen:** `POST /api/projects/{id}/duplicate` (E.5) existiert
nicht — weder Backend noch UI. Ebenso fehlt die Bedienung für die maßstabsabhängige
Sichtbarkeit, und Objekte lassen sich nur über die Entf-Taste löschen, ohne Schaltfläche.

**Braucht eine Entscheidung:** Die Beschriftung lädt Glyphen von
`fonts.openmaptiles.org`. Der Dienst antwortet nicht verwertbar, MapLibre rendert
daraufhin lokal — sichtbar korrekt samt Umlauten, aber über einen Notbehelf und mit einer
Konsolenwarnung je Zeichen. Zugleich ist es die externe Abhängigkeit, die Phase 3
ausdrücklich vermeiden wollte. Wahl zwischen eigenem Glyphen-Dienst im Backend, anderem
Anbieter und bewusstem Verzicht.

---
---

# Detailabschnitt A — Datenmodell & Import

## A.1 Typmapping GeoTools → PostgreSQL

GeoTools liefert pro Attribut eine Java-Klasse über `AttributeDescriptor.getType().getBinding()`:

| Java-Binding | PostgreSQL |
|---|---|
| `String`, `Character` | `text` |
| `Byte`, `Short`, `Integer` | `integer` |
| `Long`, `BigInteger` | `bigint` |
| `Float`, `Double` | `double precision` |
| `BigDecimal` | `numeric` |
| `Boolean` | `boolean` |
| `java.sql.Date` | `date` |
| `java.util.Date`, `Timestamp`, `Instant` | `timestamptz` |
| `java.sql.Time` | `time` |
| `byte[]` | `bytea` |
| `UUID` | `uuid` |
| alles andere | `text` via `toString()` |

Shapefile/DBF kennt nur eine Handvoll Typen: Numerische Felder mit Nachkommastellen kommen als `Double`, ohne als `Long`. DBF begrenzt Feldnamen auf 10 Zeichen — Kürzungen und daraus folgende Kollisionen sind quellseitig und werden übernommen, nicht repariert.

## A.2 Spaltennamen-Normalisierung

Reihenfolge der Regeln in `SqlIdentifier.normalize()`:

1. Unicode-Normalisierung, Umlaute transliterieren (`ä→ae`, `ö→oe`, `ü→ue`, `ß→ss`) — hier ist ASCII korrekt, weil es um SQL-Bezeichner geht, nicht um Text
2. lowercase, alles außer `[a-z0-9_]` durch `_` ersetzen, Mehrfach-`_` zusammenfassen
3. Beginnt es mit einer Ziffer → Präfix `c_`
4. Auf 63 Zeichen kürzen (PostgreSQL-Limit für Bezeichner)
5. Kollision mit `fid` oder `geom` → Suffix `_1` (diese beiden sind real belegt und die einzigen echten Konflikte; PostgreSQL-Keywords sind durch konsequentes Quoting unkritisch)
6. Kollision mit einer bereits vergebenen Spalte → aufsteigendes Suffix

Das Ergebnis landet als `layer_field.column_name`, der Originalname als `source_name`. Die UI zeigt immer `source_name`.

## A.3 Zeichenkodierung bei Shapefile/DBF

Der häufigste Importfehler überhaupt. Ermittlungsreihenfolge:

1. **`.cpg`-Datei** im ZIP lesen, falls vorhanden (Inhalt z. B. `UTF-8`, `ISO-8859-1`, `1252`)
2. **LDID-Byte** im DBF-Header (Offset 29) auswerten, falls ≠ 0
3. **Heuristik:** die ersten ~500 Textwerte strikt als UTF-8 dekodieren (`CodingErrorAction.REPORT`). Bei `MalformedInputException` → **Windows-1252**, nicht ISO-8859-1: In der Praxis stammen die Dateien aus Windows-Werkzeugen, und Windows-1252 deckt die relevanten Zeichen zuverlässiger ab

Gesetzt wird das über `ShapefileDataStore.setCharset(Charset)`. Der Import-Dialog zeigt die erkannte Kodierung und eine **Vorschau der ersten zehn Attributwerte** — der Unterschied zwischen `Müllerstraße`, `MÃ¼llerstraÃŸe` und `M?llerstra?e` ist sofort sichtbar, und der Nutzer kann überschreiben, bevor 200.000 Zeilen falsch importiert sind.

## A.4 Achsenreihenfolge — Fallstrick mit stiller Wirkung

EPSG:4326 ist offiziell **lat/lon**, praktisch erwartet fast jede Software **lon/lat**. Wird das nicht erzwungen, landen Berliner Daten im Indischen Ozean — ohne Fehlermeldung.

Deshalb beim Anwendungsstart global setzen, bevor irgendein GeoTools-Code läuft:

```java
System.setProperty("org.geotools.referencing.forceXY", "true");
Hints.putSystemDefault(Hints.FORCE_LONGITUDE_FIRST_AXIS_ORDER, Boolean.TRUE);
```

Ein Integrationstest fixiert das: bekannter Punkt in 4326 → Import → Rücklesen in 4326 → Koordinaten müssen übereinstimmen.

> **Der Test hat sich sofort bezahlt gemacht, nur anders als gedacht.** Er fiel nicht
> wegen der Achsenreihenfolge durch, sondern deckte einen Fehler auf, der seit Phase 2 im
> Code stand: `ST_Extent` über eine Tabelle mit **genau einem Punkt** liefert einen Punkt,
> keine Box. Der Cast auf `geometry(Polygon)` scheiterte, und damit jeder Import mit einem
> einzigen Objekt. Die Extents werden seither in Java aus der Envelope konstruiert
> (`ExtentCalculator`) statt in SQL gecastet — dieselbe Ursache traf auch das Rollup über
> die Layer eines Projekts.

## A.5 Geometrietypen und Multi-Varianten

Shapefile unterscheidet nicht zwischen `Polygon` und `MultiPolygon` — ein Datensatz kann beides enthalten. Konsequenz:

- **Immer den Multi-Typ als Spaltentyp:** `geometry(MultiPolygon, 25832)` usw. Einzelgeometrien werden beim Insert mit `ST_Multi()` hochgehoben. Das ist dieselbe Strategie wie `ogr2ogr -nlt PROMOTE_TO_MULTI` und verhindert, dass ein einziges Multipolygon den Import kippt.
- **Typermittlung vor dem `CREATE TABLE`:** deklarierten Typ aus dem `SimpleFeatureType` nehmen. Ist er nur `Geometry` (bei GeoJSON die Regel), die ersten 1000 Features sampeln.
- **Echt gemischt** (Punkte *und* Linien in einer Quelle): Spaltentyp `geometry(Geometry, 25832)`, `layer.geometry_type = 'GEOMETRY'`. Das hat eine direkte Folge fürs Frontend, siehe B.6.
- **`GeometryCollection`:** `ST_AsMVTGeom` verarbeitet sie nicht. Beim Insert `ST_CollectionHomogenize` versuchen; gelingt das nicht, Feature überspringen und im Job-Report zählen.
- **Abweichler jenseits des Samples:** Inserts laufen pro Batch mit Fehlertoleranz, fehlgeschlagene Features werden gezählt und mit Grund im Job-Report ausgewiesen. Übersteigt die Fehlerquote 5 %, gilt der Job als `FAILED` — dann stimmt etwas Grundsätzliches nicht und ein halb importierter Layer wäre schlimmer als keiner.

## A.6 CSV-Import

CSV bringt keinerlei Geo-Metadaten mit, also muss alles erkannt oder erfragt werden:

- **Trennzeichen** über Häufigkeitsanalyse der ersten Zeilen (`;` `,` `\t` `|`). Bei deutschen Exporten ist `;` der Normalfall.
- **Dezimaltrennzeichen:** bei `;`-Trennung ist Komma als Dezimaltrenner wahrscheinlich — erkennen, sonst wird aus `52,5` eine Textspalte.
- **Geometriespalten** über Kandidatenlisten: X/Ost aus `x, lon, long, longitude, rechtswert, east, easting`, Y/Nord aus `y, lat, latitude, hochwert, north, northing`, alternativ eine WKT-Spalte aus `wkt, geom, geometry, the_geom`.
- **Quell-CRS ist Pflichtangabe**, wird aber aus dem Wertebereich vorbelegt: `[-180,180]`/`[-90,90]` → 4326; Rechtswert um 32.xxx.xxx bei Hochwert ~5.xxx.xxx → 25832 (mit Zonenpräfix); ~3.xxx.xxx/5.xxx.xxx → Gauß-Krüger (31466/31467).
- **Spaltentypen** durch Sampling: alle nichtleeren Werte als Ganzzahl parsbar → `bigint`, als Dezimalzahl → `double precision`, als ISO-Datum → `date`/`timestamptz`, `true/false/ja/nein` → `boolean`, sonst `text`. Leerwerte werden `NULL` und zählen bei der Typerkennung nicht mit.

## A.7 Fehlendes oder falsches Quell-CRS

| Format | Verhalten |
|---|---|
| Shapefile | `.prj` fehlt häufig → GeoTools liefert `null` |
| GeoPackage | SRS ist immer vorhanden, verlässlich |
| GeoJSON | laut RFC 7946 immer WGS84, in der Praxis oft nicht — altes `crs`-Member auswerten, sonst 4326 annehmen |
| CSV | nie vorhanden, siehe A.6 |

**Plausibilitätsprüfung in jedem Fall:** Bbox der ersten 1000 Features gegen den Gültigkeitsbereich des angenommenen CRS prüfen. Koordinaten wie `502000 / 5720000` bei angenommenem EPSG:4326 sind unmöglich — der Import stoppt und erzwingt die CRS-Auswahl, statt Unsinn zu schreiben.

Der Import-Dialog zeigt das erkannte CRS und eine Klartext-Verortung der Bbox (`Daten liegen bei 52,5° N / 13,4° O`). Falsche CRS sind der teuerste Importfehler, weil er erst auffällt, wenn die Daten längst in der Datenbank stehen.

## A.8 Transaktions- und Rollback-Strategie

Eine einzige Transaktion über den gesamten Import wäre falsch — bei großen Dateien liefe sie minutenlang und würde WAL und Locks aufblähen. Stattdessen drei Abschnitte:

- **A (kurz, transaktional):** `CREATE TABLE` + GiST-Index + `layer`/`layer_field`-Einträge + Job auf `RUNNING`. Commit.
- **B (viele kurze Transaktionen):** Batch-Inserts à 1000 Features, jeder Batch committet, danach `import_job.processed_count` aktualisieren — das speist die echte Fortschrittsanzeige in der UI.
- **C (kurz, transaktional):** `feature_count`, `extent`, Job auf `SUCCEEDED`.

**Kompensation statt Rollback:** Bei Fehler oder Abbruch `DROP TABLE IF EXISTS`, Katalogzeilen löschen, Job auf `FAILED` mit lesbarer Meldung.

**Verwaiste Jobs nach Prozessabbruch:** Ein `ImportJanitor` läuft beim Anwendungsstart, findet alle Jobs im Status `RUNNING` — die kann es nach einem Neustart nicht geben — und räumt sie wie einen Fehlerfall ab. Zusätzlich meldet er Tabellen in `gis_data` ohne Katalogeintrag, damit keine Leichen unbemerkt Platz belegen.

## A.9 Upload-Absicherung

Größenlimit über `spring.servlet.multipart.max-file-size` (Vorschlag 500 MB, konfigurierbar). Beim Entpacken von ZIPs: Pfade auf `../` und absolute Pfade prüfen (Zip-Slip), entpackte Gesamtgröße und Eintragszahl begrenzen sowie das Kompressionsverhältnis prüfen (Zip-Bombe). Pro Job ein eigenes Temp-Verzeichnis, Aufräumen garantiert im `finally`.

---

# Detailabschnitt B — Frontend-Architektur & UI

## B.1 Layout

```
┌──────────────────────────────────────────────────────────────────────┐
│ hgis  [Projekt ▾]   [+ Import]    [↖][•][/][▱]  [↶][↷][✓ Speichern] │ 48px
├─────────────────┬────────────────────────────────────────────────────┤
│ LAYER        ⠿  │                                                    │
│ ┌─────────────┐ │                                                    │
│ │☑ ▦ Gebäude  │ │                     KARTE                          │
│ │☑ ▤ Straßen  │ │                   (MapLibre)                       │
│ │☐ ▣ Flurst.  │ │                                    ┌─────────────┐ │
│ └─────────────┘ │                                    │ Gebäude #42 │ │
│                 │                                    │ name  Haus B│ │
│ EIGENSCHAFTEN   │                                    │ typ   WHG   │ │
│ Features 12.847 │                                    └─────────────┘ │
│ CRS      25832  │                                                    │
│ Zoom     0 – 22 │              32491203, 5712847 · 1:5.000 · [◧]     │
├─────────────────┴────────────────────────────────────────────────────┤
│ ATTRIBUTE — Gebäude       [typ = 'WHG'              ]  [47 / 12.847] │
│ ┌────────┬────────────┬─────────┬───────────────────────────────────┐│
│ │ fid    │ name       │ typ     │ flaeche                           ││
│ │ 41     │ Haus A     │ WHG     │ 124,50                            ││
│ │ 42     │ Haus B     │ WHG     │  98,20      ◄ selektiert          ││
│ └────────┴────────────┴─────────┴───────────────────────────────────┘│
└──────────────────────────────────────────────────────────────────────┘
```

Linkes Dock und Attributpanel sind per Ziehgriff größenveränderlich, das Attributpanel zusätzlich einklappbar. Größen landen in `localStorage`.

## B.2 Komponentenbaum

```
<App>
├── <Toolbar>
│   ├── <ProjectMenu>  <ImportButton → ImportDialog>
│   └── <EditToolbar>              // nur im Editiermodus aktiv
└── <WorkspaceLayout>              // resizable Panels
    ├── <LeftDock>
    │   ├── <LayerTree> → <LayerItem>*
    │   └── <LayerProperties>
    ├── <MapProvider>              // besitzt die MapLibre-Instanz
    │   ├── <MapCanvas>
    │   ├── <MapLayerSync/>        // rendert nichts, synchronisiert Store → Karte
    │   ├── <DrawController/>      // terra-draw, nur im Editiermodus gemountet
    │   ├── <IdentifyPopup>
    │   └── <MapStatusBar>         // Koordinaten, Maßstab
    └── <AttributePanel>
        ├── <FilterBar>
        └── <AttributeTable>       // TanStack Table + Virtual
```

## B.3 MapLibre in React — die kritische Stelle

MapLibre ist imperativ und mutabel, React deklarativ. Die verbreitete Fehlerquelle ist, die Map-Instanz wie einen State-Wert zu behandeln. Fünf Regeln:

1. Instanz in `useRef`, Erzeugung in einem `useEffect` mit leerem Dependency-Array, Cleanup ruft `map.remove()`.
2. **Nie** `useState` für die Map-Instanz — sie ist kein Wert, der Renders auslösen soll.
3. Zugriff für Kinder über `MapContext` mit dem Ref **und** einem `isLoaded`-Flag. Kinder, die `addSource`/`addLayer` rufen, rendern erst nach dem `load`-Event — vorher wirft MapLibre.
4. React 19 StrictMode mountet Effects im Dev-Modus doppelt. Der Guard über den Ref und ein idempotenter Cleanup sind Pflicht, sonst hängen zwei Karten im selben Container.
5. Alles, was die Karte verändert, passiert in Effects — nie während des Renderns.

## B.4 Synchronisation Store → Karte

`<MapLayerSync>` rendert `null` und wendet in einem Effect den Diff an:

```
1. Sources anlegen, die im Store sind, aber nicht in der Karte
2. Sources entfernen, die in der Karte sind, aber nicht mehr im Store
3. Tile-URL vergleichen — bei geänderter Version die Kacheln neu laden
4. Reihenfolge über map.moveLayer(id, beforeId) gemäß z_index herstellen
```

Zu Schritt 3: Eine bestehende Source kann ihre URL nicht per Neuzuweisung ändern. MapLibre bietet dafür `map.getSource(id).setTiles([neueUrl])` — das lädt neu, ohne die Layer abzureißen, und vermeidet das Flackern. Fällt das aus (ältere Version, Typmismatch), ist der Fallback `removeLayer` → `removeSource` → `addSource` → `addLayer` in dieser Reihenfolge; die Verfügbarkeit von `setTiles` wird beim Bootstrap gegen die dann aktuelle MapLibre-Version geprüft.

## B.5 State-Aufteilung

| Zustand | Ort | Begründung |
|---|---|---|
| Projekt, Layerliste, Layer-Metadaten | TanStack Query | Server ist die Wahrheit, Cache und Invalidierung geschenkt |
| Feature-Seiten der Attributtabelle | TanStack Query, `placeholderData: keepPreviousData` | serverseitig paginiert, Cache-Key aus Filter + Sortierung |
| Import-/Job-Status | TanStack Query mit `refetchInterval` | Polling ist Server-State, kein UI-State |
| Sichtbarkeit, `z_index` | Query-Mutation mit optimistischem Update | persistiert, UI reagiert trotzdem sofort |
| Aktiver Layer, Selektion (`Set<fid>`) | Zustand | reiner UI-State |
| Kartenposition | Zustand, gedrosselt auf `moveend` | ändert sich zu oft für jeden Frame |
| Editiermodus, Edit-Buffer, Undo-Stack | Zustand | rein clientseitig bis zum Speichern |
| Panelgrößen, Aufklappzustände | Zustand + `localStorage` | Layout-Präferenz |

Faustregel: Was der Server kennt, gehört in TanStack Query. Was nur der Bildschirm kennt, gehört in Zustand. Nichts gehört in beides.

## B.6 Selektion, Identify und gemischte Geometrietypen

Die Selektion ist ein `Set<number>` von `fid`s im Zustand-Store — **eine** Wahrheit, in die Karte und Tabelle beide schreiben, weshalb keine Rückkopplung entstehen kann.

- Karte: ein Highlight-Layer über dem normalen, gefiltert per `['in', ['get','fid'], ['literal', [...fids]]]`
- Tabelle: Zeile hervorheben, `scrollToIndex` von TanStack Virtual springt hin
- Identify: `map.queryRenderedFeatures` liefert die `fid`, die Attribute holt `GET /features/{fid}` — die Tiles enthalten sie ja bewusst nicht

**Gemischte Layer** (`geometry_type = 'GEOMETRY'` aus A.5) brauchen drei MapLibre-Layer auf derselben Source, getrennt per `['==', ['geometry-type'], 'Point' | 'LineString' | 'Polygon']`. `<MapLayerSync>` muss diesen Fall von Anfang an kennen, sonst bleiben solche Layer unsichtbar.

## B.7 Performance

Attributtabelle virtualisiert, nur sichtbare Zeilen im DOM. Serverseitige Seiten à 200 Zeilen, **Keyset-Pagination statt `OFFSET`** — `OFFSET 500000` zwingt PostgreSQL, eine halbe Million Zeilen zu verwerfen. Filtereingaben um 300 ms entprellt. Kartenposition nur bei `moveend` in den Store, nicht bei jedem `move`.

## B.8 shadcn/ui, Tailwind und TanStack als Basis

**Warum shadcn passt:** Die Komponenten werden per CLI in `src/components/ui/` kopiert und gehören dem Projekt — keine Bibliotheksversion, die man um eine Anpassung herum biegen muss. Für ein Werkzeug wie dieses ist das entscheidend, weil GIS-UIs ständig von den Defaults abweichen (dichte Listen, mehrspaltige Kontextmenüs, Panels mit eigenem Fokusverhalten). Unter der Haube sitzt Radix, also sind Tastaturbedienung, Fokusfallen und ARIA-Rollen bereits korrekt — bei einem Werkzeug, das man stundenlang benutzt, zählt das mehr als das Aussehen.

**Benötigte Komponenten**, nach Einsatzort:

| Bereich | shadcn-Komponenten |
|---|---|
| Dock-Layout | `resizable` (react-resizable-panels) — trägt das gesamte Layout aus B.1 |
| Layerbaum | `checkbox`, `context-menu`, `scroll-area`, `separator`, `collapsible` |
| Layer-Eigenschaften | `tabs`, `slider` (Transparenz), `input`, `label`, `switch` |
| Import | `dialog`, `progress`, `select` (CRS/Kodierung), `alert` |
| Attributtabelle | `table` (nur Styling), `input`, `dropdown-menu`, `badge` |
| Werkzeugleiste | `toggle-group` (Zeichenwerkzeuge), `tooltip`, `button` |
| Editieren | `form`, `alert-dialog` (Verwerfen bestätigen), `sonner` (Meldungen) |
| Identify | `popover`, `card` |

**Drei Reibungspunkte, die früh Aufmerksamkeit brauchen:**

1. **shadcn `table` trägt die Virtualisierung nicht.** Es liefert nur gestylte `<table>`-Primitives. TanStack Virtual braucht absolut positionierte Zeilen mit `transform`, was mit `<tbody>`-Semantik kollidiert. Der gangbare Weg: shadcn-Klassen für Kopfzeile und Zellen übernehmen, den Zeilencontainer aber selbst als Grid-Layout bauen. Das früh entscheiden — nachträglich ist es ein Umbau der ganzen Tabelle.
2. **MapLibre bringt eigenes CSS mit.** `maplibre-gl.css` und Tailwinds Preflight streiten sich um Popups und Controls. Deshalb: MapLibre-CSS *nach* Tailwind importieren, und die eingebauten Controls (Zoom, Maßstab, Attribution) durch eigene shadcn-Komponenten ersetzen, statt sie zu überschreiben. Das ist ohnehin nötig, damit die Karte nicht wie ein Fremdkörper im übrigen UI wirkt.
3. **Die shadcn-Defaults sind zu luftig für ein GIS.** Sie zielen auf Web-Anwendungen mit viel Weißraum; ein Werkzeug braucht Dichte. Beim `init` eine kompaktere Skala festlegen — Basisschrift 13 px statt 16 px, reduzierte Zeilenhöhen, kleinere Radien, engere Paddings in Listen und Tabellen. Tailwind v4 macht das über `@theme` direkt in der CSS-Datei, ein `tailwind.config.js` ist nicht mehr nötig.

**TanStack durchgängig:** Query für allen Server-State (siehe B.5), Table für Spaltenmodell, Sortierung und Filterzustand der Attributtabelle, Virtual für Zeilen *und* für lange Layerlisten. Formulare laufen über `react-hook-form` mit `zod`-Schema — beim Attributformular wird dieses Schema zur Laufzeit aus `layer_field` erzeugt, siehe D.4.

---

# Detailabschnitt C — Style-Schema & Geoprocessing

Beides war nicht MVP. Die Schemata vorab festzulegen hat sich gelohnt: **C.1 bis C.3 sind
inzwischen umgesetzt**, ohne dass `MvtService` oder die Job-Infrastruktur umgebaut werden
mussten. C.4 und C.5 (Geoprocessing) stehen weiterhin aus; die Job-Infrastruktur trägt sie
bereits.

> **Drei Dinge, die sich erst beim Bauen zeigten:**
>
> **`renderer.field` und `labels.field` werden auf den Spaltennamen kanonisiert.** Der
> Client darf beide Schreibweisen senden, gespeichert wird der `column_name` — denn die
> Kachel führt ihre Attribute unter genau diesem Namen. Ohne die Kanonisierung suchte
> `["get", "Straße"]` nach einer Spalte, die `strasse` heißt, und alle Objekte fielen auf
> das Fallback-Symbol. Das Frontend schreibt denselben Namen von sich aus, weil es sonst
> in der optimistischen Vorschau — die bewusst nicht auf die Serverantwort wartet — den
> falschen Namen verwendet hätte.
>
> **Eine Weglass-Regel darf keine Bedeutung löschen.** `@JsonInclude(NON_NULL)` ist im
> Style-Schema richtig, mit einer Ausnahme: Bei einer Kategorie bleibt `value: null`
> erhalten. „Objekte ohne Wert" ist eine Kategorie, die man legitim einfärbt und die
> `/values` ausdrücklich anbietet; weggelassen wäre sie von einer halbfertigen Kategorie
> ohne gewählten Wert nicht zu unterscheiden. Dasselbe Muster traf das Frontend von der
> anderen Seite: Ein TypeScript-Typ verspricht jedes Member, die API liefert optionale
> Felder aber gar nicht erst.
>
> **`undefined` und `NaN` kosten den ganzen Layer, nicht die eine Eigenschaft.** Ein
> gültiger Style ohne `outlineColor` erzeugte `fill-outline-color: undefined`, woraufhin
> MapLibre den Layer komplett verwarf — die Objekte waren weg, sichtbar nur an einer
> Konsolenzeile. Dasselbe gilt für `NaN`, das entsteht, wenn ein fehlendes `opacity` in
> einen Paint-Wert multipliziert wird. Symbolfelder werden deshalb feldweise aus Defaults
> aufgefüllt, und kein Paint-Objekt verlässt die Abbildung mit einem undefinierten Member.

## C.1 Style-Schema (`layer.style`)

Bewusst **nicht** die MapLibre-Style-Spec speichern: Die ist renderer-spezifisch, und ein Export nach QGIS oder SLD wäre damit verbaut. Stattdessen ein eigenes, semantisches Schema, das auf MapLibre *abgebildet* wird.

```jsonc
{
  "version": 1,
  "renderer": {
    "type": "single" | "categorized" | "graduated",
    "symbol": { … },                          // bei "single"
    "field": "nutzungsart",                   // bei "categorized" / "graduated"
    "categories": [ { "value": "Wohnen", "label": "Wohnbebauung", "symbol": {…} } ],
    "classes":    [ { "min": 0, "max": 100, "label": "0 – 100", "symbol": {…} } ],
    "method": "quantile" | "equalInterval" | "naturalBreaks" | "manual",
    "rampName": "viridis",
    "fallbackSymbol": {…}
  },
  "labels": {
    "enabled": true, "field": "name", "placement": "point" | "line" | "centroid",
    "size": 12, "color": "#333", "haloColor": "#fff", "haloWidth": 1.5,
    "minZoom": 14, "allowOverlap": false
  },
  "opacity": 0.9, "minZoom": 0, "maxZoom": 22
}
```

Symbole je Geometrietyp:

```jsonc
{ "kind": "marker", "shape": "circle"|"square"|"triangle", "size": 6,
  "fillColor": "#e74c3c", "strokeColor": "#fff", "strokeWidth": 1 }
{ "kind": "line", "color": "#2980b9", "width": 2, "dashArray": [2,2],
  "cap": "round", "join": "round" }
{ "kind": "fill", "fillColor": "#27ae60", "fillOpacity": 0.5,
  "outlineColor": "#1e8449", "outlineWidth": 1 }
```

## C.2 Abbildung auf MapLibre

Eine reine Funktion `styleToMapLibre(style, layerMeta) → LayerSpecification[]`:

| Renderer | MapLibre-Ausdruck |
|---|---|
| `single` | konstante Paint-Properties |
| `categorized` | `["match", ["get", field], v1, c1, v2, c2, …, fallback]` |
| `graduated` | `["step", ["get", field], c0, grenze1, c1, grenze2, c2, …]` |
| `labels` | zusätzlicher `symbol`-Layer mit `text-field: ["get", field]` |

Der entscheidende Punkt: Weil MapLibre datengetriebenes Styling in der Spec beherrscht, brauchen kategorisierte und abgestufte Darstellungen **keinen zusätzlichen Server-Roundtrip**. Es muss nur das Klassifizierungsfeld in den Tiles liegen.

**Genau das ist die Rückwirkung auf Phase 3:** `MvtService` darf die Attributauswahl nicht fest verdrahten, sondern leitet sie aus `renderer.field` und `labels.field` ab. Ändert sich diese Menge, steigt `style_version` und damit die Tile-URL (siehe Kernmechanismus 2). Eine reine Farbänderung lässt die Tiles unberührt und wird clientseitig über `setPaintProperty` angewandt.

## C.3 Klassengrenzen serverseitig berechnen

```
GET /api/layers/{id}/classify?field=einwohner&method=quantile&classes=5
```

Quantile über `percentile_cont`, gleiche Intervalle über `min`/`max`, Natural Breaks (Jenks) auf einer Stichprobe oder als `ntile`-Näherung — exaktes Jenks ist quadratisch und für große Layer untragbar.

## C.4 Geoprocessing-Jobs — gemeinsame Basis mit dem Import

```sql
processing_job (id uuid pk, project_id uuid fk, algorithm text, parameters jsonb,
                status text, progress int, output_layer_id uuid, message text,
                started_at, finished_at)
```

Das ist strukturgleich zu `import_job`: langlaufende Operation, Status, Fortschritt, Ergebnis-Layer. **Deshalb sollte schon Phase 2 eine gemeinsame Abstraktion bauen** — eine `job`-Tabelle mit `type`-Diskriminator und ein `AsyncJobService`. Dann teilen sich Import und Geoprocessing den Polling-Endpunkt, die Fortschrittsanzeige und den Janitor aus A.8, statt beides zweimal zu schreiben. Das ist der handfeste Ertrag des Vorziehens.

## C.5 Algorithmus-Registry

```java
interface ProcessingAlgorithm {
  String id();                        // "buffer", "intersection", "clip", "dissolve"
  List<ParameterSpec> parameters();   // treibt das generische UI-Formular
  String outputGeometryType(Context ctx);
  String buildSql(Context ctx);       // CREATE TABLE … AS SELECT …
}
```

Jeder Algorithmus schreibt sein Ergebnis direkt als neue Tabelle — die Daten verlassen die Datenbank nie:

```sql
CREATE TABLE gis_data.layer_<neu> AS
SELECT ST_Multi(ST_Buffer(geom, :distance))::geometry(MultiPolygon, 25832) AS geom, <attrs>
FROM gis_data.layer_<quelle>;
```

Anschließend GiST-Index und Katalogeinträge — derselbe `TableCreator` wie beim Import, deshalb gehört er in `common` und nicht in `ingest`.

Weil das Speicher-CRS metrisch ist, bedeutet `ST_Buffer(geom, 50)` schlicht 50 Meter — kein `geography`-Cast, keine Transformation, keine breitengradabhängige Verzerrung. Das ist der Ertrag der CRS-Entscheidung aus dem Hauptteil.

`ParameterSpec` erlaubt ein datengetriebenes Formular: **eine** Frontend-Komponente für alle Algorithmen, wie die Processing-Toolbox in QGIS.

---
---

# Detailabschnitt D — Editier-Workflow (Phase 6)

## D.1 Snapping — Tiles taugen nicht als Referenz

Der naheliegende Weg wäre, gegen die bereits geladenen Vector Tiles zu snappen. Das ist falsch: `ST_AsMVTGeom` quantisiert Koordinaten auf ein Raster von 4096 Einheiten pro Kachel und vereinfacht Geometrien. Wer darauf snappt, erzeugt Stützpunkte, die *aussehen* wie ein Treffer, aber um Zentimeter bis Meter danebenliegen — und genau solche Lücken zwischen benachbarten Flächen fallen erst Jahre später auf.

**Lösung:** Sobald der Editiermodus aktiv wird, lädt das Frontend die Features des aktiven Layers im aktuellen Ausschnitt als **exaktes GeoJSON in voller Präzision**:

```
GET /api/layers/{id}/features?bbox=…&geometry=true&limit=2000
```

Snapping läuft dann rein clientseitig gegen diese Menge — kein Netzwerk-Roundtrip pro Mausbewegung. Überschreitet der Ausschnitt das Limit, wird Snapping deaktiviert und in der Statusleiste begründet ("Zu viele Objekte im Ausschnitt — bitte hineinzoomen"), statt still ungenau zu arbeiten. Für Snapping über Layergrenzen hinweg wird dieselbe Abfrage für die als Snap-Quelle markierten Layer wiederholt.

**Toleranz in Bildschirmpixeln, nicht in Metern.** Zwölf Pixel, über die aktuelle Kartenauflösung in Karteneinheiten umgerechnet. Eine feste Meterangabe macht Snapping beim Herauszoomen unbrauchbar und beim Hineinzoomen wirkungslos.

**Prioritäten:** Stützpunkt vor Kante vor Schnittpunkt. Das aktive Snap-Ziel bekommt einen sichtbaren Marker — ohne Rückmeldung weiß niemand, ob gerade gesnappt wurde oder nicht.

> **[Abweichung] Die Rangfolge ist Stützpunkt → Schnittpunkt → Kante.** Der Schnittpunkt
> steht in der Mitte, nicht am Ende: Er ist wie ein Stützpunkt ein Ort, den die Daten
> auszeichnen, während ein Kantenpunkt nur der ist, an dem der Zeiger zufällig stand. Nach
> Abstand zu sortieren wäre falsch — dabei rutscht der Cursor von einer Ecke auf die Linie
> daneben, die klassische Frustration mit Fangwerkzeugen.
>
> **Berechnete Fangpunkte werden auf neun Nachkommastellen gerundet, gefundene nicht.**
> Ein Stützpunkt wird unverändert durchgereicht — das ist der ganze Zweck. Ein Punkt auf
> einer Kante oder ein Schnittpunkt wird dagegen *gerechnet* und trägt die volle
> Genauigkeit eines `double`, rund fünfzehn Stellen, wo die Feature-API neun liefert. Das
> ist nicht nur erfundene Genauigkeit: terra-draw weist eine Geometrie mit mehr als neun
> Stellen rundweg zurück, und zwar stillschweigend. Ein auf eine Kante gezeichnetes Objekt
> ließ sich dadurch nicht wiederherstellen — Rückgängig leerte die Zeichenfläche endgültig,
> während der Zähler die Änderung als zurückgeholt auswies.
>
> **Punkte rasten über einen anderen Weg ein.** terra-draw bietet die Snapping-Option nur
> für Linien und Flächen an. Ein gesetzter Punkt wird deshalb nachträglich auf das Ziel
> gezogen, das der Marker anzeigte — nicht auf ein neu gesuchtes. Zwei Gründe: Der Punkt
> liegt zu diesem Zeitpunkt bereits in terra-draws Speicher und fände sich selbst mit
> Abstand null; und ein neu gesuchtes Ziel könnte ein anderes sein als das unter dem
> Marker, was den Marker zur Lüge machte. Eine Reichweitenprüfung verwirft eine veraltete
> Vorschau, etwa von einem Zeiger, der sich nie über die Karte bewegt hat.

## D.2 Undo/Redo über Immer-Patches

Statt ein Command-Pattern von Hand zu bauen: Zustand mit der `immer`-Middleware, und `produceWithPatches` liefert zu jeder Änderung automatisch die Patches **und** die inversen Patches. Undo ist dann `applyPatches(state, inversePatches)`, Redo `applyPatches(state, patches)`. Das ist erheblich weniger Code als handgeschriebene `apply`/`revert`-Paare und kann nicht auseinanderlaufen.

```
undoStack: { patches, inversePatches, label }[]
redoStack: { patches, inversePatches, label }[]
```

Das `label` ("Stützpunkt verschoben", "Fläche gelöscht") wandert in Tooltip und Verlaufsanzeige.

**Zusammenfassen ist Pflicht:** Beim Ziehen eines Stützpunkts entstehen hunderte Zwischenzustände. Ein Undo-Eintrag entsteht erst bei `dragend`, nicht bei jedem `mousemove` — sonst braucht der Nutzer 200 Klicks, um eine Bewegung zurückzunehmen.

Nach erfolgreichem Speichern werden beide Stapel geleert. Das hält die Semantik eindeutig: Undo wirkt auf ungespeicherte Änderungen, nie auf bereits Persistiertes.

> **[Abweichung] Das Zeichenwerkzeug muss dem Buffer folgen — und das war zunächst nicht
> verdrahtet.** terra-draw hält die Geometrien, die man anfasst; der Buffer hält, was
> gespeichert wird. Beide sind Kopien derselben Sache, und die Verbindung muss in *beide*
> Richtungen laufen. Undo setzt Patches auf den Buffer an, wovon das Zeichenwerkzeug
> nichts mitbekommt: Der Zähler fiel auf „keine Änderungen", während die zurückgenommene
> Fläche sichtbar stehen blieb. Umgekehrt passiert das Löschen mit der Entf-Taste im
> Zeichenwerkzeug, wovon der Buffer nichts mitbekam — das Objekt verschwand von der Karte,
> Speichern schrieb nichts, und nach dem Neuladen war es wieder da.
>
> Der Abgleich nach Undo baut die Zeichenfläche **aus dem Buffer neu auf**, statt Patches
> nachzuspielen: Ein Patch sagt, wie der Buffer sich geändert hat, nicht was die Karte nun
> zeigen soll; über eine lange Historie liefen beide auseinander. Ein Flag schaltet
> währenddessen die Gegenrichtung stumm, sonst legt der Abgleich die zurückgenommene
> Änderung sofort wieder an.
>
> **Was noch gezeichnet wird, gehört nicht in den Buffer.** Eine entstehende Fläche wächst
> mit jeder Zeigerbewegung, und terra-draw meldet jeden Zwischenstand. Aufgezeichnet ergaben
> drei Ecken acht Verlaufseinträge — acht Klicks auf Rückgängig für ein Dreieck, genau die
> Umständlichkeit, die dieser Abschnitt vermeiden will. Ein Objekt tritt einmal in den
> Buffer ein, wenn `finish` es für fertig erklärt.

## D.3 Edit-Buffer und Darstellung ungespeicherter Änderungen

```ts
type EditBuffer = {
  creates: Map<number, DraftFeature>  // temporäre negative fids: -1, -2, …
  updates: Map<number, DraftFeature>
  deletes: Set<number>
}
```

Temporäre `fid`s sind negativ — echte sind immer positiv, die Unterscheidung ist damit kostenlos und die Karte kann neue Objekte einfärben.

**Die Darstellung ist der knifflige Teil.** Geänderte Features existieren gleichzeitig in den (noch alten) Tiles und im Buffer. Ohne Gegenmaßnahme sieht man beide übereinander. Deshalb:

1. Ein zusätzlicher GeoJSON-Source-Layer über den Tile-Layern zeigt den Buffer-Inhalt in einem Bearbeitungsstil.
2. Alle betroffenen `fid`s werden im Tile-Layer ausgeblendet:
   `setFilter(['!', ['in', ['get','fid'], ['literal', [...dirtyFids]]]])`
3. Beim Speichern liefert die Antwort das Mapping temporäre → echte `fid` und die neue `data_version`. Danach: Buffer leeren, Filter zurücksetzen, Tile-URL über `setTiles` aktualisieren — in dieser Reihenfolge, sonst blitzt der alte Stand kurz auf.

## D.4 Attributformular aus `layer_field`

Das Formular wird zur Laufzeit erzeugt, es gibt keine layer-spezifischen Komponenten. Aus `layer_field.data_type` folgt Eingabefeld und Zod-Regel:

| `data_type` | Eingabe | Zod |
|---|---|---|
| `text` | `Input` / `Textarea` ab 255 Zeichen | `z.string().nullable()` |
| `integer`, `bigint` | `Input type=number`, Schrittweite 1 | `z.coerce.number().int().nullable()` |
| `double precision`, `numeric` | `Input type=number` | `z.coerce.number().nullable()` |
| `boolean` | `Switch` mit drittem Zustand für NULL | `z.boolean().nullable()` |
| `date`, `timestamptz` | `Popover` + `Calendar` | `z.coerce.date().nullable()` |
| `uuid` | `Input`, schreibgeschützt | — |

Beschriftet wird mit `source_name`, gesendet wird auf `column_name` — die UI zeigt nie den normalisierten SQL-Namen.

**NULL ist nicht der leere String.** Bei Textspalten müssen beide Zustände unterscheidbar bleiben, sonst werden aus fehlenden Werten stillschweigend leere. Deshalb bekommt jedes nullable Feld eine explizite Leeren-Aktion, und ein geleertes Feld wird als `null` gesendet, nicht als `""`.

## D.5 Geometrie-Validierung — nicht heimlich reparieren

Clientseitig nur das Offensichtliche: Polygon mindestens drei verschiedene Stützpunkte, Linie mindestens zwei. Alles Weitere prüft der Server, denn dort liegt die Wahrheit.

Beim Speichern läuft je Geometrie `ST_IsValid`. Bei einem Verstoß **wird nicht automatisch `ST_MakeValid` angewandt.** Das würde Daten stillschweigend verändern und kann aus einem Polygon eine GeometryCollection machen, die dann nicht mehr in die Spalte passt. Stattdessen:

1. Der Server antwortet mit `ST_IsValidReason` und der Fehlerkoordinate aus `ST_IsValidDetail`.
2. Das Frontend zoomt auf die Stelle und markiert sie.
3. "Automatisch reparieren" wird als **ausdrückliche Nutzeraktion** angeboten. Erst dann läuft `ST_MakeValid`, gefolgt von `ST_CollectionExtract` auf die Dimension der Zielspalte.
4. Liefert die Reparatur einen Typ, der nicht in die Spalte passt, bricht der Vorgang mit einer Meldung ab — lieber ein abgelehnter Speichervorgang als ein verfälschter Datensatz.

## D.6 Typkonflikte verhindern statt melden

Bei typisierten Layern (`MultiPolygon` usw.) sind in der Werkzeugleiste nur die passenden Zeichenwerkzeuge aktiv; die übrigen sind ausgegraut mit Begründung im Tooltip. Nur in Layern mit `geometry_type = 'GEOMETRY'` (siehe A.5) stehen alle drei zur Verfügung. Beim Speichern hebt `ST_Multi` Einzelgeometrien hoch, genau wie im Import — dieselbe Regel an beiden Stellen.

## D.7 Optimistisches Sperren ohne zusätzliche Spalte

Für Multi-User später wird Konflikterkennung gebraucht. Eine `version`-Spalte in jeder Layertabelle wäre unnötiger Ballast — PostgreSQL liefert das bereits über die Systemspalte **`xmin`**, die Transaktions-ID der letzten Änderung einer Zeile:

```sql
SELECT fid, xmin::text AS row_version, … FROM gis_data.layer_<hex> WHERE …
UPDATE gis_data.layer_<hex> SET … WHERE fid = :fid AND xmin = :rowVersion;
```

Betrifft das `UPDATE` null Zeilen, hat jemand anders zwischenzeitlich geschrieben → `409 Conflict` mit dem aktuellen Serverstand, damit die UI die Unterschiede zeigen kann.

Der Preis ist gering: `xmin` ist 32 Bit und wird bei `VACUUM FREEZE` zurückgesetzt — für Konflikterkennung innerhalb einer Bearbeitungssitzung völlig ausreichend. Der Gewinn: **kein Schemawechsel**, wenn Multi-User kommt. Deshalb wird `row_version` schon im MVP mitgelesen und mitgeschickt, obwohl im Single-User-Betrieb kein Konflikt entstehen kann.

## D.8 Ungespeicherte Änderungen

Ist der Buffer nicht leer, wird beim Layerwechsel, Projektwechsel und Schließen des Tabs (`beforeunload`) nachgefragt — shadcn `AlertDialog` mit den drei Optionen Speichern, Verwerfen, Abbrechen. Der Zähler ungespeicherter Änderungen steht dauerhaft in der Werkzeugleiste, damit der Zustand nie überrascht.

---
---

# Detailabschnitt E — Projektverwaltung (Phase 1)

Ein Projekt ist hier das, was in QGIS die `.qgz`-Datei ist: die Klammer um eine Menge von Layern, deren Reihenfolge, Sichtbarkeit, Darstellung und den zuletzt betrachteten Ausschnitt. Alles Weitere im Plan hängt daran — deshalb steht diese Phase vor dem Import.

## E.1 Startbildschirm

Route `/` zeigt den Projektbrowser, sortiert nach zuletzt geöffnet:

```
┌───────────────────────────────────────────────────────────────┐
│  hgis                                        [+ Neues Projekt] │
├───────────────────────────────────────────────────────────────┤
│  [Suche…                                                     ] │
│                                                               │
│  ▌ Kataster Musterstadt                          vor 2 Stunden│
│  ▌ 4 Layer · 128.400 Objekte · EPSG:25832                 [⋯] │
│                                                               │
│  ▌ Leitungsnetz Nord                                  gestern │
│  ▌ 7 Layer · 12.900 Objekte · EPSG:25832                  [⋯] │
│                                                               │
│  ▌ Testdaten                                       12.03.2026 │
│  ▌ leer · EPSG:25832                                      [⋯] │
└───────────────────────────────────────────────────────────────┘
```

Das `[⋯]`-Menü (shadcn `dropdown-menu`) enthält Öffnen, Umbenennen, Duplizieren, Löschen.

**Kein Kartenvorschaubild.** Ein Thumbnail zu erzeugen bräuchte serverseitiges Rendering, das dieser Stack nicht hat, und wäre für den Nutzen zu teuer. Stattdessen bekommt jedes Projekt einen aus seiner UUID abgeleiteten Farbbalken — Projekte bleiben visuell unterscheidbar, ohne dass ein Renderer nötig wird.

**Leerer Zustand:** Existiert noch kein Projekt, erscheint statt einer leeren Liste direkt die Anlege-Maske mit einer kurzen Erklärung. Ein leerer Startbildschirm ohne Führung ist der schlechteste erste Eindruck, den eine solche App machen kann.

## E.2 Projekt anlegen — das CRS ist eine Einbahnstraße

Dialog mit Name (Pflicht), Beschreibung (optional), CRS (durchsuchbares `select`, vorbelegt mit EPSG:25832) und Hintergrundkarte.

**Das CRS lässt sich nach dem Anlegen nicht mehr ändern**, und der Dialog sagt das ausdrücklich. Ein nachträglicher Wechsel müsste jede Layertabelle des Projekts umschreiben:

```sql
ALTER TABLE gis_data.layer_<hex>
  ALTER COLUMN geom TYPE geometry(MultiPolygon, <neu>)
  USING ST_Transform(geom, <neu>);
```

Das ist machbar, aber es schreibt jede Zeile neu, verwirft die Indizes und ist bei großen Layern eine Operation von Minuten bis Stunden. Als bewusst gewählte Roadmap-Funktion "Projekt umprojizieren" ist das sinnvoll — als stille Nebenwirkung einer Auswahlliste wäre es eine Falle. Deshalb: beim Anlegen entscheiden, danach gesperrt.

## E.3 Ansichtszustand persistieren

Wie in QGIS soll ein Projekt dort aufgehen, wo man es verlassen hat. `center` und `zoom` werden bei `moveend` **entprellt um zwei Sekunden** per `PATCH` gespeichert — nicht bei jeder Bewegung, sonst schreibt die Anwendung im Sekundentakt.

Sichtbarkeit und `z_index` der Layer liegen ohnehin schon in der `layer`-Tabelle. Genau deshalb werden sie serverseitig gehalten und nicht nur im Store: Sie sind Teil des Projektzustands, nicht der Sitzung. Wer ein Projekt schließt und wieder öffnet, findet seinen Arbeitsstand vor.

`last_opened_at` wird beim Laden des Projekts gesetzt und treibt die Sortierung im Browser.

## E.4 Löschen — die einzige wirklich gefährliche Operation

Ein Projekt zu löschen bedeutet, alle zugehörigen Tabellen in `gis_data` **physisch** zu droppen. `ON DELETE CASCADE` in `gis_meta` genügt nicht: Es räumt die Katalogzeilen ab und lässt die Nutzdaten als Waisen zurück, die niemand mehr zuordnen kann.

`ProjectDeletionService`, alles in **einer** Transaktion — DDL ist in PostgreSQL transaktional, ein Fehler in der Mitte rollt auch die bereits erfolgten Drops zurück:

1. Alle `layer.table_name` des Projekts einsammeln
2. Je Tabelle `DROP TABLE IF EXISTS gis_data.<name>`
3. Katalogzeilen löschen (Kaskade über die Fremdschlüssel)

In der UI ein `AlertDialog`, der Layeranzahl und Objektzahl konkret nennt. Enthält das Projekt Layer, muss der Projektname zur Bestätigung abgetippt werden; bei einem leeren Projekt genügt eine einfache Rückfrage. Die Hürde soll dort stehen, wo tatsächlich etwas verloren gehen kann — nicht überall.

## E.5 Duplizieren

Der QGIS-Reflex vor einem Experiment ist, die Projektdatei zu kopieren. Das Äquivalent hier:

```sql
CREATE TABLE gis_data.layer_<neu> (LIKE gis_data.layer_<alt> INCLUDING ALL);
INSERT INTO gis_data.layer_<neu> SELECT * FROM gis_data.layer_<alt>;
```

`INCLUDING ALL` übernimmt Indizes und Defaults mit. Anschließend werden `layer`- und `layer_field`-Zeilen mit neuen IDs kopiert. Weil das bei großen Projekten dauert, läuft es über die Job-Infrastruktur aus C.4 — derselbe Fortschrittsbalken wie beim Import, ohne zusätzlichen Code.

## E.6 Routing mit TanStack Router

Passt zur TanStack-First-Vorgabe und bringt typsichere Routen und Suchparameter mit:

```
/                        Projektbrowser
/projects/$projectId     Arbeitsbereich
    ?layer=<uuid>        aktiver Layer
    ?table=open          Attributpanel ausgeklappt
```

Aktiver Layer und Panelzustand gehören **in die URL**, nicht nur in den Store: Damit ist ein Arbeitsstand teilbar und übersteht einen Reload. TanStack Router validiert diese Suchparameter typisiert, sodass ein manipulierter Link keinen kaputten Zustand erzeugt.

Der Route-Loader lädt Projekt und Layerliste über TanStack Query vor, damit der Arbeitsbereich nicht mit leeren Panels aufblitzt. Eine unbekannte Projekt-ID leitet auf `/` um und meldet das über `sonner`, statt eine leere Seite zu zeigen.

## E.7 Anknüpfung an die Roadmap

Der Projektexport als GeoPackage — Layer, Attribute und Styles in einer Datei — wäre das echte Gegenstück zur `.qgz` und gehört in die Export-Phase der Roadmap. Das Datenmodell ist darauf vorbereitet, weil Styles bereits am Layer hängen und nicht im Frontend liegen.

---

## Verifikation

> **Stand:** 34 Backend-Testklassen, 146 Frontend-Tests in 16 Dateien. Die unten
> geforderten Prüfungen existieren alle, teils in anderer Form als geplant.
>
> **Was sich als Prinzip bewährt hat:** Ein Test, von dem niemand gesehen hat, wie er rot
> wird, ist keiner. Bei den heikelsten Stellen wurde die Gegenprobe gemacht — Fix
> zurückgedreht, Tests mussten fehlschlagen, dann wiederhergestellt. Das hat mehr als
> einmal gezeigt, dass ein Test die Sache gar nicht berührte, die er absichern sollte.
>
> **Und was Tests nicht leisten:** Drei der teuersten Fehler dieser Umsetzung waren am
> laufenden System sichtbar und in jeder Testsuite unsichtbar — ein Layer, den MapLibre
> wegen eines undefinierten Paint-Werts komplett verwarf; eine Feld-UUID, die im Panel
> statt des Feldnamens stand; eine Menübreite, die jeden Eintrag umbrach. Alles drei
> fällt beim ersten Bedienen auf und in keinem Unit-Test.

**Manuell, Ende zu Ende:**
1. `docker compose up -d` → `docker compose exec db psql -U hgis -c "SELECT postgis_version();"`
2. Backend `./mvnw spring-boot:run`, Frontend `npm run dev`
3. Startseite zeigt den leeren Projektbrowser mit Anlege-Maske → Projekt anlegen → Arbeitsbereich öffnet sich, URL lautet `/projects/<uuid>`
4. Testdaten: Verwaltungsgrenzen aus dem NUTS-/GADM-Datensatz oder ein OSM-Extrakt als Shapefile — hinreichend groß, um Tile-Performance realistisch zu prüfen
5. Import über die UI, Job läuft auf `SUCCEEDED`
6. `curl -o t.mvt "http://localhost:8080/api/layers/<id>/tiles/12/2133/1367.mvt"` → Datei ist nicht leer
7. Karte zeigt den Layer; Klick liefert Identify; Attributtabelle filtert und sortiert
8. Polygon zeichnen, speichern, Seite neu laden → Geometrie ist persistent
9. Karte verschieben, zurück zur Projektliste, Projekt erneut öffnen → Ausschnitt und Layer-Sichtbarkeiten stehen wie zuvor
10. Projekt löschen → `docker compose exec db psql -U hgis -c "\dt gis_data.*"` zeigt keine verwaisten Tabellen

**Automatisiert:**
- Unit: `SqlIdentifier` (Injection-Versuche, Kollisionen mit `fid`/`geom`, Kürzung auf 63 Zeichen), `TypeMapper`, Filterparser (nur erlaubte Operatoren, unbekannte Felder werden abgewiesen), `styleToMapLibre` (Snapshot-Tests je Renderer-Typ)
- **Achsenreihenfolge (A.4):** bekannter Punkt in EPSG:4326 → Import → Rücklesen → Koordinaten müssen identisch sein. Dieser Test verhindert die stillste aller Fehlerklassen
- **Kodierung (A.3):** je ein Shapefile mit `.cpg`, mit LDID-Byte und ganz ohne Angabe, jeweils mit Umlauten im Attributwert → nach dem Import muss `Müllerstraße` exakt so in der Datenbank stehen
- **CRS-Plausibilität (A.7):** GeoJSON mit UTM-Koordinaten, aber ohne CRS-Angabe → Import muss abbrechen statt zu schreiben
- Integration mit Testcontainers-PostGIS: Shapefile importieren → Tabelle und Katalog prüfen → Tile abrufen und mit einem MVT-Parser dekodieren → Edit-Batch schreiben → `data_version` und `feature_count` verifizieren
- **Rollback (A.8):** Import mit absichtlich fehlerhaftem Feature → Tabelle darf nicht zurückbleiben, Job steht auf `FAILED`
- **Projektlöschung (E.4):** Projekt mit drei Layern löschen → weder Katalogzeilen noch Tabellen in `gis_data` dürfen zurückbleiben; ein Fehler beim zweiten Drop muss auch den ersten zurückrollen
- **Snapping-Präzision (D.1):** gesnappter Stützpunkt muss **exakt** mit der Zielkoordinate übereinstimmen, nicht nur auf sechs Nachkommastellen — dieser Test entlarvt ein versehentliches Snapping gegen Tile-Geometrien
- **Validierung (D.5):** Polygon mit Selbstüberschneidung speichern → `409` mit Fehlerkoordinate, Datenbank bleibt unverändert; danach mit ausdrücklicher Reparatur → gültige Geometrie im erlaubten Typ
- **Optimistisches Sperren (D.7):** zwei konkurrierende Updates auf dieselbe `fid` → das zweite muss `409` liefern, nicht überschreiben
- Performance-Check: `EXPLAIN ANALYZE` der Tile-Query muss einen Index Scan zeigen, keinen Seq Scan

**Explizit nicht im MVP:** Authentifizierung, Styling-UI, Geoprocessing, Export, Rasterdaten, OGC-Dienste.
