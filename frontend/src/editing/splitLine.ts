import { isMapKeyboardContext } from '@/measurement/keyboard'

/**
 * The line a split is drawn with: the clicks it is made of, what they look like on the
 * map, and which keys mean what while it is being drawn.
 *
 * Its own module rather than part of `SplitLineTool` for the reason `measurement/session`
 * and `measurement/keyboard` give: none of this needs a map, and all of it is worth
 * checking without one.
 */

/** [lng, lat] in EPSG:4326 -- the only coordinate order that ever leaves this application. */
export type SplitLinePoint = [number, number]

/** Fewest clicks that make a line at all. One point is a click, not a cut. */
export const SPLIT_LINE_MIN_POINTS = 2

export interface SplitLineDraft {
  points: readonly SplitLinePoint[]
  /** Where the pointer is now, for the rubber band; null once it has left the map. */
  cursor: SplitLinePoint | null
}

/**
 * The GeoJSON the sketch is drawn from. Three roles, each with its own MapLibre layer:
 * the placed segments, the rubber band to the pointer, and a dot per click.
 */
export function splitLineFeatures({ points, cursor }: SplitLineDraft): GeoJSON.FeatureCollection {
  const features: GeoJSON.Feature[] = []

  if (points.length >= SPLIT_LINE_MIN_POINTS) {
    features.push({
      type: 'Feature',
      properties: { role: 'line' },
      geometry: { type: 'LineString', coordinates: [...points] },
    })
  }

  const last = points.at(-1)
  if (last && cursor) {
    features.push({
      type: 'Feature',
      properties: { role: 'pending' },
      geometry: { type: 'LineString', coordinates: [last, cursor] },
    })
  }

  for (const point of points) {
    features.push({
      type: 'Feature',
      properties: { role: 'vertex' },
      geometry: { type: 'Point', coordinates: point },
    })
  }

  return { type: 'FeatureCollection', features }
}

/**
 * The line to send, or null while there is not enough of it.
 *
 * Guards the request rather than the button alone: a `LineString` with one coordinate is
 * not valid GeoJSON, and the server would answer a malformed body with a 400 that says
 * nothing about what the user actually did.
 */
export function toSplitLine(points: readonly SplitLinePoint[]): GeoJSON.LineString | null {
  if (points.length < SPLIT_LINE_MIN_POINTS) return null
  return { type: 'LineString', coordinates: points.map((point) => [...point]) }
}

/** What a key press should do while the line is being drawn. */
export type SplitLineKeyAction =
  /** Drop the sketch so far, but stay armed. */
  | 'clear'
  /** Leave the split tool altogether. */
  | 'cancel'
  /** Close the line, as a double-click would. */
  | 'finish'
  /** Take back the last click. */
  | 'undo'

/**
 * The action a key stands for, or null when the tool has no business with it.
 *
 * Escape does whichever of the two things is still undone -- clear the sketch, or leave
 * the tool -- so one press never does both, the same rule measuring follows. Enter only
 * means something once the line could actually be sent; before that it is left alone
 * rather than answered with a refusal.
 */
export function splitLineKeyAction(key: string, pointCount: number): SplitLineKeyAction | null {
  switch (key) {
    case 'Escape':
      return pointCount > 0 ? 'clear' : 'cancel'
    case 'Enter':
      return pointCount >= SPLIT_LINE_MIN_POINTS ? 'finish' : null
    case 'Backspace':
      return pointCount > 0 ? 'undo' : null
    default:
      return null
  }
}

/**
 * The same decision for a real event: ours only inside the map, and only if nobody
 * handled the press first. Escape in an open dialog and Backspace in a text field must
 * survive untouched -- see `isMapKeyboardContext`, which this borrows from the measuring
 * tool rather than restating.
 */
export function splitLineKeyEventAction(
  event: { key: string; target?: unknown; defaultPrevented?: boolean },
  pointCount: number,
): SplitLineKeyAction | null {
  if (event.defaultPrevented) return null
  if (!isMapKeyboardContext(event.target)) return null
  return splitLineKeyAction(event.key, pointCount)
}
