import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { ClassifyMethod, GeometryType, LayerField } from '@/api/layers'
import { layerClassifyQuery } from '@/api/layers'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { formatCount } from '@/lib/format'
import { ColorInput, NumberInput, Row } from './controls'
import { defaultSymbolFor, primaryColorOf, withPrimaryColor } from './defaults'
import { formatClassLabel, isNumericField } from './fields'
import { DEFAULT_RAMP, PaletteSelect, paletteColors } from './palettes'
import type { Renderer, StyleClass } from './types'

const METHOD_LABELS: [ClassifyMethod, string][] = [
  ['quantile', 'Quantile'],
  ['equalInterval', 'Gleiche Intervalle'],
  // Named as what it is: the server approximates Jenks with ntile, because exact Jenks
  // is quadratic and unusable on a large layer.
  ['naturalBreaks', 'Natürliche Unterbrechungen (genähert)'],
]

interface GraduatedEditorProps {
  layerId: string
  geometryType: GeometryType
  renderer: Extract<Renderer, { type: 'graduated' }>
  fields: LayerField[]
  onChange: (renderer: Renderer, options?: { defer?: boolean }) => void
}

export function GraduatedEditor({ layerId, geometryType, renderer, fields, onChange }: GraduatedEditorProps) {
  const [method, setMethod] = useState<ClassifyMethod>('quantile')
  const [classCount, setClassCount] = useState(Math.max(2, renderer.classes.length || 5))
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
    onChange({ ...renderer, classes: buildClasses(data.breaks, geometryType, ramp) })
  }, [classCount, data, geometryType, layerId, method, onChange, ramp, renderer])

  function selectField(field: string) {
    generatedFor.current = null
    onChange({ ...renderer, field, classes: [] })
  }

  function setClassColor(index: number, color: string) {
    onChange(
      {
        ...renderer,
        classes: renderer.classes.map((styleClass, position) =>
          position === index
            ? { ...styleClass, symbol: withPrimaryColor(styleClass.symbol, color) }
            : styleClass,
        ),
      },
      { defer: true },
    )
  }

  return (
    <>
      <Row label="Feld">
        <Select value={renderer.field} onValueChange={(value) => value && selectField(value)}>
          <SelectTrigger size="sm" className="w-full">
            <SelectValue placeholder="Zahlenfeld wählen" />
          </SelectTrigger>
          <SelectContent>
            {numericFields.map((field) => (
              <SelectItem key={field.id} value={field.columnName}>
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
            <SelectValue />
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

      {renderer.classes.length > 0 && renderer.classes.length < classCount && (
        <p className="py-1 text-xs text-muted-foreground">
          Das Feld hat zu wenige verschiedene Werte für {formatCount(classCount)} Klassen — es sind{' '}
          {formatCount(renderer.classes.length)} geworden.
        </p>
      )}

      {renderer.classes.length > 0 && (
        <ScrollArea className="max-h-56">
          <ul className="grid gap-0.5 py-1">
            {renderer.classes.map((styleClass, index) => (
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

/**
 * `breaks` holds the lower bound of every class plus the maximum -- usually n+1 values,
 * but fewer when the column has fewer distinct values than classes were asked for. The
 * server drops the repeated bounds because `step` rejects stops that do not ascend, so
 * the class count comes from the answer and never from what was requested.
 */
function buildClasses(breaks: number[], geometryType: GeometryType, ramp: string): StyleClass[] {
  const count = Math.max(0, breaks.length - 1)
  const colors = paletteColors(ramp, count)
  return Array.from({ length: count }, (_, index) => ({
    min: breaks[index],
    max: breaks[index + 1],
    label: formatClassLabel(breaks[index], breaks[index + 1]),
    symbol: withPrimaryColor(defaultSymbolFor(geometryType), colors[index]),
  }))
}
