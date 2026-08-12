-- Layer provenance for datasets imported from Hamburg's Geoportal (CONTRACT.md phase 23.7).
-- V6 is taken by the clip modes; this is the next free number.
--
-- All eight columns are nullable and start out NULL for every existing row: provenance only
-- ever exists for a layer that came from the Geoportal, and there is no way to reconstruct
-- it for a layer that was drawn by hand or imported from a file before this migration ran.
-- The DTO's "source" object mirrors that -- null, not an object of nulls, for such a layer.
--
-- licenseName and licenseUrl are stored per row rather than read from a constant at request
-- time on purpose: the plan (section 4.1) found every checked service naming the same
-- licence today, but nothing guarantees that stays true, and a layer's own record of what
-- licence covered it at import time must not silently change if that ever does.

ALTER TABLE layer
    ADD COLUMN source_attribution      text,
    ADD COLUMN source_license_name     text,
    ADD COLUMN source_license_url      text,
    ADD COLUMN source_dataset_uri      text,
    ADD COLUMN source_metadata_url     text,
    ADD COLUMN source_dataset_id       text,
    ADD COLUMN source_feature_id_field text,
    ADD COLUMN source_fetched_at       timestamptz;
