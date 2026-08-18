import { useId, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Info, Loader2, Wand2 } from 'lucide-react'
import { toast } from 'sonner'
import type { LayerField } from '@/api/layers'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import {
  columnNameOfField,
  fieldIdOfColumn,
  heatmapFieldRangeQuery,
  requestHeatmapWeightSuggestion,
  resolveRangeState,
  resolveWeightBounds,
  sourceNameOfField,
  type FieldRangeState,
} from './classification'
import { BoundIndicator, NumberInput, Row } from './controls'
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

  // Seeded once from the saved renderer, the same `useState`-initial-value convention
  // `GraduatedEditor`'s method/classCount/ramp follow (CONTRACT.md package B1) -- never
  // re-derived from `renderer` afterwards, or a reopened panel would look like the user
  // just changed something. `manual` mirrors the one state the server actually accepts:
  // both bounds set or neither, never a lone `weightMin`/`weightMax`
  // (`LayerStyleService.requireWeightRange`, backend decision, package 2 sync).
  const [manual, setManual] = useState(renderer.weightMin !== undefined && renderer.weightMax !== undefined)
  // The two inputs' own in-progress values -- distinct from `renderer.weightMin`/
  // `.weightMax` so that typing one end while the other is still empty, or while the pair
  // does not yet ascend, never fires a PATCH the server would 400 on. `onChange` only
  // ever sees a complete, ascending pair (`commitIfValid` below).
  const [draftMin, setDraftMin] = useState<number | undefined>(renderer.weightMin)
  const [draftMax, setDraftMax] = useState<number | undefined>(renderer.weightMax)
  const [suggesting, setSuggesting] = useState(false)

  // One check (`resolveWeightBounds`) behind both the commit decision below and these two
  // hints -- they cannot disagree about what counts as valid, because there is only one
  // place that decides.
  const draftBounds = resolveWeightBounds(draftMin, draftMax)
  const draftIncomplete = manual && !draftBounds && (draftMin === undefined || draftMax === undefined)
  const draftDescending = manual && !draftBounds && draftMin !== undefined && draftMax !== undefined

  function selectField(value: string) {
    // One field's bounds carried onto another would silently misweight it -- a `baujahr`
    // floor of 1950 applied to `waermebedarf_unsaniert` clamps almost everything to
    // weight 0, instead of falling back to the new (unrelated) field's own automatic
    // stretch. Cleared on every change, including into density mode, where they mean
    // nothing at all -- the manual toggle goes with them, so the new field starts
    // automatic rather than carrying the old field's mode forward.
    setManual(false)
    setDraftMin(undefined)
    setDraftMax(undefined)
    onChange({
      ...renderer,
      field: value === DENSITY_VALUE ? null : columnNameOfField(fields, value),
      weightMin: undefined,
      weightMax: undefined,
    })
  }

  /**
   * The one place either draft reaches `renderer` -- gated by `resolveWeightBounds`, the
   * same check `draftIncomplete`/`draftDescending` above read. An incomplete or descending
   * pair is held here, in this component, rather than sent -- the server would 400 on
   * exactly this shape (`requireWeightRange`).
   */
  function commitIfValid(min: number | undefined, max: number | undefined) {
    const bounds = resolveWeightBounds(min, max)
    if (bounds) onChange({ ...renderer, weightMin: bounds.min, weightMax: bounds.max })
  }

  function setDraftMinValue(value: number | undefined) {
    setDraftMin(value)
    commitIfValid(value, draftMax)
  }

  function setDraftMaxValue(value: number | undefined) {
    setDraftMax(value)
    commitIfValid(draftMin, value)
  }

  /**
   * Turning manual mode on seeds both fields from the automatic stretch already in
   * effect -- the same numbers the map already renders with -- so it starts from a
   * working pair instead of two empty boxes (team lead, package 2 backend sync: "damit
   * der Nutzer nicht bei null anfängt"). Off clears both together and falls back to the
   * automatic stretch, the same single `onChange` call `selectField` above makes.
   */
  function toggleManual(checked: boolean) {
    setManual(checked)
    if (!checked) {
      setDraftMin(undefined)
      setDraftMax(undefined)
      onChange({ ...renderer, weightMin: undefined, weightMax: undefined })
      return
    }
    const seedMin = range ? normalisationFloor(range) : undefined
    const seedMax = range?.max
    setDraftMin(seedMin)
    setDraftMax(seedMax)
    commitIfValid(seedMin, seedMax)
  }

  /**
   * The one-click fix for the outlier case (renderer contract, package 2 -- "der
   * häufigste Fall darf kein Rechnen verlangen"): fetches the field's quantile breaks and
   * sets *both* bounds at once to the near-8th/92nd percentile, turning manual mode on if
   * it was not already. Both move together on purpose -- the server no longer accepts a
   * "just fix the top end" state, so neither does this button.
   */
  async function applySuggestion() {
    if (!field) return
    setSuggesting(true)
    try {
      const suggestion = await requestHeatmapWeightSuggestion(queryClient, layerId, field)
      if (!suggestion) {
        toast.error(`Für „${sourceNameOfField(fields, fieldIdOfColumn(fields, field))}" gibt es zu wenige unterschiedliche Werte für einen Vorschlag.`)
        return
      }
      setManual(true)
      setDraftMin(suggestion.min)
      setDraftMax(suggestion.max)
      onChange({ ...renderer, weightMin: suggestion.min, weightMax: suggestion.max })
    }
    catch {
      toast.error('Das Programm konnte keinen Vorschlag berechnen.')
    }
    finally {
      setSuggesting(false)
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

      {/* Only with a field chosen -- density mode has no weight to bound at all. */}
      {field && (
        <>
          <div className="flex min-w-0 flex-wrap items-center gap-1.5 py-1">
            <Checkbox
              id="heatmap-weight-manual"
              checked={manual}
              // Turning it on seeds both fields from `range` (`toggleManual` above) --
              // disabled until that exists, rather than turning on into two empty boxes
              // with nothing to seed them from.
              disabled={!range}
              onCheckedChange={(checked) => toggleManual(checked === true)}
            />
            <Label htmlFor="heatmap-weight-manual" className="text-xs font-normal">
              Grenzen manuell festlegen
            </Label>
            {!range && (
              <span className="text-xs text-muted-foreground">
                {query.isFetching ? 'Spanne wird geladen…' : 'Spanne nicht verfügbar'}
              </span>
            )}
            <SuggestButton
              pending={suggesting}
              disabled={suggesting}
              onClick={applySuggestion}
              tooltip="Setzt beide Grenzen auf einen Bereich nah an den Rändern der Daten, ohne die äußersten Ausreißer. Das hilft, wenn die Heatmap fast leer aussieht."
              ariaLabel="Vorschlag für den Gewichtsbereich"
            />
          </div>

          {manual && (
            <>
              <Row label="Untergrenze">
                <BoundInput value={draftMin} onChange={setDraftMinValue} ariaLabel="Untergrenze des Gewichts" />
              </Row>
              <Row label="Obergrenze">
                <BoundInput value={draftMax} onChange={setDraftMaxValue} ariaLabel="Obergrenze des Gewichts" />
              </Row>
              {/*
               * Explains why nothing changed on the map yet -- `commitIfValid` above
               * silently withholds an incomplete or descending pair rather than sending
               * it, since the server would 400 on exactly this shape
               * (`requireWeightRange`). Silence on its own would read as a bug.
               */}
              {draftIncomplete && (
                <p className="py-1 text-xs text-muted-foreground">
                  Beide Werte werden gebraucht. Bis dahin gilt weiterhin die zuletzt gespeicherte Grenze.
                </p>
              )}
              {draftDescending && (
                <p className="py-1 text-xs text-destructive">
                  Die Obergrenze muss größer sein als die Untergrenze.
                </p>
              )}
            </>
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

  /**
   * The real case the team lead measured: `waermebedarf_unsaniert`'s `weightMax`
   * (1 225 354, its p95) copied onto `waermebedarf_saniert` -- same field, same
   * building stock, an obviously related layer to reuse a bound on. That field's true
   * maximum is only 23 165 761, well under the copied ceiling, so the hottest saniertes
   * Gebäude lands at weight 0,51 -- which reads as "sanieren half, the whole scale is
   * used" when it is actually "the fixed ceiling no longer matches this field's data".
   * Nothing about a plain locked number says which of the two is true (team lead,
   * package 2 measurement) -- this is that missing signal, computed from data this
   * component already has in scope (`range`) rather than a second request.
   */
  const dataExceedsMin = weightMin !== undefined && range !== undefined && range.min < weightMin
  const dataExceedsMax = weightMax !== undefined && range !== undefined && range.max > weightMax
  /**
   * Whether that comparison could even run. `range` needs a *resolved* `FieldRangeState`
   * (`typeof rangeState === 'object'`), and `'error'`/`'invalid'` never resolve to one, no
   * matter how long this panel stays open -- unlike "still loading" (`rangeState ===
   * undefined`), which resolves on its own once the request settles and is deliberately
   * excluded here (`stillLoading` above already covers it without a false-positive
   * warning; the Prüfer confirmed that half is right).
   *
   * A fixed bound keeps rendering correctly on the map regardless -- `heatmapWeight` only
   * needs `range` when a bound is *not* fixed. But the "does the data still fit" check
   * above silently *cannot* run while `range` is unavailable, and `dataExceeds*` reads
   * exactly like "checked, and it fits" in that case -- there is no third value it could
   * return instead. Found by the Prüfer: "eine Warnung, die stillschweigend nie kommt, ist
   * schlimmer als gar keine. Sie erzeugt Vertrauen, das sie nicht trägt." This is what
   * keeps that silence from being mistaken for an answer -- `BoundIndicator` renders a
   * different icon for it, not just a different tooltip sentence, so the distinction
   * survives even for someone who never hovers.
   */
  const checkFailed = rangeState === 'error' || rangeState === 'invalid'
  const checkUnavailableMin = weightMin !== undefined && checkFailed
  const checkUnavailableMax = weightMax !== undefined && checkFailed

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
              <BoundIndicator
                value={effectiveMin!}
                fixed={weightMin !== undefined}
                state={checkUnavailableMin ? 'unknown' : dataExceedsMin ? 'stale' : 'current'}
                description={
                  checkUnavailableMin
                    ? 'Feste Untergrenze. Konnte nicht geprüft werden, ob der aktuelle Datenbestand schon darunter reicht -- die Wertespanne ließ sich gerade nicht laden.'
                    : dataExceedsMin
                      ? 'Feste Untergrenze. Der aktuelle Datenbestand reicht bereits darunter -- die Grenze passt nicht mehr zu den Daten. Werte darunter zeigen dieselbe Farbe wie dieser Wert.'
                      : 'Feste Untergrenze. Werte darunter zeigen dieselbe Farbe wie dieser Wert.'
                }
              />
              <BoundIndicator
                value={effectiveMax!}
                fixed={weightMax !== undefined}
                state={checkUnavailableMax ? 'unknown' : dataExceedsMax ? 'stale' : 'current'}
                description={
                  checkUnavailableMax
                    ? 'Feste Obergrenze. Konnte nicht geprüft werden, ob der aktuelle Datenbestand schon darüber hinausreicht -- die Wertespanne ließ sich gerade nicht laden.'
                    : dataExceedsMax
                      ? 'Feste Obergrenze. Der aktuelle Datenbestand reicht bereits darüber hinaus -- die Grenze passt nicht mehr zu den Daten. Werte darüber zeigen dieselbe Farbe wie dieser Wert.'
                      : 'Feste Obergrenze. Werte darüber zeigen dieselbe Farbe wie dieser Wert.'
                }
              />
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
         * Only while neither bound is fixed: once one is, `BoundIndicator`'s own lock icon
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

interface BoundInputProps {
  value: number | undefined
  onChange: (value: number | undefined) => void
  ariaLabel: string
}

/**
 * `weightMin`/`weightMax`'s own control, not `NumberInput` (`controls.tsx`): its value can
 * sit transiently empty while someone is mid-edit, without that meaning anything final --
 * `onChange` here only ever updates `HeatmapEditor`'s own draft state, which reaches
 * `renderer` exclusively through `commitIfValid` once *both* ends hold a valid, ascending
 * pair. An empty box is not "automatic" any more (that is what turning the manual switch
 * off is for) -- it is simply "not finished typing yet".
 */
function BoundInput({ value, onChange, ariaLabel }: BoundInputProps) {
  const id = useId()
  return (
    <input
      id={id}
      type="number"
      aria-label={ariaLabel}
      value={value ?? ''}
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
  )
}

interface SuggestButtonProps {
  pending: boolean
  disabled: boolean
  onClick: () => void
  tooltip: string
  ariaLabel: string
}

/** The one-click suggestion trigger next to the manual-mode switch -- a magic-wand icon
 *  button, a spinner in its place while its own request is in flight (`applySuggestion`
 *  above). */
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
