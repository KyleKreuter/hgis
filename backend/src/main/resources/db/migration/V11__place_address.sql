-- House numbers in the place search. V10__place.sql's table already has the right shape for
-- an address -- a name, a context, a point -- so no column changes here and no existing row
-- is rewritten: place_kind's list of allowed tokens grows by one, and a second index is
-- added beside V10's own (which stays exactly as it is). See below for why that index has
-- to exist rather than being an optimisation that could follow later.
--
-- 'address' rows come from Hamburg's own gages:Hauskoordinaten (302393 of them, measured
-- 2026-08-15 via RESULTTYPE=hits), carrying street *and* house number in name -- "Eickhoffweg
-- 12" -- so the trigram index built on name in V10 finds the whole address as typed. Context
-- keeps the streets' own "Ortsteil, Postleitzahl" shape.
--
-- place_source is deliberately left as it is: Photon still answers addresses live and its
-- hits are still never stored (V10's own reasoning applies unchanged).

ALTER TABLE gis_meta.place DROP CONSTRAINT place_kind;
ALTER TABLE gis_meta.place ADD CONSTRAINT place_kind
    CHECK (kind IN ('street', 'district', 'place', 'address'));

-- A second, partial copy of V10's trigram index over the streets and districts alone.
-- HamburgPlaceQuery's own doc explains when each of the two statements runs; this is what
-- makes the one without house numbers -- by far the more common one, since it is every
-- search that has not had a number typed into it yet -- cost what it cost before the
-- addresses arrived.
--
-- Measured through GET /api/places on the running application, on copies of the development
-- database, median of eight calls each. "Vorher" is today's 9936 rows on V10; the other two
-- columns are the full 312329 rows (9755 streets, 181 districts, 302393 addresses):
--
--                   9936 rows   312329, no index   312329, this index
--   q=er              30 ms          513 ms              30 ms
--   q=Weg             15 ms           40 ms              15 ms
--   q=Hauptstra        3 ms            9 ms              10 ms
--
-- Where the 513 ms came from: a two-character term contains no trigram at all, so V10's
-- index over the whole table can narrow nothing down and hands back all 312329 rows for the
-- ILIKE to recheck one by one. This index holds 9936 entries in total, so the same worst
-- case can only ever hand back 9936 -- which is why the two slow terms land back exactly on
-- their old numbers. "Hauptstra" stays about 7 ms slower than before either way: its
-- matching rows now sit scattered across a 70 MB table instead of a 3 MB one, and no index
-- changes that. This one costs 728 kB and roughly 120 ms of the six minutes a refresh takes.
--
-- The predicate is written exactly as HamburgPlaceQuery writes its own WHERE clause.
-- Postgres does not insist on that -- it proves that the query's clause implies the index
-- predicate, and manages it for other spellings of the same condition too (verified for
-- kind IN ('street', 'district', 'place')) -- but keeping the two identical is what makes
-- the connection between this file and that query readable at all.
-- PlaceMigrationTest holds them together.
CREATE INDEX place_name_trgm_no_address_idx ON gis_meta.place USING gin (gis_meta.place_search_key(name) gin_trgm_ops)
    WHERE kind <> 'address';
