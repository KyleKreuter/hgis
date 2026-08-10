-- Runs once against the Testcontainers database, before Flyway and Hibernate touch it.
--
-- Mirrors docker/initdb/01-init.sql: the postgis extension and both schemas are
-- infrastructure that Flyway deliberately does not own (see V1__catalog.sql), so tests
-- have to provide them themselves. Without this, every catalog table fails to create,
-- because they all carry geometry columns.
--
-- gis_meta would also be created by Flyway itself (create-schemas defaults to true),
-- but it is declared here anyway so this file stays a faithful mirror of the
-- production init script rather than silently depending on a Flyway default.

CREATE EXTENSION IF NOT EXISTS postgis;

CREATE SCHEMA IF NOT EXISTS gis_meta;
CREATE SCHEMA IF NOT EXISTS gis_data;
