-- Adds the client's view state to a project: the active layer, and per layer what is
-- sorted, searched or filtered, and selected.
--
-- ADD COLUMN without NOT NULL and without a default, on purpose: this migration runs
-- against projects that already exist. A NULL view_state means the same thing
-- ProjectService already treats an absent one as -- no state has ever been saved -- and
-- is answered with the empty document, never a 404. No backfill is needed.

ALTER TABLE project ADD COLUMN view_state jsonb;
