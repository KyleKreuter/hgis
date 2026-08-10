# API Contract — Phase 2 and 3

Binding for all four parallel tracks. If something here turns out to be wrong, raise it
instead of quietly deviating: every track builds against these shapes.

Already in place on `main` and **not to be modified by any track**:

- `catalog/Layer`, `catalog/LayerField` and their repositories
- `common/SqlIdentifier` — the only code allowed to put an identifier into SQL
- `ingest/spi/*` — the reader contract between track A and track B
- Flyway `V1__catalog.sql` — **the schema already covers phases 2 and 3. No track adds
  a migration.** If you believe you need one, stop and report it.

---

## 1. Tiles

```
GET /api/layers/{layerId}/tiles/{z}/{x}/{y}.mvt?v={dataVersion}.{styleVersion}
```

- Response `200` with `Content-Type: application/vnd.mapbox-vector-tile`, body is the
  raw `byte[]`. An empty tile is `204 No Content`, not a 404.
- `Cache-Control: public, max-age=31536000, immutable` — the URL carries the version, so
  a changed layer produces a different URL.
- `ETag` derived from `layerId + dataVersion + styleVersion`.
- The MVT layer name inside the tile is the string `"layer"` (constant, not the display
  name — the client addresses its source by id).
- Feature id is `fid`.
- Attributes in the tile: none in phase 3 beyond `fid`. The style-driven attribute
  selection from plan section C.2 comes later.

**The query must transform the tile envelope into the storage CRS**, never the
geometries into 3857 — otherwise the GiST index is unusable and every tile becomes a
sequential scan:

```sql
WITH bounds AS (
  SELECT ST_TileEnvelope(:z, :x, :y) AS merc,
         ST_Transform(ST_TileEnvelope(:z, :x, :y), :srid) AS native
)
SELECT ST_AsMVT(tile, 'layer', 4096, 'geom', 'fid')
FROM (
  SELECT l.fid,
         ST_AsMVTGeom(ST_Transform(l.geom, 3857), b.merc, 4096, 64, true) AS geom
  FROM gis_data."<table>" l, bounds b
  WHERE l.geom && b.native          -- index-friendly, do not change
) AS tile
WHERE tile.geom IS NOT NULL;
```

---

## 2. Layers

```
GET    /api/projects/{projectId}/layers   -> LayerSummary[]
GET    /api/layers/{layerId}              -> LayerDetail
PATCH  /api/layers/{layerId}              -> LayerDetail
DELETE /api/layers/{layerId}              -> 204
```

```jsonc
// LayerSummary
{
  "id": "uuid",
  "name": "Gebäude",
  "geometryType": "MULTIPOLYGON",     // MULTIPOINT | MULTILINESTRING | MULTIPOLYGON | GEOMETRY
  "srid": 25832,
  "featureCount": 128447,
  "visible": true,
  "zIndex": 0,
  "minZoom": 0,
  "maxZoom": 22,
  "dataVersion": 3,
  "styleVersion": 1,
  "extent": [9.9, 53.4, 10.1, 53.6]    // [minLng, minLat, maxLng, maxLat] in 4326, or null
}

// LayerDetail = LayerSummary plus:
{
  "fields": [
    { "id": "uuid", "sourceName": "Gebäudehöhe", "columnName": "gebaeudehoehe", "dataType": "double precision" }
  ],
  "style": null,                       // reserved, phase 7
  "createdAt": "2026-08-10T15:00:00Z",
  "updatedAt": "2026-08-10T15:00:00Z"
}

// PATCH body, every field optional
{ "name": "…", "visible": true, "zIndex": 2, "minZoom": 0, "maxZoom": 22 }
```

The client builds the tile URL itself from `id`, `dataVersion` and `styleVersion`.

---

## 3. Import and jobs

```
POST /api/projects/{projectId}/imports   multipart/form-data -> 202 Accepted, Job
GET  /api/jobs/{jobId}                                       -> Job
```

Multipart parts:

| Part | Required | Meaning |
|---|---|---|
| `file` | yes | ZIP with a Shapefile set, or `.gpkg`, `.geojson`, `.json`, `.csv` |
| `name` | no | layer name; defaults to the file name without extension |
| `srid` | no | overrides the detected source CRS |
| `charset` | no | overrides the detected encoding |

```jsonc
// Job
{
  "id": "uuid",
  "type": "IMPORT",                    // IMPORT | PROCESSING | DUPLICATE
  "status": "RUNNING",                 // PENDING | RUNNING | SUCCEEDED | FAILED
  "filename": "gebaeude.zip",
  "processedCount": 4000,
  "totalCount": 128447,                // null while unknown
  "skippedCount": 3,
  "outputLayerId": "uuid",             // null until a layer exists
  "message": "…",                      // failure reason, or a warning on success
  "startedAt": "…", "finishedAt": null, "createdAt": "…"
}
```

The client polls `GET /api/jobs/{jobId}` roughly every second while status is
`PENDING` or `RUNNING`.

---

## 4. Errors

Every error is RFC 7807, produced by the existing `common/ProblemDetailAdvice`:

```jsonc
{ "title": "Ungültige Anfrage", "status": 400, "detail": "…",
  "instance": "/api/…", "errors": { "field": "message" } }   // errors only on validation
```

Throw `NotFoundException` (404) or `BadRequestException` (400) from `common`; do not
build responses by hand.

---

## 5. Rules that cross track boundaries

1. **Geometry columns always use the multi variant** (`geometry(MultiPolygon, <srid>)`).
   Single geometries are promoted with `ST_Multi` on insert. Only genuinely mixed
   sources get `geometry(Geometry, <srid>)` and `geometryType = "GEOMETRY"`.
2. **Reprojection happens in PostGIS**, not in Java: insert with
   `ST_Transform(ST_GeomFromWKB(?, :sourceSrid), :targetSrid)`.
3. **Axis order** is already forced to longitude-first in `HgisBackendApplication`.
   Do not touch it, and do not decode CRS with a different setting.
4. **Every identifier goes through `SqlIdentifier`.** Values always use bind parameters.
5. **`data_version` is bumped on every write** to a payload table.
6. Metadata geometry (`extent`) is EPSG:4326; payload geometry uses the project CRS.

---

## 6. Track boundaries

| Track | Owns | Must not touch |
|---|---|---|
| A Reader | `ingest/reader/**`, its tests | `jobs/`, `tiles/`, frontend |
| B Writer + Jobs | `jobs/**`, `common/TableCreator*`, `ingest/ImportService*` | `ingest/reader/`, `tiles/`, frontend |
| C Tiles | `tiles/**`, `catalog/LayerController*` | `ingest/`, `jobs/`, frontend |
| D Map | `frontend/src/map/**`, `frontend/src/api/layers.ts`, `package.json` | all backend code |

Shared and off-limits for everyone: `V1__catalog.sql`, `SqlIdentifier`, `ingest/spi/`,
`Layer`, `LayerField`, `application.yml`, `pom.xml` (all needed dependencies are present).
