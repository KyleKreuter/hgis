# MapLibre glyph PBFs

Signed-distance-field glyph sets served at `/api/glyphs/{fontstack}/{range}.pbf`.

## Bundled face

- **Noto Sans Regular** — SIL Open Font License 1.1 (see `Noto Sans Regular/LICENSE.md`)
- Source of the PBF conversion: [maplibre/demotiles](https://github.com/maplibre/demotiles/tree/gh-pages/font/Noto%20Sans%20Regular)
- Ranges shipped: Latin + Latin Extended, combining marks, general punctuation / currency (€), and a small block of common symbols. Missing ranges return 404; MapLibre skips those codepoints.

## Regenerating / extending

1. Convert a TTF/OTF with [MapLibre Font Maker](https://maplibre.org/font-maker/) (or download a full stack from demotiles).
2. Place the `{range}.pbf` files under `Noto Sans Regular/` (folder name must match the frontend `LABEL_FONT`).
3. Keep `LICENSE.md` next to the PBFs.
