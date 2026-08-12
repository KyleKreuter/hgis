import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { ClassifyMethod, GeometryType, LayerField } from '@/api/layers'
import { layerClassifyQuery } from '@/api/layers'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { formatCount } from '@/lib/format'
import { buildClasses, columnNameOfField, fieldIdOfColumn, sharedSymbolOf, sourceNameOfField, withSharedSymbol } from './classification'
import { ColorInput, NumberInput, Row } from './controls'
import { primaryColorOf, withPrimaryColor } from './defaults'
import { isNumericField } from './fields'
import { PaletteSelect } from './PaletteSelect'
import { METHOD_LABELS, labelOf } from './labels'
import { DEFAULT_RAMP } from './palettes'
import { SymbolEditor } from './SymbolEditor'
import type { LayerSymbol, Renderer } from './types'

interface GraduatedEditorProps {
  layerId: string
  geometryType: GeometryType
  renderer: Extract<Renderer, { type: 'graduated' }>
  fields: LayerField[]
  onChange: (renderer: Renderer, options?: { defer?: boolean }) => void
}

export function GraduatedEditor({ layerId, geometryType, renderer, fields, onChange }: GraduatedEditorProps) {
  // Defensive: the server omits every null member (@JsonInclude(NON_NULL)), so an
  // empty list may not arrive as `[]` at all. `undefined.length` here would take the
  // whole workspace down over an edge case that costs one line to survive.
  const classes = renderer.classes ?? []
  const [method, setMethod] = useState<ClassifyMethod>('quantile')
  const [classCount, setClassCount] = useState(Math.max(2, classes.length || 5))
  const [ramp, setRamp] = useState(DEFAULT_RAMP)

  const numericFields = fields.filter(isNumericField)
  const { data, isFetching, isError, error } = useQuery({
    ...layerClassifyQuery(layerId, renderer.field, method, classCount),
    enabled: renderer.field !== '',
  })

  // Same idea as in the categorized editor: the classes follow from the breaks, so they
  // are derived as soon as those arrive -- but only once per combination, otherwise a
  // hand-picked class colour would be reset on every render.
  const generatedFor = useRef<string | null>(null)
  useEffect(() => {
    if (!data || data.field !== renderer.field) return
    const key = `${layerId}:${data.field}:${method}:${classCount}:${ramp}`
    if (generatedFor.current === key) return
    generatedFor.current = key
    const fresh = buildClasses(data.breaks, geometryType, ramp)
    // `buildClasses` gives every fresh class the layer's default symbol -- carrying the
    // previous shared size/width across is what keeps it from jumping back to the
    // default the moment the class count, method or ramp changes.
    const shared = sharedSymbolOf(renderer.classes ?? [], geometryType)
    onChange({ ...renderer, classes: withSharedSymbol(fresh, shared) })
  }, [classCount, data, geometryType, layerId, method, onChange, ramp, renderer])

  function selectField(fieldId: string) {
    generatedFor.current = null
    onChange({ ...renderer, field: columnNameOfField(fields, fieldId), classes: [] })
  }

  function setClassColor(index: number, color: string) {
    onChange(
      {
        ...renderer,
        classes: classes.map((styleClass, position) =>
          position === index
            ? { ...styleClass, symbol: withPrimaryColor(styleClass.symbol, color) }
            : styleClass,
        ),
      },
      { defer: true },
    )
  }

  /** Applies one symbol's size/width to every class at once, colours untouched. */
  function setSharedSymbol(symbol: LayerSymbol, options?: { defer?: boolean }) {
    onChange({ ...renderer, classes: withSharedSymbol(classes, symbol) }, options)
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
          Dieser Layer hat kein Zahlenfeld, das sich in Klassen einteilen liesse.
        </p>
      )}

      <Row label="Methode">
        <Select
          value={method}
          onValueChange={(value) => {
            setMethod(value as ClassifyMethod)
          }}
        >
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
        <NumberInput label="" value={classCount} min={2} max={12} onChange={setClassCount} />
        <PaletteSelect value={ramp} onValueChange={setRamp} includeCategorical={false} />
      </Row>

      {isFetching && (
        <div className="grid gap-1 py-1">
          <Skeleton className="h-5 w-full" />
          <Skeleton className="h-5 w-4/5" />
        </div>
      )}

      {isError && (
        <p className="py-1 text-xs text-destructive">
          {error instanceof Error ? error.message : 'Klassen konnten nicht berechnet werden.'}
        </p>
      )}

      {classes.length > 0 && classes.length < classCount && (
        <p className="py-1 text-xs text-muted-foreground">
          Das Feld hat zu wenige verschiedene Werte für {formatCount(classCount)} Klassen — es sind{' '}
          {formatCount(classes.length)} geworden.
        </p>
      )}

      {classes.length > 0 && (
        // Colour comes from the ramp, per class below -- everything else (size, width,
        // ...) is one shared symbol, edited here for every class at once.
        <SymbolEditor symbol={sharedSymbolOf(classes, geometryType)} onChange={setSharedSymbol} hideColor />
      )}

      {classes.length > 0 && (
        <ScrollArea className="max-h-56">
          <ul className="grid gap-0.5 py-1">
            {classes.map((styleClass, index) => (
              <li key={index} className="flex items-center gap-1.5">
                <ColorInput
                  value={primaryColorOf(styleClass.symbol)}
                  onChange={(color) => setClassColor(index, color)}
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
          onChange={(color) =>
            onChange(
              { ...renderer, fallbackSymbol: withPrimaryColor(renderer.fallbackSymbol, color) },
              { defer: true },
            )
          }
          ariaLabel="Farbe für Objekte ohne Wert"
        />
        <span className="truncate text-xs text-muted-foreground">
          {data ? `${formatCount(data.nullCount)} Objekte` : 'Objekte ohne Wert'}
        </span>
      </Row>
    </>
  )
}
