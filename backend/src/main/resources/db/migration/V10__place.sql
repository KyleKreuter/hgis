-- Place search (CONTRACT.md "API-Contract: Ortssuche"): a local index of Hamburg's own
-- streets and districts, refreshed from the city's WFS (PlaceRefreshService). Everything
-- outside Hamburg -- and every Photon hit in general -- is looked up live and never lands
-- in this table; source therefore only ever admits 'hamburg', by design (CONTRACT.md:
-- "Photon-Treffer werden nie gespeichert. Sie kommen live und sind fremde Daten mit
-- eigener Lizenz.").
--
-- One row per street *segment*, not per street: Hamburg's WFS answers dog:Strassen with
-- one member per street name, but that member can repeat dog:postleitzahl/dog:postOrtsteil
-- for every postal-code area the street crosses (measured live: 331 of 9534 members do,
-- "Ahrensburger Strasse" among them, split between Tonndorf/22045 and Wandsbek/22041). Each
-- such area becomes its own row here with the same name -- see PlaceGmlReader -- which is
-- what CONTRACT.md's "eine Strasse kann mehrere Abschnitte und Postleitzahlen haben" asks
-- for: a search has to be able to return both areas as distinct, individually flyable hits.

-- Schema-qualified rather than left to search_path: Flyway scopes the migration session's
-- search_path to gis_meta alone (application.yml's flyway.schemas), so an unqualified
-- CREATE EXTENSION would install into gis_meta and an unqualified regdictionary literal
-- below would then fail to resolve outside of that same scoped session (measured: "text
-- search dictionary unaccent does not exist" the moment anything outside Flyway's own
-- search_path, e.g. a plain application query, evaluates the cast). public is where every
-- other consumer of this database can find it without special configuration.
CREATE EXTENSION IF NOT EXISTS pg_trgm SCHEMA public;
CREATE EXTENSION IF NOT EXISTS unaccent SCHEMA public;

-- unaccent() is STABLE, not IMMUTABLE -- it reads a text search dictionary that could in
-- principle be swapped at runtime, so Postgres refuses it directly inside an index
-- expression ("functions in index expression must be marked IMMUTABLE"). The wrapper below
-- pins the dictionary by name and asserts IMMUTABLE by hand, the standard workaround from
-- Postgres' own unaccent documentation. Safe here because this application never changes
-- text search configuration at runtime, so the promise an IMMUTABLE function makes -- same
-- input, same output, forever -- actually holds for as long as this database exists.
--
-- Schema-qualified (gis_meta.place_search_key), the same way V1__catalog.sql qualifies
-- gis_meta.touch_updated_at: every raw-SQL caller in this codebase (HamburgPlaceQuery,
-- PlaceWriter, and every other JdbcClient query against a gis_meta table -- see
-- ProjectRepository, ExtentCalculator, JobJanitor, ...) addresses gis_meta tables and
-- functions fully qualified rather than relying on the connection's search_path, which
-- outside of Flyway's own scoped migration session does not include gis_meta at all.
CREATE OR REPLACE FUNCTION gis_meta.place_search_key(text)
    RETURNS text AS
$$
SELECT lower(public.unaccent('public.unaccent'::regdictionary, $1))
$$ LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT;

CREATE TABLE place
(
    id         uuid                   PRIMARY KEY,
    name       text                   NOT NULL,
    context    text,
    kind       text                   NOT NULL,
    source     text                   NOT NULL,
    geom       geometry(Point, 4326)  NOT NULL,
    fetched_at timestamptz            NOT NULL DEFAULT now(),
    CONSTRAINT place_kind   CHECK (kind IN ('street', 'district', 'place')),
    CONSTRAINT place_source CHECK (source IN ('hamburg'))
);

-- Trigram GIN index over the normalised name. This is what makes "Hauptstra" find
-- "Billstedter Hauptstrasse" -- the amtliche WFS's own strassenname_normalisiert search
-- (an exact/prefix match on an ASCII-folded column) returns nothing for a truncated middle
-- fragment like that, which was a stated reason for keeping this local copy at all. A
-- trigram GIN index accelerates ILIKE '%term%' for exactly this kind of substring search,
-- unlike a b-tree, which can only help a prefix.
CREATE INDEX place_name_trgm_idx ON place USING gin (gis_meta.place_search_key(name) gin_trgm_ops);
