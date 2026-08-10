-- Runs once against the Testcontainers database, before Flyway and Hibernate touch it.
--
-- Mirrors docker/initdb/01-init.sql: the postgis extension and the gis_data schema are
-- infrastructure that Flyway deliberately does not own (see V1__catalog.sql), so tests
-- have to provide them themselves. Without this, every catalog table fails to create,
-- because they all carry geometry columns.

CREATE EXTENSION IF NOT EXISTS postgis;

CREATE SCHEMA IF NOT EXISTS gis_data;
