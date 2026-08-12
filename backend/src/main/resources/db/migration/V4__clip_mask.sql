-- Lets a polygon layer act as a clip mask: everything stacked above it (higher
-- z_index) is drawn clipped to its polygons. See CONTRACT.md phase 19.
--
-- A single boolean column is enough. Because a mask reaches upward through the whole
-- stack, no layer needs a reference back to it -- the existing z_index already says
-- which layers a mask affects. Taking a layer out of the clip means dragging it below
-- the mask, not editing a relationship.
--
-- At most one layer per project may carry this flag; LayerService enforces that on
-- write by unmarking whichever layer had it before. A CHECK constraint cannot express
-- a cross-row rule like that, so it is left to the application layer rather than
-- reached for here.
--
-- NOT NULL DEFAULT false: every layer that exists today keeps rendering exactly as it
-- does now, unclipped, with no backfill needed.

ALTER TABLE layer ADD COLUMN is_clip_mask boolean NOT NULL DEFAULT false;
