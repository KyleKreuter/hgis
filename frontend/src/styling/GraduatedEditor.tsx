import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import type { ClassifyMethod, ClassifyResult, GeometryType, LayerField } from '@/api/layers'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { formatCount } from '@/lib/format'
import {
  columnNameOfField,
  fieldIdOfColumn,
  heatmapFieldRangeQuery,
  initialGraduatedControls,
  requestGraduatedClasses,
  resolveRangeState,
  sharedSymbolOf,
  sourceNameOfField,
  withSharedSymbol,
  withSharedSymbolShape,
} from './classification'
import { BoundIndicator, ColorInput, NumberInput, Row, type BoundCheckState } from './controls'
import { primaryColorOf, withPrimaryColor } from './defaults'
import { isNumericField } from './fields'
import { PaletteSelect } from './PaletteSelect'
import { resolvePaletteId } from './palettes'
import { METHOD_LABELS, labelOf } from './labels'
import { SymbolEditor } from './SymbolEditor'
import type { LayerSymbol, Renderer, StyleClass } from './types'

interface GraduatedEditorProps {
  layerId: string
  geometryType: GeometryType
  renderer: Extract<Renderer, { type: 'graduated' }>
  fields: LayerField[]
  onChange: (renderer: Renderer, options?: { defer?: boolean }) => void
}

/** What `/classify` last answered, kept outside `useQuery` -- see the comment on `request` below. */
interface ClassifyState {
  isFetching: boolean
  isError: boolean
  data?: ClassifyResult
}

