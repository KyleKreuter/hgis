-- Mirrors docker/initdb/01-init.sql: creates the extension and schemas that Flyway's
-- V1__catalog.sql assumes already exist. In production these come from the postgis
-- image's docker-entrypoint-initdb.d mechanism; Testcontainers' withInitScript runs
-- this over plain JDBC instead, so it is kept as a separate, self-contained copy here.
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE SCHEMA IF NOT EXISTS gis_meta;
CREATE SCHEMA IF NOT EXISTS gis_data;
