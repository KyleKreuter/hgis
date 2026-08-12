-- Index behind job.output_layer_id. V7 is taken by the layer provenance; this is the next
-- free number.
--
-- The column is a foreign key with ON DELETE SET NULL, and PostgreSQL creates an index for
-- the referencing side of a foreign key exactly never. Every write to gis_meta.layer that
-- could break the reference therefore had to prove it does not -- by reading the whole job
-- table. That is one sequential scan per deleted layer, and deleting layers is not rare:
-- it happens for every failed import's compensation, every janitor cleanup after a crash,
-- and every layer a user removes by hand. A project deletion cascades into it once per
-- layer it holds.
--
-- Partial on purpose. A job that produced no layer -- every PENDING job, every job that
-- failed before phase A, every duplication -- carries NULL here, and NULL is the one value
-- the foreign key check never has to look for. Leaving those rows out keeps the index to
-- the jobs that can actually block a delete.

CREATE INDEX job_output_layer_idx ON job (output_layer_id) WHERE output_layer_id IS NOT NULL;
