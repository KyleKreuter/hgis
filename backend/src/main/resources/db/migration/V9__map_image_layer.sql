-- Lets a layer be a map image (WMS) instead of a vector layer with a payload table
-- (plan "Kartenbilder aus dem Geoportal Hamburg", stage 1). V8 is the last vector-only
-- migration; this is the next free number.
--
-- Every existing row is a vector layer today, so 'kind' defaults to 'VECTOR' and every
-- column it touches stays exactly as strict as before for that kind -- see the CHECK
-- constraints below, which read as "nothing changes for kind = 'VECTOR'" plus "kind =
-- 'WMS' has no table at all".

ALTER TABLE layer
    ADD COLUMN kind text NOT NULL DEFAULT 'VECTOR',
    ADD CONSTRAINT layer_kind CHECK (kind IN ('VECTOR', 'WMS'));

-- A map image has no payload table in gis_data: nothing is downloaded, so there is
-- nothing to name, type or project. These three were NOT NULL since V1; a WMS layer
-- needs them nullable, and layer_kind_columns below is what still keeps a vector layer
-- from ever storing a NULL in any of them.
ALTER TABLE layer
    ALTER COLUMN table_name    DROP NOT NULL,
    ALTER COLUMN geometry_type DROP NOT NULL,
    ALTER COLUMN srid          DROP NOT NULL;

-- V1's checks only ever had to consider a NOT NULL column; widen both to also accept
-- the WMS case's NULL rather than rejecting it outright.
ALTER TABLE layer DROP CONSTRAINT layer_table_name_fmt;
ALTER TABLE layer ADD CONSTRAINT layer_table_name_fmt CHECK (
    table_name IS NULL OR table_name ~ '^layer_[0-9a-f]{32}$');

ALTER TABLE layer DROP CONSTRAINT layer_geometry_type;
ALTER TABLE layer ADD CONSTRAINT layer_geometry_type CHECK (
    geometry_type IS NULL OR geometry_type IN (
        'MULTIPOINT', 'MULTILINESTRING', 'MULTIPOLYGON', 'GEOMETRY'));

-- Splits the two kinds cleanly: a vector layer has its table identity fully set, a map
-- image has none of it -- never a half-filled row that would let backend code read one
-- of the three and silently get NULL instead of a clear "this layer has no table" it
-- can act on (see Layer#requireVector).
ALTER TABLE layer ADD CONSTRAINT layer_kind_columns CHECK (
    (kind = 'VECTOR' AND table_name IS NOT NULL AND geometry_type IS NOT NULL AND srid IS NOT NULL) OR
    (kind = 'WMS'    AND table_name IS NULL     AND geometry_type IS NULL     AND srid IS NULL));

-- WMS service the layer draws from. wms_layers is a native text[] rather than jsonb:
-- it is nothing but an ordered list of scalar names (the plan's own contract: "the
-- order the service draws in, bottom first"), never queried by any single entry, and
-- an array keeps that order and quotes each name PostgreSQL's own way -- jsonb would
-- buy nothing here but a second way to represent the same list.
ALTER TABLE layer
    ADD COLUMN wms_service_url  text,
    ADD COLUMN wms_layers       text[],
    ADD COLUMN wms_image_format text,
    ADD COLUMN wms_legend_url   text,
    ADD COLUMN wms_queryable    boolean;

-- wms_legend_url is excluded from the "WMS must have" side: plan measurement found only
-- 101 of about 150 sampled layers publish one, so a legend-less service is ordinary, not
-- an error. Every WMS column is required NULL for a vector layer, same reasoning as
-- layer_kind_columns above.
--
-- cardinality(), not array_length(wms_layers, 1): for a zero-length array the latter
-- returns SQL NULL rather than 0 (there is no dimension to report), and a CHECK
-- constraint treats NULL as satisfied, not failed -- '{}'::text[] would silently pass
-- the ">= 1" test array_length() alone gives. cardinality() answers 0 for an empty
-- array, so the comparison stays a real boolean and the constraint means what it says.
ALTER TABLE layer ADD CONSTRAINT layer_wms_fields CHECK (
    (kind = 'WMS' AND wms_service_url IS NOT NULL
                  AND wms_layers IS NOT NULL AND cardinality(wms_layers) >= 1
                  AND wms_image_format IS NOT NULL
                  AND wms_queryable IS NOT NULL) OR
    (kind = 'VECTOR' AND wms_service_url IS NULL AND wms_layers IS NULL
                     AND wms_image_format IS NULL AND wms_legend_url IS NULL
                     AND wms_queryable IS NULL));
