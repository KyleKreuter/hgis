-- Lets a mask layer clip everything above it to what lies inside its polygons, or --
-- new in this phase -- to what lies outside them. See CONTRACT.md phase 20.
--
-- layer.is_clip_mask was a plain boolean: marked or not. clip_mode replaces it with one
-- tri-state column instead of adding a second flag beside it. Two independent booleans
-- (masked yes/no, inverted yes/no) could express the nonsensical "inverted, but not a
-- mask" -- a single mode column cannot.
--
-- NULL means "not a mask". Every row marked true today clipped to the inside, so it
-- becomes 'inside' rather than being lost.

ALTER TABLE layer ADD COLUMN clip_mode text;
UPDATE layer SET clip_mode = 'inside' WHERE is_clip_mask;
ALTER TABLE layer DROP COLUMN is_clip_mask;
ALTER TABLE layer ADD CONSTRAINT layer_clip_mode_known
    CHECK (clip_mode IS NULL OR clip_mode IN ('inside', 'outside'));
