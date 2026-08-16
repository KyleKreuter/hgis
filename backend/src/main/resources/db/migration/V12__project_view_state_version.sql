-- The working state's own version, for the live channel (GET /api/events, plan
-- "Live-Kanal" stage 1). An event reports "project X now stands at working-state
-- version N" and carries nothing else -- so this number is the whole of what a
-- receiver has to be told, and the state itself stays behind the existing API.
--
-- A column, not a counter in memory: a restart must never let a client believe it
-- already holds the current state. The value is only ever compared against a later
-- value of the same project, never against another project's, so every existing row
-- can simply start at 1 -- no backfill, and no meaning attached to the starting point.
--
-- Bumped by a plain UPDATE (ProjectService.updateViewState) rather than by reading,
-- incrementing and writing back, so two writes at the same time cannot both produce
-- the same next value. V11 is the last migration before this one.

ALTER TABLE project
    ADD COLUMN view_state_version bigint NOT NULL DEFAULT 1;

ALTER TABLE project
    ADD CONSTRAINT project_view_state_version_positive CHECK (view_state_version > 0);