export function GraduatedEditor({ layerId, geometryType, renderer, fields, onChange }: GraduatedEditorProps) {
  const queryClient = useQueryClient()
  // Defensive: the server omits every null member (@JsonInclude(NON_NULL)), so an
  // empty list may not arrive as `[]` at all. `undefined.length` here would take the
  // whole workspace down over an edge case that costs one line to survive.
  const classes = renderer.classes ?? []
  // Seeded from the saved renderer, once, the same way `useState`'s initial value
  // always works -- not kept in sync with `renderer` afterwards, because every place
  // that changes `renderer.method` /`.classCount`/`.ramp` also calls `setMethod` /
  // `setClassCount`/`setRamp` itself (see `request` below).
  const initial = initialGraduatedControls(renderer, classes)
  const [method, setMethod] = useState<ClassifyMethod>(initial.method)
  const [classCount, setClassCount] = useState(initial.classCount)
  const [ramp, setRamp] = useState(initial.ramp)
  const [classify, setClassify] = useState<ClassifyState>({ isFetching: false, isError: false })

  // No `useEffect` here on purpose (CONTRACT.md, package B1). This used to be exactly
  // that: an effect watching `renderer`, `method`, `classCount` and `ramp`, rebuilding
  // the classes whenever any of them "changed". The trouble is that mounting looks like
  // a change too -- the effect's guard ref starts out empty, and an empty ref is
  // indistinguishable from a real edit, so it ran the moment the panel opened and
  // rebuilt with `method: 'quantile'` and `ramp: DEFAULT_RAMP` (both plain local state
  // back then, with no memory of what had actually produced the saved classes). That is
  // how a hand-picked class colour, or the whole set of bounds if the classes had been
  // computed with a different method, got silently overwritten just by opening the
  // panel. Do not reintroduce an effect that watches these values -- if the classes ever
  // need to track something automatically again, it has to be a `useState` initial value
  // (see `initial` above) or a user action (see `request` below), never a reactive watch.
  const numericFields = fields.filter(isNumericField)

  /**
   * Whether the stored classes still fit the field's *current* data -- what "Kein
   * automatisches Neuberechnen" (package 3) leaves silently unchecked otherwise.
   *
   * `classes[0].min`/`classes[classes.length - 1].max` are the classification's own
   * outer bounds -- not the field's range at the time it was computed, but the range
   * `stepExpression` (`styleToMapLibre.ts`) actually clamps against today, on every
   * render. A live value below the first or above the last clamps to that edge class's
   * colour instead of getting a colour of its own: the map goes on looking finished,
   * exactly the failure this check exists to surface (package 3: "Es sieht richtig aus.
   * Niemand merkt es.").
   *
   * Reuses `heatmapFieldRangeQuery`/`resolveRangeState` as-is (`classification.ts`) --
   * a plain min/max read, the same one `HeatmapEditor`'s legend already checks its own
   * fixed bounds against, and the same cache entry: both write paths that invalidate
   * `layerKeys.classify` (`api/edits.ts`, `useLiveDataState.ts`) already cover it, so a
   * remote write that pushes the data past a stored boundary surfaces here without
   * anything new to invalidate.
   */
  const rangeQuery = useQuery({
    ...heatmapFieldRangeQuery(layerId, renderer.field),
    enabled: classes.length > 0,
  })
  const rangeState = resolveRangeState(rangeQuery)
  const liveRange = typeof rangeState === 'object' ? rangeState : undefined
  const lowerBound = classes[0]?.min
  const upperBound = classes[classes.length - 1]?.max

  /**
   * Only "the data now reaches past a stored boundary" counts as stale -- not "the data
   * no longer reaches all the way to it". The first is the correctness problem above:
   * a value outside the stored bounds gets an edge class's colour it does not actually
   * belong to. The second is not a correctness problem at all -- every value still
   * inside the stored bounds keeps landing in its own, correctly coloured class no
   * matter how much of that range the current data actually uses; the classification is
   * merely no longer the best possible split of today's data, which is exactly the
   * "not automatically fixed" gap "Kein automatisches Neuberechnen" leaves to a
   * deliberate "Klassen neu berechnen" instead (package 3's own framing: the two ways
   * bounds can go stale do not share a warning, because only one of them is wrong).
   */
  const dataExceedsLower = liveRange !== undefined && lowerBound !== undefined && liveRange.min < lowerBound
  const dataExceedsUpper = liveRange !== undefined && upperBound !== undefined && liveRange.max > upperBound
  // Same "the check itself could not run" distinction `HeatmapEditor`'s legend makes
  // (`checkFailed` there): `'error'`/`'invalid'` never resolve to a `liveRange`, no
  // matter how long the panel stays open, so `dataExceeds*` above reads exactly like
  // "checked, and it fits" for those two states -- indistinguishable from a real pass
  // unless something renders the difference on purpose.
  const checkFailed = rangeState === 'error' || rangeState === 'invalid'
  const lowerState: BoundCheckState = checkFailed ? 'unknown' : dataExceedsLower ? 'stale' : 'current'
  const upperState: BoundCheckState = checkFailed ? 'unknown' : dataExceedsUpper ? 'stale' : 'current'

  /**
   * The only place `/classify` is asked for and the result written back. Called from
   * every control that can produce a new classification -- never from an effect that
   * watches `renderer`, `method`, `classCount` or `ramp`: that pattern is what used to
   * rebuild the classes the moment the panel opened, indistinguishable from the user
   * actually changing something (CONTRACT.md, package B1). `existingClasses` is passed
   * in rather than read off `classes` above because `selectField` needs to say "empty"
   * before `renderer` itself reflects that.
   *
   * `nextRamp` is resolved before either use. Only `selectRamp` ever passes an already-
   * resolved value (straight from `PaletteSelect`); `selectField`, `selectMethod` and
   * `selectClassCount` all pass the current `ramp` state unchanged, and that state can
   * hold a name `initialGraduatedControls` never validated -- it only defaults a
   * *missing* `renderer.ramp`, not one naming a ramp since renamed or removed (team
   * review, package 3 addendum, the `CategorizedEditor`/`palette` counterpart to this).
   * Without resolving here, any of those three controls would repaint every class from
   * `DEFAULT_RAMP` while writing the old, unresolved name back into `renderer.ramp`.
   */
  async function request(nextField: string, nextMethod: ClassifyMethod, nextClassCount: number, nextRamp: string, existingClasses: StyleClass[]) {
    if (!nextField) return
    const resolved = resolvePaletteId(nextRamp)
    setClassify((previous) => ({ ...previous, isFetching: true, isError: false }))
    try {
      const { classes: fresh, result } = await requestGraduatedClasses(
        queryClient,
        layerId,
        geometryType,
        nextField,
        nextMethod,
        nextClassCount,
        resolved,
        existingClasses,
        renderer.fallbackSymbol,
      )
      setClassify({ isFetching: false, isError: false, data: result })
      setRamp(resolved)
      onChange({ ...renderer, field: nextField, method: nextMethod, classCount: nextClassCount, ramp: resolved, classes: fresh })
    }
    catch {
      setClassify((previous) => ({ ...previous, isFetching: false, isError: true }))
    }
  }

  function selectField(fieldId: string) {
    const field = columnNameOfField(fields, fieldId)
    onChange({ ...renderer, field, classes: [] })
    void request(field, method, classCount, ramp, [])
  }

  function selectMethod(next: ClassifyMethod) {
    setMethod(next)
    void request(renderer.field, next, classCount, ramp, classes)
  }

  function selectClassCount(next: number) {
    setClassCount(next)
    void request(renderer.field, method, next, ramp, classes)
  }

  function selectRamp(next: string) {
    setRamp(next)
    void request(renderer.field, method, classCount, next, classes)
  }

  function setClassColor(index: number, color: string, options?: { defer?: boolean }) {
    onChange(
      {
        ...renderer,
        classes: classes.map((styleClass, position) =>
          position === index
            ? { ...styleClass, symbol: withPrimaryColor(styleClass.symbol, color) }
            : styleClass,
        ),
      },
      options,
    )
  }

  /**
   * Applies one symbol's size/width to every class and to the fallback, colours
   * untouched. The fallback has to move with the classes: it renders every object the
   * classification does not cover, and without this it would keep the layer's default
   * size while the classes took on the new one, with no control anywhere to fix it --
   * the "Ohne Wert" row below only ever offered a colour.
   */
  function setSharedSymbol(symbol: LayerSymbol, options?: { defer?: boolean }) {
    onChange(
      {
        ...renderer,
        classes: withSharedSymbol(classes, symbol),
        fallbackSymbol: withSharedSymbolShape(renderer.fallbackSymbol, symbol),
      },
      options,
    )
  }

  return (
    <>
      <Row label="Feld">
        <Select value={fieldIdOfColumn(fields, renderer.field)} onValueChange={(value) => value && selectField(value)}>
          <SelectTrigger size="sm" className="w-full">
            <SelectValue placeholder="Zahlenfeld wählen">
              {(value: string) => sourceNameOfField(fields, value) || 'Zahlenfeld wählen'}
            </SelectValue>
          </SelectTrigger>
          <SelectContent>
            {numericFields.map((field) => (
              <SelectItem key={field.id} value={field.id}>
                {field.sourceName}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </Row>

      {numericFields.length === 0 && (
        <p className="py-1 text-xs text-muted-foreground">
          Dieser Layer hat kein Zahlenfeld, das sich in Klassen einteilen ließe.
        </p>
      )}

      <Row label="Methode">
        <Select value={method} onValueChange={(value) => value && selectMethod(value as ClassifyMethod)}>
          <SelectTrigger size="sm" className="w-full">
            <SelectValue>{(value: string) => labelOf(METHOD_LABELS, value)}</SelectValue>
          </SelectTrigger>
          <SelectContent>
            {METHOD_LABELS.map(([value, label]) => (
              <SelectItem key={value} value={value}>
                {label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </Row>

      <Row label="Klassen">
        <NumberInput label="" value={classCount} min={2} max={12} onChange={selectClassCount} />
        <PaletteSelect value={ramp} onValueChange={selectRamp} includeCategorical={false} />
      </Row>

      {classify.isFetching && (
        <div className="grid gap-1 py-1">
          <Skeleton className="h-5 w-full" />
          <Skeleton className="h-5 w-4/5" />
        </div>
      )}

      {classify.isError && (
        <p className="py-1 text-xs text-destructive">Das Programm konnte die Klassen nicht berechnen</p>
      )}

      {classes.length > 0 && classes.length < classCount && (
        <p className="py-1 text-xs text-muted-foreground">
          Das Feld hat zu wenige verschiedene Werte für {formatCount(classCount)} Klassen. Es sind
          nur {formatCount(classes.length)} geworden.
        </p>
      )}

      {classes.length > 0 && lowerBound !== undefined && upperBound !== undefined && (
        <p className="flex items-center gap-1 py-1 text-xs text-muted-foreground tabular-nums">
          <span>Grenzen:</span>
          <BoundIndicator
            value={lowerBound}
            fixed
            state={lowerState}
            description={
              checkFailed
                ? 'Untere Klassengrenze. Konnte nicht geprüft werden, ob der aktuelle Datenbestand schon darunter reicht -- die Wertespanne ließ sich gerade nicht laden.'
                : dataExceedsLower
                  ? 'Untere Klassengrenze. Der aktuelle Datenbestand reicht bereits darunter -- die Klassifizierung passt nicht mehr zu den Daten. Werte darunter zeigen dieselbe Farbe wie die unterste Klasse.'
                  : 'Untere Klassengrenze. Werte darunter zeigen dieselbe Farbe wie die unterste Klasse.'
            }
          />
          <span>–</span>
          <BoundIndicator
            value={upperBound}
            fixed
            state={upperState}
            description={
              checkFailed
                ? 'Obere Klassengrenze. Konnte nicht geprüft werden, ob der aktuelle Datenbestand schon darüber hinausreicht -- die Wertespanne ließ sich gerade nicht laden.'
                : dataExceedsUpper
                  ? 'Obere Klassengrenze. Der aktuelle Datenbestand reicht bereits darüber hinaus -- die Klassifizierung passt nicht mehr zu den Daten. Werte darüber zeigen dieselbe Farbe wie die oberste Klasse.'
                  : 'Obere Klassengrenze. Werte darüber zeigen dieselbe Farbe wie die oberste Klasse.'
            }
          />
        </p>
      )}

      {classes.length > 0 && (
        // Colour comes from the ramp, per class below -- everything else (size, width,
        // ...) is one shared symbol, edited here for every class (and the fallback) at
        // once.
        <SymbolEditor symbol={sharedSymbolOf(classes, renderer.fallbackSymbol)} onChange={setSharedSymbol} hideColor />
      )}

      {classes.length > 0 && (
        <ScrollArea className="max-h-56">
          <ul className="grid gap-0.5 py-1">
            {classes.map((styleClass, index) => (
              // min-w-0: same fix as the matching `<li>` in `CategorizedEditor`, same
              // reason -- see the comment there.
              <li key={index} className="flex min-w-0 items-center gap-1.5">
                <ColorInput
                  value={primaryColorOf(styleClass.symbol)}
                  onChange={(color, options) => setClassColor(index, color, options)}
                  ariaLabel={`Farbe für Klasse ${styleClass.label}`}
                />
                <span className="truncate text-xs tabular-nums">{styleClass.label}</span>
              </li>
            ))}
          </ul>
        </ScrollArea>
      )}

      <Row label="Ohne Wert">
        <ColorInput
          value={primaryColorOf(renderer.fallbackSymbol)}
          onChange={(color, options) =>
            onChange(
              { ...renderer, fallbackSymbol: withPrimaryColor(renderer.fallbackSymbol, color) },
              options,
            )
          }
          ariaLabel="Farbe für Objekte ohne Wert"
        />
        <span className="truncate text-xs text-muted-foreground">
          {classify.data ? `${formatCount(classify.data.nullCount)} Objekte` : 'Objekte ohne Wert'}
        </span>
      </Row>
    </>
  )
}
