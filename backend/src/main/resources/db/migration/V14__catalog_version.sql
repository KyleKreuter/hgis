-- The catalog's own version counter, for the live channel's second kind of event (plan
-- "Der Live-Kanal meldet auch Datenaenderungen"). Companion to view_state_version (V12):
-- that one covers the workspace -- map position, active layer, sort, query, selection --
-- this one covers everything else about a project: its layer list, a layer's properties,
-- its style, its data. V13 is the last migration before this one.
--
-- Bumped by a trigger, not by the Java write path that caused the change, and that is a
-- deliberate, measured choice rather than the obvious one. LayerBookkeeping.recount and
-- every LayerService/LayerFieldService write path go through the JPA entity -- a Java
-- counter bumped alongside each of them would work for those. But
-- LayerRepository.bumpDataVersion (ImportTransactions.writeBatch, once per import batch)
-- is a bulk `@Modifying` JPQL UPDATE, and Hibernate never loads an entity for one of
-- those: no dirty checking, no @PostUpdate, nothing an application-level hook could ever
-- see fire. A Java counter, or an entity listener, would silently miss every import ever
-- run through that path -- a mistake that produces no error, just a browser that never
-- updates, which is exactly the failure this whole feature exists to fix. A trigger fires
-- on the write itself, whichever of JPA's several routes to SQL produced it, and whatever
-- future write path does too.

ALTER TABLE project
    ADD COLUMN catalog_version bigint NOT NULL DEFAULT 1;

ALTER TABLE project
    ADD CONSTRAINT project_catalog_version_positive CHECK (catalog_version > 0);


-- Runs under the caller's search_path, not this migration's -- see V10's own note on the
-- same point. Every reference to another table is therefore schema-qualified rather than
-- left to resolve however the calling connection happens to be configured.
CREATE OR REPLACE FUNCTION gis_meta.bump_catalog_version() RETURNS trigger AS $$
BEGIN
    UPDATE gis_meta.project
       SET catalog_version = catalog_version + 1
     WHERE id = COALESCE(NEW.project_id, OLD.project_id);
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER layer_bump_catalog_version
    AFTER INSERT OR UPDATE OR DELETE ON layer
    FOR EACH ROW EXECUTE FUNCTION gis_meta.bump_catalog_version();


-- layer_field carries no project_id of its own; resolved through the layer it belongs to.
-- A field row removed by ON DELETE CASCADE together with its layer (LayerService#purge)
-- finds no such layer any more by the time this runs -- within one DELETE FROM layer
-- statement, the parent row is already gone from every later sub-statement the cascade
-- fires, this one included -- and the UPDATE below then matches no project and does
-- nothing. That is correct, not a gap: the layer's own DELETE fires
-- bump_catalog_version() once already, which is all one purge is worth reporting.
CREATE OR REPLACE FUNCTION gis_meta.bump_catalog_version_for_field() RETURNS trigger AS $$
DECLARE
    affected_layer_id uuid := COALESCE(NEW.layer_id, OLD.layer_id);
BEGIN
    UPDATE gis_meta.project
       SET catalog_version = catalog_version + 1
     WHERE id = (SELECT project_id FROM gis_meta.layer WHERE id = affected_layer_id);
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER layer_field_bump_catalog_version
    AFTER INSERT OR UPDATE OR DELETE ON layer_field
    FOR EACH ROW EXECUTE FUNCTION gis_meta.bump_catalog_version_for_field();
