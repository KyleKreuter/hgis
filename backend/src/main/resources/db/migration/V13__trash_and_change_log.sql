-- Trash for whole layers, and the write change log (plan "Schreibstufe", package
-- "schutz", sections 1.1 and 1.2). V12 is the last migration before this one.

-- A layer's DELETE no longer drops its payload table. It moves the layer to the trash:
-- the catalog row stays, its payload table in gis_data stays, and the row is marked with
-- when and by whom. Both columns null together means "not trashed" -- see
-- LayerService#delete/restore/purge, the only three places that ever touch them.
ALTER TABLE layer ADD COLUMN deleted_at timestamptz;
ALTER TABLE layer ADD COLUMN deleted_by text;

-- Cheap lookup for the trash listing (GET /api/projects/{id}/trash); partial because a
-- trashed layer is the rare case, not the common one.
CREATE INDEX layer_trash_idx ON layer (project_id, deleted_at DESC) WHERE deleted_at IS NOT NULL;


-- Every write to a layer or its features, from the surface as much as from the Python
-- library, independent of which client made it (CONTRACT.md "Schreibstufe" 1.2).
--
-- Deleted *objects* get no trash of their own -- only whole layers do (see above). This
-- table is therefore the sole fallback for a deleted feature: deleted_rows carries its
-- geometry (GeoJSON, EPSG:4326) and every attribute, keyed by column_name like the edit
-- batch itself, so a row here is shaped the same as an EditDtos.Create waiting to be
-- replayed. Filled only for action = 'feature.delete'; every other action leaves it null.
CREATE TABLE change_log (
    id             uuid        PRIMARY KEY,
    occurred_at    timestamptz NOT NULL DEFAULT now(),

    -- Whole history of a project disappears with it -- nothing is left to protect once
    -- the project itself, layers and all, is gone.
    project_id     uuid        NOT NULL REFERENCES project (id) ON DELETE CASCADE,

    -- SET NULL rather than cascaded: a purged layer's own history must stay readable
    -- (that is the point of purge being logged at all). layer_name is captured at write
    -- time for exactly that reason -- once layer_id turns NULL there is nothing left to
    -- join against for a name.
    layer_id       uuid        REFERENCES layer (id) ON DELETE SET NULL,
    layer_name     text        NOT NULL,

    action         text        NOT NULL,
    -- The X-Hgis-Client of whoever wrote it, or null when they named none -- same header,
    -- same rule as ClientId/the live channel's "origin".
    client_name    text,
    affected_count integer     NOT NULL,

    deleted_rows   jsonb,

    CONSTRAINT change_log_action CHECK (action IN (
        'layer.create', 'layer.update', 'layer.delete', 'layer.restore', 'layer.purge',
        'feature.insert', 'feature.update', 'feature.delete',
        'field.create', 'field.delete')),
    CONSTRAINT change_log_affected_count_positive CHECK (affected_count > 0)
);

CREATE INDEX change_log_project_idx ON change_log (project_id, occurred_at DESC, id DESC);
