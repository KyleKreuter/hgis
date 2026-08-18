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
--
-- The price: every UPDATE on this trigger's row is an UPDATE on the *project* row, so two
-- writes to two different layers of the same project take project's row lock one after the
-- other, not concurrently, for as long as either transaction stays open -- not a lock on
-- the layer being written, which is what a write to two different layers would otherwise
-- suggest is free to run in parallel.
--
-- Measured before accepting it (plan "Der Live-Kanal meldet auch Datenaenderungen",
-- reviewer's pass), realistic case first: two full imports of 46,233 objects each, started
-- 35 microseconds apart into the *same* project. Baseline, one import alone: 1.458 s. Both
-- concurrent: 1.186 s and 1.178 s -- neither slower than the baseline, both SUCCEEDED with
-- every object. Ten concurrent writes to ten layers of one project cost ~90 ms each against
-- ~25 ms spread over ten separate projects -- a factor of 3.5, both comfortably under
-- 100 ms. No deadlock, even provoked: 16 concurrent reorders of the same layers in
-- deliberately opposite orders all answered 200, 14-55 ms each -- Hibernate flushes an
-- entity list in the order it was loaded from the database, not the order a client asked
-- for, so two transactions touching the same layers always take their row locks in the
-- same order regardless of what either caller requested.
--
-- Why the two imports cost nothing extra: the project row's lock is taken and released
-- once per *batch* -- the trigger's UPDATE, then that batch's transaction commits
-- immediately (ImportTransactions#writeBatch) -- never held for a batch's whole duration.
-- Almost all of a batch's time is spent parsing, reprojecting and bulk-writing objects,
-- none of which ever touches the project row; 47 such locks from one import interleaved
-- with another import's 47 barely register against 1.2 seconds of total work. An isolated
-- worst case still exists -- a transaction held open 5 seconds artificially (pg_sleep, not
-- a real write) makes a second write to a *different* layer of the same project wait 4.1 of
-- those 5 -- full serialisation, exactly as expected once the locked row is understood to
-- be project, not layer. It just never happens here: no real transaction in this
-- application holds the project row anywhere near that long.
--
-- The scaling rule that follows, and the reason this holds only on the hardware it was
-- measured on: the wait one transaction imposes on another is set by that transaction's own
-- commit latency, not by how much data it wrote or the world outside the project row waited
-- for. Measured on a fast machine with a local database, each batch's own commit is quick
-- enough that the lock is gone before a concurrent writer would even notice it queued. On
-- slower disks, a remote database, or under real concurrent load, each of an import's 47
-- batch transactions holds the project row longer, and the wait for a concurrent writer in
-- the same project then grows with the number of writers contending for that project at
-- once -- not with the object count of any one import.
--
-- Accepted as is: hGIS runs single-tenant today, one person plus a handful of agents, and
-- CONTRACT.md names multi-user access control (Spring Security) as deliberately later work
-- of its own. Under that load, the ~65 ms added by ten concurrent writers sharing one
-- project is not a reason to give up the one mechanism that catches every write path -- see
-- above. Revisit this trigger, not just its numbers, once that later work actually starts:
-- a busier project on slower infrastructure would feel this lock first, before anything
-- else in the schema does.

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
