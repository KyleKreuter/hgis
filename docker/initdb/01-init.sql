-- Runs once when the data volume is empty.
-- Creates the PostGIS extension and the two schemas the application relies on.
--
-- gis_meta  catalog: projects, layers, fields, jobs. Owned by Flyway.
-- gis_data  payload: one table per layer, created at runtime via DDL. Never touched by Flyway.

CREATE EXTENSION IF NOT EXISTS postgis;

CREATE SCHEMA IF NOT EXISTS gis_meta;
CREATE SCHEMA IF NOT EXISTS gis_data;

COMMENT ON SCHEMA gis_meta IS 'Catalog. Static schema, migrated by Flyway.';
COMMENT ON SCHEMA gis_data IS 'Layer payload tables. Created at runtime, never migrated.';
