-- Runs once against the Testcontainers-managed database, before Flyway and Hibernate
-- touch it. Mirrors docker/initdb/01-init.sql for local development: the postgis
-- extension and the gis_data schema are infrastructure that Flyway deliberately does
-- not own (see V1__catalog.sql), so tests have to provide them themselves.

CREATE EXTENSION IF NOT EXISTS postgis;

CREATE SCHEMA IF NOT EXISTS gis_data;
