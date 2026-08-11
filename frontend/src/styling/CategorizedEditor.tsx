import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { AlertTriangle } from 'lucide-react'
import type { GeometryType, LayerField } from '@/api/layers'
import { layerValuesQuery, type FieldValue } from '@/api/layers'
import { Button } from '@/components/ui/button'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { formatCount } from '@/lib/format'
import { ColorInput, Row } from './controls'
import { defaultSymbolFor, primaryColorOf, withPrimaryColor } from './defaults'
import { formatCategoryValue } from './fields'
import { PaletteSelect, paletteColors, DEFAULT_CATEGORY_PALETTE } from './palettes'
import type { Renderer, StyleCategory } from './types'

interface CategorizedEditorProps {
  layerId: string
  geometryType: GeometryType
  renderer: Extract<Renderer, { type: 'categorized' }>
  fields: LayerField[]
  onChange: (renderer: Renderer, options?: { defer?: boolean }) => void
}

export function CategorizedEditor({
  layerId,
  geometryType,
  renderer,
  fields,
  onChange,
}: CategorizedEditorProps) {
  const [palette, setPalette] = useState(DEFAULT_CATEGORY_PALETTE)
  const { data, isFetching, isError } = useQuery({
    ...layerValuesQuery(layerId, renderer.field),
    enabled: renderer.field !== '',
  })

  // Deriving the categories from the loaded values, once per field. Doing it on arrival
  // rather than behind a button saves the step that would follow every field change
  // anyway; the ref is what keeps it from overwriting colours the user has since picked.
  const generatedFor = useRef<string | null>(null)
  useEffect(() => {
    if (!data || data.field !== renderer.field) return
    const key = `${layerId}:${data.field}`
    if (generatedFor.current === key) return
    generatedFor.current = key
    if (renderer.categories.length > 0) return
    onChange({ ...renderer, categories: buildCategories(data.values, geometryType, palette) })
  }, [data, geometryType, layerId, onChange, palette, renderer])

  function selectField(field: string) {
    generatedFor.current = null
    onChange({ ...renderer, field, categories: [] })
  }

  function recolor(paletteId: string) {
    setPalette(paletteId)
    const colors = paletteColors(paletteId, renderer.categories.length)
    onChange({
      ...renderer,
      categories: renderer.categories.map((category, index) => ({
        ...category,
        symbol: withPrimaryColor(category.symbol, colors[index]),
      })),
    })
  }

  function setCategoryColor(index: number, color: string) {
    onChange(
      {
        ...renderer,
        categories: renderer.categories.map((category, position) =>
          position === index ? { ...category, symbol: withPrimaryColor(category.symbol, color) } : category,
        ),
      },
      { defer: true },
    )
  }

  const withoutValue = data?.values.find((entry) => entry.value === null)

  return (
    <>
      <Row label="Feld">
        <Select value={renderer.field} onValueChange={(value) => value && selectField(value)}>
          <SelectTrigger size="sm" className="w-full">
            <SelectValue placeholder="Feld wählen" />
          </SelectTrigger>
          <SelectContent>
            {fields.map((field) => (
              <SelectItem key={field.id} value={field.columnName}>
                {field.sourceName}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </Row>

      <Row label="Farben">
        <PaletteSelect value={palette} onValueChange={recolor} />
        <Button
          variant="outline"
          size="sm"
          className="h-7 px-2 text-xs"
          disabled={renderer.categories.length === 0}
          onClick={() => recolor(palette)}
        >
          Neu verteilen
        </Button>
      </Row>

      {isFetching && (
        <div className="grid gap-1 py-1">
          <Skeleton className="h-5 w-full" />
          <Skeleton className="h-5 w-4/5" />
        </div>
      )}

      {isError && (
        <p className="py-1 text-xs text-destructive">Werte konnten nicht geladen werden.</p>
      )}

      {data?.truncated && (
        <p className="flex items-start gap-1.5 py-1 text-xs text-muted-foreground">
          <AlertTriangle className="mt-0.5 size-3.5 shrink-0" />
          Das Feld hat mehr verschiedene Werte, als hier gezeigt werden. Eine kategorisierte
          Darstellung ist dafür meist die falsche Wahl.
        </p>
      )}

      {renderer.categories.length > 0 && (
        <ScrollArea className="max-h-56">
          <ul className="grid gap-0.5 py-1">
            {renderer.categories.map((category, index) => (
              <li key={`${index}-${String(category.value)}`} className="flex items-center gap-1.5">
                <ColorInput
                  value={primaryColorOf(category.symbol)}
                  onChange={(color) => setCategoryColor(index, color)}
                  ariaLabel={`Farbe für ${category.label}`}
                />
                <span className="truncate text-xs" title={category.label}>
                  {category.label}
                </span>
                <span className="ml-auto shrink-0 text-xs text-muted-foreground tabular-nums">
                  {countOf(data?.values, category.value)}
                </span>
              </li>
            ))}
          </ul>
        </ScrollArea>
      )}

      <Row label="Sonstige">
        <ColorInput
          value={primaryColorOf(renderer.fallbackSymbol)}
          onChange={(color) =>
            onChange(
              { ...renderer, fallbackSymbol: withPrimaryColor(renderer.fallbackSymbol, color) },
              { defer: true },
            )
          }
          ariaLabel="Farbe für alle übrigen Werte"
        />
        <span className="truncate text-xs text-muted-foreground">
          {withoutValue
            ? `alles Übrige, ${formatCount(withoutValue.count)} ohne Wert`
            : 'alles Übrige'}
        </span>
      </Row>
    </>
  )
}

/**
 * Values without a value get no category of their own: MapLibre's `match` cannot carry
 * null as a branch label, so those objects land on the fallback symbol either way.
 */
function buildCategories(values: FieldValue[], geometryType: GeometryType, palette: string): StyleCategory[] {
  const usable = values.filter((entry) => entry.value !== null)
  const colors = paletteColors(palette, usable.length)
  return usable.map((entry, index) => ({
    value: entry.value,
    label: formatCategoryValue(entry.value),
    symbol: withPrimaryColor(defaultSymbolFor(geometryType), colors[index]),
  }))
}

function countOf(values: FieldValue[] | undefined, value: StyleCategory['value']): string {
  const entry = values?.find((candidate) => candidate.value === value)
  return entry ? formatCount(entry.count) : ''
}
