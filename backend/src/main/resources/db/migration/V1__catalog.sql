-- Catalog schema for hgis.
--
-- Scope: this migration owns gis_meta and nothing else. Layer payload tables live in
-- gis_data, are created at runtime via DDL and must never appear in a migration --
-- they are user data, not schema.
--
-- CRS convention, and the one thing to get right here:
--   * Payload geometries (gis_data.layer_*) use the project's storage CRS, EPSG:25832
--     by default. Metric, so lengths, areas and buffers are correct without casting.
--   * Metadata geometries in THIS schema (extent, center) are always EPSG:4326.
--     They exist to drive the client: MapLibre's fitBounds and setCenter expect
--     lng/lat. Pinning them to the storage CRS would break the moment a project uses
--     a different one, and would force a transform on every read.

CREATE TABLE project (
    id             uuid PRIMARY KEY,
    name           text        NOT NULL,
    description    text,

    -- Storage CRS for all payload tables of this project. Immutable after creation:
    -- changing it would have to rewrite every layer table and drop every index.
    srid           integer     NOT NULL DEFAULT 25832,

    -- Last viewed map position, restored when the project is reopened.
    center         geometry(Point, 4326),
    zoom           double precision,
    basemap        text        NOT NULL DEFAULT 'osm',

    extent         geometry(Polygon, 4326),

    last_opened_at timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT project_name_not_blank CHECK (length(btrim(name)) > 0),
    CONSTRAINT project_zoom_range     CHECK (zoom IS NULL OR (zoom >= 0 AND zoom <= 24))
);

-- Drives the default ordering of the project browser.
CREATE INDEX project_last_opened_idx ON project (last_opened_at DESC NULLS LAST);


CREATE TABLE layer (
    id             uuid PRIMARY KEY,
    project_id     uuid        NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    name           text        NOT NULL,

    -- Physical table in gis_data, always 'layer_' + hex of this row's id.
    -- Never derived from user input.
    table_name     text        NOT NULL UNIQUE,

    -- Always the multi variant, or GEOMETRY for genuinely mixed sources.
    -- Single geometries are promoted with ST_Multi on insert.
    geometry_type  text        NOT NULL,
    srid           integer     NOT NULL,

    feature_count  bigint      NOT NULL DEFAULT 0,

    -- Two independent tile cache busters, both part of the tile URL:
    --   data_version  bumped by every write to the payload table
    --   style_version bumped only when a style change alters which attributes the
    --                 tiles must carry. A pure colour change must NOT invalidate tiles.
    data_version   bigint      NOT NULL DEFAULT 1,
    style_version  bigint      NOT NULL DEFAULT 1,

    visible        boolean     NOT NULL DEFAULT true,
    z_index        integer     NOT NULL DEFAULT 0,
    min_zoom       integer     NOT NULL DEFAULT 0,
    max_zoom       integer     NOT NULL DEFAULT 22,

    style          jsonb,
    extent         geometry(Polygon, 4326),

    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT layer_name_not_blank CHECK (length(btrim(name)) > 0),
    CONSTRAINT layer_geometry_type  CHECK (geometry_type IN (
        'MULTIPOINT', 'MULTILINESTRING', 'MULTIPOLYGON', 'GEOMETRY')),
    CONSTRAINT layer_zoom_range     CHECK (min_zoom >= 0 AND max_zoom <= 24 AND min_zoom <= max_zoom),
    CONSTRAINT layer_table_name_fmt CHECK (table_name ~ '^layer_[0-9a-f]{32}$')
);

CREATE INDEX layer_project_idx ON layer (project_id, z_index);


-- Maps a source attribute name to the sanitised SQL column name.
-- This table is the only place allowed to resolve an identifier for use in SQL:
-- the client always sends source_name or a field id, never a column name.
CREATE TABLE layer_field (
    id          uuid PRIMARY KEY,
    layer_id    uuid    NOT NULL REFERENCES layer (id) ON DELETE CASCADE,
    source_name text    NOT NULL,
    column_name text    NOT NULL,
    data_type   text    NOT NULL,
    ordinal     integer NOT NULL,

    CONSTRAINT layer_field_unique_column UNIQUE (layer_id, column_name),
    -- Belt and braces: even though normalisation guarantees this, a violated
    -- constraint is far cheaper than an injected identifier.
    CONSTRAINT layer_field_column_fmt CHECK (column_name ~ '^[a-z_][a-z0-9_]{0,62}$')
);

CREATE INDEX layer_field_layer_idx ON layer_field (layer_id, ordinal);


-- One table for every long running operation. Import, geoprocessing and project
-- duplication share status handling, progress reporting, the polling endpoint and
-- the janitor that cleans up after a crash.
CREATE TABLE job (
    id              uuid PRIMARY KEY,
    project_id      uuid        REFERENCES project (id) ON DELETE CASCADE,
    type            text        NOT NULL,
    status          text        NOT NULL DEFAULT 'PENDING',

    -- Import payload
    filename        text,
    -- Processing payload
    algorithm       text,
    parameters      jsonb,

    -- Layer produced by this job, if any. Set to NULL rather than cascading, so a
    -- failed job stays readable after its half built layer was dropped.
    output_layer_id uuid        REFERENCES layer (id) ON DELETE SET NULL,

    processed_count bigint      NOT NULL DEFAULT 0,
    total_count     bigint,
    skipped_count   bigint      NOT NULL DEFAULT 0,
    message         text,

    started_at      timestamptz,
    finished_at     timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT job_type   CHECK (type   IN ('IMPORT', 'PROCESSING', 'DUPLICATE')),
    CONSTRAINT job_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'))
);

CREATE INDEX job_project_idx ON job (project_id, created_at DESC);
-- Used by the janitor on startup: any job still RUNNING after a restart is orphaned.
CREATE INDEX job_status_idx  ON job (status) WHERE status IN ('PENDING', 'RUNNING');


-- Keep updated_at honest regardless of how a row is written. The catalog is touched
-- both by JPA and by plain JdbcTemplate statements (data_version bumps), so relying
-- on Hibernate's @UpdateTimestamp alone would leave gaps.
CREATE OR REPLACE FUNCTION gis_meta.touch_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER project_touch_updated_at BEFORE UPDATE ON project
    FOR EACH ROW EXECUTE FUNCTION gis_meta.touch_updated_at();
CREATE TRIGGER layer_touch_updated_at BEFORE UPDATE ON layer
    FOR EACH ROW EXECUTE FUNCTION gis_meta.touch_updated_at();
CREATE TRIGGER job_touch_updated_at BEFORE UPDATE ON job
    FOR EACH ROW EXECUTE FUNCTION gis_meta.touch_updated_at();
