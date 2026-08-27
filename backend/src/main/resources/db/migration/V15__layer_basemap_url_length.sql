-- V3 (basemap per layer) bounded layer.basemap at 64 characters -- plenty for a catalog
-- token like "eox-s2cloudless-2020", the only shape basemap could ever take back then.
-- Since the basemap catalog (VERTRAG.md "Setzen: die bestehenden Endpunkte"), a value
-- starting with https:// is a free-text tile- or WMS-URL template instead, and every
-- realistic one -- the shortest new catalog entry alone is already 40+ characters, and
-- Hamburg's WMS-GetMap templates run well past 100 -- blows straight through 64. The old
-- constraint would reject any URL override on a layer with a 500 (a CHECK violation
-- surfaces as an unhandled DataIntegrityViolationException, not a 400), even though
-- BasemapUrlTemplate had already accepted the same value moments earlier at the
-- application layer.
--
-- Raised to 2000, not dropped: matches BasemapUrlTemplate.MAX_LENGTH exactly, the same
-- bound ProjectDtos.CreateRequest#description already uses for free text elsewhere. The
-- database stays the backstop against a value that skipped application validation, not a
-- second, silently different limit from it.
--
-- project.basemap has never had a length constraint at all (see V1__catalog.sql), so
-- nothing to change there.

ALTER TABLE layer DROP CONSTRAINT layer_basemap_length;
ALTER TABLE layer ADD CONSTRAINT layer_basemap_length
    CHECK (basemap IS NULL OR length(basemap) <= 2000);
