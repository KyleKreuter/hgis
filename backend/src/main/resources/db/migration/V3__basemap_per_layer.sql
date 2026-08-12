-- Lets a layer override the project's basemap, and adds an opacity for the basemap
-- itself -- distinct from a layer's own symbology opacity, which already lives in
-- layer.style.
--
-- layer.basemap and layer.basemap_opacity get no NOT NULL and no default, on purpose:
-- this migration runs against layers that already exist, and NULL here means "follow
-- the project's basemap" -- a state distinct from any concrete value. A default could
-- not express that: "osm because nothing was ever chosen" and "osm because it was
-- chosen" must behave differently once the project's basemap changes. No backfill is
-- needed -- every existing layer already means "follow the project".
--
-- project.basemap_opacity gets NOT NULL DEFAULT 1: a project always has a basemap, so
-- it always has an opacity for it. Existing projects get full opacity, exactly how
-- they render today.

ALTER TABLE layer   ADD COLUMN basemap         text;
ALTER TABLE layer   ADD COLUMN basemap_opacity double precision;
ALTER TABLE project ADD COLUMN basemap_opacity double precision NOT NULL DEFAULT 1;

ALTER TABLE layer   ADD CONSTRAINT layer_basemap_length
    CHECK (basemap IS NULL OR length(basemap) <= 64);
ALTER TABLE layer   ADD CONSTRAINT layer_basemap_opacity_range
    CHECK (basemap_opacity IS NULL OR (basemap_opacity >= 0 AND basemap_opacity <= 1));
ALTER TABLE project ADD CONSTRAINT project_basemap_opacity_range
    CHECK (basemap_opacity >= 0 AND basemap_opacity <= 1);
