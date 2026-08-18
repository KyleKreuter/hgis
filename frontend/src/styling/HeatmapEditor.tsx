import { useId, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Info, Loader2, Lock, Wand2, X } from 'lucide-react'
import { toast } from 'sonner'
import type { LayerField } from '@/api/layers'
import { Button } from '@/components/ui/button'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { formatAttributeNumber } from '@/lib/format'
import {
  columnNameOfField,
  fieldIdOfColumn,
  heatmapFieldRangeQuery,
  requestHeatmapWeightSuggestion,
  resolveRangeState,
  sourceNameOfField,
  type FieldRangeState,
} from './classification'
import { NumberInput, Row } from './controls'
import { COLOR_RAMPS, sampleRamp } from './defaults'
import { isNumericField } from './fields'
import { PaletteSelect } from './PaletteSelect'
import { normalisationFloor } from './styleToMapLibre'
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
  const queryClient = useQueryClient()
  const numericFields = fields.filter(isNumericField)
  const field = renderer.field
  // Which bound's suggestion button is mid-fetch, if any -- disables both buttons (one
  // `/classify` request at a time is enough) and drives the spinner on the one clicked.
  const [suggesting, setSuggesting] = useState<'min' | 'max' | null>(null)

  const query = useQuery({
    ...heatmapFieldRangeQuery(layerId, field ?? ''),
    enabled: field !== null,
  })
  // Same conversion `MapLayerSync` applies to its own copy of this query
  // (`heatmapFieldRanges.ts`) -- both have to agree on what "the range is unavailable"
  // means, or the panel could show a plausible range for a layer whose heatmap on the
  // map already fell back to the diagnostic colour (team review, package 2).
  const rangeState = resolveRangeState(query)
  const range = typeof rangeState === 'object' ? rangeState : undefined

  function selectField(value: string) {
    // `weightMin`/`weightMax` are one specific field's bounds -- carrying them across a
    // field change would silently misweight the new field (a `baujahr` floor of 1950
    // applied to `waermebedarf_unsaniert` clamps almost everything to weight 0) instead
    // of falling back to the automatic stretch the new field has never been checked
    // against. Cleared on every change, including into density mode, where they mean
    // nothing at all.
    onChange({
      ...renderer,
      field: value === DENSITY_VALUE ? null : columnNameOfField(fields, value),
      weightMin: undefined,
      weightMax: undefined,
    })
  }

  /**
   * The one-click suggestion (renderer contract, package 2 -- "der häufigste Fall darf
   * kein Rechnen verlangen"): fetches the field's quantile breaks and applies the near-8th
   * or near-92nd percentile to whichever bound was clicked, leaving the other untouched.
   * `requestHeatmapWeightSuggestion` shares `layerClassifyQuery`'s cache, so a second click
   * -- the other bound, or the same one again -- costs no further round trip inside the
   * 5-minute window.
   */
  async function applySuggestion(bound: 'min' | 'max') {
    if (!field) return
    setSuggesting(bound)
    try {
      const suggestion = await requestHeatmapWeightSuggestion(queryClient, layerId, field)
      if (!suggestion) {
        toast.error(`Für „${sourceNameOfField(fields, fieldIdOfColumn(fields, field))}" gibt es zu wenige unterschiedliche Werte für einen Vorschlag.`)
        return
      }
      onChange(bound === 'min' ? { ...renderer, weightMin: suggestion.min } : { ...renderer, weightMax: suggestion.max })
    }
    catch {
      toast.error('Das Programm konnte keinen Vorschlag berechnen.')
    }
    finally {
      setSuggesting(null)
    }
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

      {/*
       * Only with a field chosen -- density mode has no weight to bound at all. The
       * automatic stretch (`normalisationFloor(range)`..`range.max`) stays the default;
       * these two only override one end each, independently, and only once someone types
       * a number or clicks a suggestion (`BoundInput`, `applySuggestion` above).
       */}
      {field && (
        <>
          <Row label="Untergrenze">
            <BoundInput
              value={renderer.weightMin}
              placeholder={range ? normalisationFloor(range) : undefined}
              onChange={(weightMin) => onChange({ ...renderer, weightMin })}
              ariaLabel="Untergrenze des Gewichts"
            />
            <SuggestButton
              pending={suggesting === 'min'}
              disabled={suggesting !== null}
              onClick={() => applySuggestion('min')}
              tooltip="Setzt die Untergrenze auf einen Wert nah am unteren Rand der Daten, ohne den äußersten Ausreißer."
              ariaLabel="Vorschlag für die Untergrenze"
            />
          </Row>
          <Row label="Obergrenze">
            <BoundInput
              value={renderer.weightMax}
              placeholder={range?.max}
              onChange={(weightMax) => onChange({ ...renderer, weightMax })}
              ariaLabel="Obergrenze des Gewichts"
            />
            <SuggestButton
              pending={suggesting === 'max'}
              disabled={suggesting !== null}
              onClick={() => applySuggestion('max')}
              tooltip="Setzt die Obergrenze auf einen Wert nah am oberen Rand der Daten, ohne den äußersten Ausreißer. Das hilft, wenn die Heatmap fast leer aussieht."
              ariaLabel="Vorschlag für die Obergrenze"
            />
          </Row>
          {/*
           * `heatmapWeight` itself already falls back to a constant weight whenever the
           * effective bounds do not ascend (`styleToMapLibre.ts`) -- silently, since a map
           * cannot show *why* it looks like density mode. This is that reason, spelled out,
           * for the one case both bounds are fixed by hand and can actually be checked here
           * without needing the field's own range at all.
           */}
          {renderer.weightMin !== undefined && renderer.weightMax !== undefined && !(renderer.weightMax > renderer.weightMin) && (
            <p className="py-1 text-xs text-destructive">
              Die Obergrenze muss größer sein als die Untergrenze. Sonst zählt die Heatmap jedes Objekt gleich.
            </p>
          )}
        </>
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

      <HeatmapLegend
        ramp={renderer.ramp}
        hasField={field !== null}
        isFetching={query.isFetching}
        rangeState={rangeState}
        weightMin={renderer.weightMin}
        weightMax={renderer.weightMax}
      />
    </>
  )
}

interface HeatmapLegendProps {
  ramp: string
  hasField: boolean
  isFetching: boolean
  rangeState: FieldRangeState
  weightMin?: number
  weightMax?: number
}

/**
 * What the darkest and lightest ends of the ramp mean (renderer contract 2.3) -- a
 * heatmap without this is a colour gradient with no stated meaning. Density mode reads
 * "wenig"/"viel", a weighted field its own min/max, formatted the same way a graduated
 * class's bound is (`formatAttributeNumber`, `fields.ts`'s `formatClassLabel`).
 *
 * `weightMin`/`weightMax` (renderer contract, package 2) take over from `rangeState`
 * wherever they are set -- what the legend shows always matches what `heatmapWeight`
 * actually normalises against (`styleToMapLibre.ts`), not the field's unmodified range.
 * With *both* set, the legend no longer needs `rangeState` to be resolved at all, for the
 * same reason `heatmapWeight` itself does not: two fixed numbers are a complete answer on
 * their own (see `styleToMapLibre.ts`'s comment on `heatmapWeight` for the full argument).
 * A fixed end shows a lock, not just a number -- indistinguishable digits would look
 * exactly like a range that will keep tracking the data as it grows, which a fixed one by
 * definition will not (renderer contract: "eine Karte, deren Skala nicht mehr mitwächst,
 * sieht aus wie eine, die es tut -- bis neue Daten kommen und sich nichts ändert").
 */
function HeatmapLegend({ ramp, hasField, isFetching, rangeState, weightMin, weightMax }: HeatmapLegendProps) {
  const colors = sampleRamp(COLOR_RAMPS.find((candidate) => candidate.id === ramp) ?? COLOR_RAMPS[0], 6)
  const range = typeof rangeState === 'object' ? rangeState : undefined
  const bothFixed = weightMin !== undefined && weightMax !== undefined
  // Same fallback order `heatmapWeight` uses: an explicit bound wins, otherwise the
  // automatic stretch, which itself needs `range` to exist at all.
  const effectiveMin = weightMin ?? (range ? normalisationFloor(range) : undefined)
  const effectiveMax = weightMax ?? range?.max
  const known = effectiveMin !== undefined && effectiveMax !== undefined
  // Loading only blocks the display while neither end is already fixed -- with both
  // bounds set, `heatmapWeight` renders correctly independently of `rangeState`
  // (see the comment above), and the legend saying "wird geladen" while the map already
  // shows a normalised heatmap would be the exact "looks unfinished when it is not"
  // problem this component exists to avoid on the other end of the scale.
  const stillLoading = isFetching && !bothFixed

  return (
    <div className="grid gap-1 py-1">
      <div className="h-3 w-full rounded-sm" style={{ background: `linear-gradient(to right, ${colors.join(', ')})` }} />
      <div className="flex items-center gap-1.5">
        <div className="flex flex-1 justify-between text-xs text-muted-foreground tabular-nums">
          {!hasField && (
            <>
              <span>wenig</span>
              <span>viel</span>
            </>
          )}
          {hasField && stillLoading && <span>Wird geladen…</span>}
          {hasField && !stillLoading && known && (
            <>
              <LegendBound value={effectiveMin!} fixed={weightMin !== undefined} description="Feste Untergrenze. Werte darunter zeigen dieselbe Farbe wie dieser Wert." />
              <LegendBound value={effectiveMax!} fixed={weightMax !== undefined} description="Feste Obergrenze. Werte darüber zeigen dieselbe Farbe wie dieser Wert." />
            </>
          )}
          {hasField && !stillLoading && !known && <span>Spanne nicht verfügbar</span>}
        </div>
        {/*
         * Für jemanden, der beim Symptom anfängt ("meine Heatmap ist fast leer"), nicht
         * für jemanden, der die Ursache schon vermutet -- der ausführliche Grund dafür
         * steht als Kommentar an `heatmapWeight` (`styleToMapLibre.ts`), hier nur der
         * kurze, jargonfreie Hinweis, an genau der Stelle, an der er gesucht wird.
         *
         * Ein Symbol statt eines Dauertextes: eine Erklärung, die bei jedem Öffnen des
         * Panels als ganzer Satz dasteht -- egal ob das Feld unauffällig ist oder nicht
         * -- wird nach dem dritten Mal überlesen, und fehlt dann genau dort, wo sie
         * gebraucht würde (team review, package 2, wie schon bei `RectangleSelectToolbar`s
         * Zuschnitt-Hinweis).
         *
         * Only while neither bound is fixed: once one is, `LegendBound`'s own lock icon
         * already says the more specific, more useful thing right next to the number it
         * is about -- a second, generic tooltip next to it would only repeat the warning
         * for a problem the user has, by setting that bound, already started to solve.
         */}
        {hasField && weightMin === undefined && weightMax === undefined && (
          <Tooltip>
            <TooltipTrigger
              render={
                <span
                  tabIndex={0}
                  className="shrink-0 text-muted-foreground"
                  aria-label="Hinweis zu Ausreißern in der Wertespanne"
                >
                  <Info className="size-3.5" />
                </span>
              }
            />
            <TooltipContent className="max-w-xs">
              Ein einzelner sehr hoher oder sehr niedriger Wert kann die Karte fast leer wirken lassen.
            </TooltipContent>
          </Tooltip>
        )}
      </div>
    </div>
  )
}

/** One legend end -- a plain formatted number, or the same number with a lock icon and
 *  its own tooltip when `weightMin`/`weightMax` fixed it rather than the automatic stretch. */
function LegendBound({ value, fixed, description }: { value: number; fixed: boolean; description: string }) {
  if (!fixed) return <span>{formatAttributeNumber(value)}</span>
  return (
    <span className="inline-flex items-center gap-1">
      {formatAttributeNumber(value)}
      <Tooltip>
        <TooltipTrigger
          render={
            <span tabIndex={0} className="shrink-0 text-muted-foreground" aria-label={description}>
              <Lock className="size-3" />
            </span>
          }
        />
        <TooltipContent className="max-w-xs">{description}</TooltipContent>
      </Tooltip>
    </span>
  )
}

interface BoundInputProps {
  value: number | undefined
  /** The value this bound falls back to while empty -- the same one `heatmapWeight` would
   *  use (`normalisationFloor(range)`/`range.max`), shown as a ghost, not a real value. */
  placeholder: number | undefined
  onChange: (value: number | undefined) => void
  ariaLabel: string
}

/**
 * `weightMin`/`weightMax`'s own control, not `NumberInput` (`controls.tsx`): there is no
 * number that could stand for "automatic" here without also being a value someone might
 * genuinely want to type -- 0 chief among them. Empty is the only unambiguous "not set",
 * so typing nothing clears the override and falls back to the automatic stretch, typing a
 * number sets it, and the automatic value itself shows through as a placeholder -- a ghost,
 * not a value someone could mistake for their own choice.
 */
function BoundInput({ value, placeholder, onChange, ariaLabel }: BoundInputProps) {
  const id = useId()
  return (
    <div className="flex min-w-0 flex-1 items-center gap-1">
      <input
        id={id}
        type="number"
        aria-label={ariaLabel}
        value={value ?? ''}
        placeholder={placeholder === undefined ? 'automatisch' : formatAttributeNumber(placeholder)}
        onChange={(event) => {
          const raw = event.target.value
          if (raw === '') {
            onChange(undefined)
            return
          }
          const next = event.target.valueAsNumber
          if (!Number.isFinite(next)) return
          onChange(next)
        }}
        className="h-6 w-full min-w-0 rounded border border-input bg-transparent px-1.5 text-xs tabular-nums outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50"
      />
      {value !== undefined && (
        <button
          type="button"
          onClick={() => onChange(undefined)}
          aria-label={`${ariaLabel} zurücksetzen (automatisch)`}
          className="shrink-0 text-muted-foreground outline-none hover:text-foreground focus-visible:text-foreground"
        >
          <X className="size-3.5" />
        </button>
      )}
    </div>
  )
}

interface SuggestButtonProps {
  pending: boolean
  disabled: boolean
  onClick: () => void
  tooltip: string
  ariaLabel: string
}

/** The one-click suggestion trigger next to a `BoundInput` -- a magic-wand icon button, a
 *  spinner in its place while its own request is in flight (`applySuggestion` above). */
function SuggestButton({ pending, disabled, onClick, tooltip, ariaLabel }: SuggestButtonProps) {
  return (
    <Tooltip>
      <TooltipTrigger
        render={
          <Button
            type="button"
            variant="outline"
            size="icon-xs"
            disabled={disabled}
            onClick={onClick}
            aria-label={ariaLabel}
            className="shrink-0"
          >
            {pending ? <Loader2 className="animate-spin" /> : <Wand2 />}
          </Button>
        }
      />
      <TooltipContent className="max-w-xs">{tooltip}</TooltipContent>
    </Tooltip>
  )
}
