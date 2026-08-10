/**
 * terra-draw works on Point, LineString and Polygon only -- it has no notion of the
 * multi-variants. Layer columns, on the other hand, are always multi-typed: the import
 * promotes everything with `ST_Multi` because a shapefile cannot promise otherwise.
 *
 * So every existing feature has to be unwrapped before it can be edited, and a multi
 * geometry that genuinely has several parts cannot be: dragging one part would leave the
 * others behind with no way to express that in a single terra-draw feature. Those are
 * reported rather than silently reduced to their first part -- editing a building and
 * saving away its courtyard is exactly the kind of loss nobody notices until much later.
 *
 * Writing back is symmetric and needs no counterpart: `ST_Multi` in the edit service
 * promotes the single part again.
 */
/** Exactly what terra-draw's store accepts -- the type states the restriction. */
export type SinglePartGeometry = GeoJSON.Point | GeoJSON.LineString | GeoJSON.Polygon

export function toSinglePart(geometry: GeoJSON.Geometry): SinglePartGeometry | null {
  switch (geometry.type) {
    case 'Point':
    case 'LineString':
    case 'Polygon':
      return geometry

    case 'MultiPoint':
      return geometry.coordinates.length === 1
        ? { type: 'Point', coordinates: geometry.coordinates[0] }
        : null

    case 'MultiLineString':
      return geometry.coordinates.length === 1
        ? { type: 'LineString', coordinates: geometry.coordinates[0] }
        : null

    case 'MultiPolygon':
      return geometry.coordinates.length === 1
        ? { type: 'Polygon', coordinates: geometry.coordinates[0] }
        : null

    default:
      // GeometryCollection and anything else: not editable, and ST_AsMVTGeom would not
      // have rendered it either.
      return null
  }
}
