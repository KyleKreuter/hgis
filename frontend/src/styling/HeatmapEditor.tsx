import { useQuery } from '@tanstack/react-query'
import type { LayerField } from '@/api/layers'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { formatAttributeNumber } from '@/lib/format'
import {
  columnNameOfField,
  fieldIdOfColumn,
  heatmapFieldRangeQuery,
  resolveRangeState,
  sourceNameOfField,
  type FieldRangeState,
} from './classification'
import { NumberInput, Row } from './controls'
import { COLOR_RAMPS, sampleRamp } from './defaults'
import { isNumericField } from './fields'
import { PaletteSelect } from './PaletteSelect'
import type { Renderer } from './types'

interface HeatmapEditorProps {
  layerId: string
  renderer: Extract<Renderer, { type: 'heatmap' }>
  fields: LayerField[]
  onChange: (renderer: Renderer, options?: { defer?: boolean }) => void
}

/**
 * The field select's own value for "kein Feld, jede Objekt zählt gleich" -- distinct
 * from every field id (a uuid), so it can never collide with a real field, and from `''`,
 * which `<Select>` treats as no selection at all (see `CategorizedEditor`'s field picker).
 */
const DENSITY_VALUE = 'density'

/**
 * The heatmap renderer's controls (renderer contract, package 2.2): a weight field --
 * numeric only, "ohne Feld" a first-class choice rather than an empty list -- plus
 * radius, intensity and colour ramp, and the legend the contract's 2.3 asks for.
 *
 * No `/classify` round trip on a field change the way `GraduatedEditor` needs one:
 * there is nothing to classify into discrete classes here, only a `min`/`max` to read
 * for the legend and for `styleToMapLibre`'s weight normalisation (`MapLayerSync`, on
 * the map side). That is a plain, read-only `useQuery` rather than the
 * request-then-`onChange` pattern the classified renderers follow under CONTRACT.md
 * package B1 -- this component never writes a fetched value back into `renderer`, so a
 * reopened panel re-reading the same field cannot overwrite anything the user picked.
 */
export function HeatmapEditor({ layerId, renderer, fields, onChange }: HeatmapEditorProps) {
  const numericFields = fields.filter(isNumericField)
  const field = renderer.field

  const query = useQuery({
    ...heatmapFieldRangeQuery(layerId, field ?? ''),
    enabled: field !== null,
  })
  // Same conversion `MapLayerSync` applies to its own copy of this query
  // (`heatmapFieldRanges.ts`) -- both have to agree on what "the range is unavailable"
  // means, or the panel could show a plausible range for a layer whose heatmap on the
  // map already fell back to the diagnostic colour (team review, package 2).
  const rangeState = resolveRangeState(query)

  function selectField(value: string) {
    onChange({ ...renderer, field: value === DENSITY_VALUE ? null : columnNameOfField(fields, value) })
  }

  return (
    <>
      <Row label="Feld">
        <Select value={field ? fieldIdOfColumn(fields, field) : DENSITY_VALUE} onValueChange={(value) => value && selectField(value)}>
          <SelectTrigger size="sm" className="w-full">
            <SelectValue>
              {(value: string) =>
                value === DENSITY_VALUE ? 'Ohne Feld (Dichte)' : sourceNameOfField(fields, value) || 'Feld wählen'
              }
            </SelectValue>
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={DENSITY_VALUE}>Ohne Feld (Dichte)</SelectItem>
            {numericFields.map((candidate) => (
              <SelectItem key={candidate.id} value={candidate.id}>
                {candidate.sourceName}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </Row>

      {numericFields.length === 0 && (
        <p className="py-1 text-xs text-muted-foreground">
          Dieser Layer hat kein Zahlenfeld. Die Heatmap zeigt, wo Objekte sich häufen.
        </p>
      )}

      <Row label="Radius">
        <NumberInput
          label=""
          value={renderer.radius}
          min={1}
          max={100}
          onChange={(radius) => onChange({ ...renderer, radius })}
        />
      </Row>

      <Row label="Intensität">
        <NumberInput
          label=""
          value={renderer.intensity}
          min={0.1}
          max={5}
          step={0.1}
          onChange={(intensity) => onChange({ ...renderer, intensity })}
        />
      </Row>

      <Row label="Farbverlauf">
        <PaletteSelect value={renderer.ramp} onValueChange={(ramp) => onChange({ ...renderer, ramp })} includeCategorical={false} />
      </Row>

      <HeatmapLegend ramp={renderer.ramp} hasField={field !== null} isFetching={query.isFetching} rangeState={rangeState} />
    </>
  )
}

interface HeatmapLegendProps {
  ramp: string
  hasField: boolean
  isFetching: boolean
  rangeState: FieldRangeState
}

/**
 * What the darkest and lightest ends of the ramp mean (renderer contract 2.3) -- a
 * heatmap without this is a colour gradient with no stated meaning. Density mode reads
 * "wenig"/"viel", a weighted field its own min/max, formatted the same way a graduated
 * class's bound is (`formatAttributeNumber`, `fields.ts`'s `formatClassLabel`).
 *
 * Reads `rangeState`, not a raw `min`/`max`, on purpose: a `FieldRange` here is already
 * guaranteed finite (`resolveRangeState`), so there is no `null` left to accidentally
 * format -- `Intl.NumberFormat` silently turns `null` into `"0"`, which used to make an
 * unavailable range read as a very plausible, entirely fabricated "0 bis 0" (team
 * review, package 2).
 */
function HeatmapLegend({ ramp, hasField, isFetching, rangeState }: HeatmapLegendProps) {
  const colors = sampleRamp(COLOR_RAMPS.find((candidate) => candidate.id === ramp) ?? COLOR_RAMPS[0], 6)

  return (
    <div className="grid gap-1 py-1">
      <div className="h-3 w-full rounded-sm" style={{ background: `linear-gradient(to right, ${colors.join(', ')})` }} />
      <div className="flex justify-between text-xs text-muted-foreground tabular-nums">
        {!hasField && (
          <>
            <span>wenig</span>
            <span>viel</span>
          </>
        )}
        {hasField && isFetching && <span>Wird geladen…</span>}
        {/* `typeof === 'object'` rather than naming every non-`FieldRange` member of
            `FieldRangeState` one by one: `FieldRange` is the union's only object-shaped
            case, so this stays correct on its own if a third string state is ever added
            (e.g. distinguishing "invalid" further), which enumerating `'error'`/`'invalid'`
            here by name would not. */}
        {hasField && !isFetching && typeof rangeState === 'object' && (
          <>
            <span>{formatAttributeNumber(rangeState.min)}</span>
            <span>{formatAttributeNumber(rangeState.max)}</span>
          </>
        )}
        {hasField && !isFetching && typeof rangeState !== 'object' && <span>Spanne nicht verfügbar</span>}
      </div>
      {/*
       * Für jemanden, der beim Symptom anfängt ("meine Heatmap ist fast leer"), nicht
       * für jemanden, der die Ursache schon vermutet -- der ausführliche Grund dafür
       * steht als Kommentar an `heatmapWeight` (`styleToMapLibre.ts`), hierher gehört
       * nur der kurze, jargonfreie Hinweis, wo er tatsächlich gesucht wird (team review,
       * package 2).
       */}
      {hasField && (
        <p className="pt-1 text-xs text-muted-foreground">
          Ein einzelner sehr hoher oder sehr niedriger Wert kann die Karte fast leer wirken lassen.
        </p>
      )}
    </div>
  )
}
