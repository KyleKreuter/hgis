import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import type { ClassifyMethod, ClassifyResult, GeometryType, LayerField } from '@/api/layers'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { formatCount } from '@/lib/format'
import {
  columnNameOfField,
  fieldIdOfColumn,
  initialGraduatedControls,
  requestGraduatedClasses,
  sharedSymbolOf,
  sourceNameOfField,
  withSharedSymbol,
  withSharedSymbolShape,
} from './classification'
import { ColorInput, NumberInput, Row } from './controls'
import { primaryColorOf, withPrimaryColor } from './defaults'
import { isNumericField } from './fields'
import { PaletteSelect } from './PaletteSelect'
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
   * The only place `/classify` is asked for and the result written back. Called from
   * every control that can produce a new classification -- never from an effect that
   * watches `renderer`, `method`, `classCount` or `ramp`: that pattern is what used to
   * rebuild the classes the moment the panel opened, indistinguishable from the user
   * actually changing something (CONTRACT.md, package B1). `existingClasses` is passed
   * in rather than read off `classes` above because `selectField` needs to say "empty"
   * before `renderer` itself reflects that.
   */
  async function request(nextField: string, nextMethod: ClassifyMethod, nextClassCount: number, nextRamp: string, existingClasses: StyleClass[]) {
    if (!nextField) return
    setClassify((previous) => ({ ...previous, isFetching: true, isError: false }))
    try {
      const { classes: fresh, result } = await requestGraduatedClasses(
        queryClient,
        layerId,
        geometryType,
        nextField,
        nextMethod,
        nextClassCount,
        nextRamp,
        existingClasses,
        renderer.fallbackSymbol,
      )
      setClassify({ isFetching: false, isError: false, data: result })
      onChange({ ...renderer, field: nextField, method: nextMethod, classCount: nextClassCount, ramp: nextRamp, classes: fresh })
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
