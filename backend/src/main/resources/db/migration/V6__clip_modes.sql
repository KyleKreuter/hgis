-- Replaces the two clip modes with four, and drops the one-mask-per-project limit.
-- See CONTRACT.md phase 21.
--
-- 'inside' meant "inside, clipped" and becomes 'insideClipped'; 'outside' becomes
-- 'outsideClipped'. Neither token keeps its old name, even though its meaning survives
-- unchanged -- a reader who sees the old token anywhere else in the codebase must be able
-- to tell at a glance that it predates this migration. The two *Whole modes are new: no
-- existing row can already carry one, so there is nothing to migrate for them.
--
-- The one-mask-per-project rule was enforced by LayerService on write, never by a
-- constraint here, so there is no cross-row rule in this table to relax.

ALTER TABLE layer DROP CONSTRAINT layer_clip_mode_known;
UPDATE layer SET clip_mode = 'insideClipped'  WHERE clip_mode = 'inside';
UPDATE layer SET clip_mode = 'outsideClipped' WHERE clip_mode = 'outside';
ALTER TABLE layer ADD CONSTRAINT layer_clip_mode_known
    CHECK (clip_mode IS NULL OR clip_mode IN
           ('insideWhole', 'insideClipped', 'outsideWhole', 'outsideClipped'));
