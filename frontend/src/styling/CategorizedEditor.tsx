import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { AlertTriangle, Shuffle } from 'lucide-react'
import type { GeometryType, LayerField } from '@/api/layers'
import { layerValuesQuery, type FieldValue } from '@/api/layers'
import { Button } from '@/components/ui/button'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { formatCount } from '@/lib/format'
import {
  buildCategories,
  columnNameOfField,
  fieldIdOfColumn,
  sharedSymbolOf,
  sourceNameOfField,
  withSharedSymbol,
  withSharedSymbolShape,
} from './classification'
import { ColorInput, Row } from './controls'
import { primaryColorOf, withPrimaryColor } from './defaults'
import { formatCategoryValue } from './fields'
import { PaletteSelect } from './PaletteSelect'
import { DEFAULT_CATEGORY_PALETTE, paletteColors } from './palettes'
import { SymbolEditor } from './SymbolEditor'
import type { LayerSymbol, Renderer, StyleCategory } from './types'

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
  // Defensive: the server omits every null member (@JsonInclude(NON_NULL)), so an
  // empty list may not arrive as `[]` at all. `undefined.length` here would take the
  // whole workspace down over an edge case that costs one line to survive.
  const categories = renderer.categories ?? []
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
    if (categories.length > 0) return
    const fresh = buildCategories(data.values, geometryType, palette)
    // `buildCategories` gives every fresh category the layer's default symbol. This path
    // only runs with an empty list (the guard above), which after a field change is
    // exactly the moment `selectField` cleared `categories` but left `fallbackSymbol`
    // alone -- so a size the user picked earlier is still there to carry over.
    const shared = sharedSymbolOf(renderer.categories ?? [], renderer.fallbackSymbol)
    onChange({ ...renderer, categories: withSharedSymbol(fresh, shared) })
    // `categories.length`, not `categories` itself: the fallback to `[]` makes a fresh
    // array on every render, which would re-run this effect forever.
  }, [categories.length, data, geometryType, layerId, onChange, palette, renderer])

  function selectField(fieldId: string) {
    generatedFor.current = null
    onChange({ ...renderer, field: columnNameOfField(fields, fieldId), categories: [] })
  }

  function recolor(paletteId: string) {
    setPalette(paletteId)
    const colors = paletteColors(paletteId, categories.length)
    onChange({
      ...renderer,
      categories: categories.map((category, index) => ({
        ...category,
        symbol: withPrimaryColor(category.symbol, colors[index]),
      })),
    })
  }

  function setCategoryColor(index: number, color: string) {
    onChange(
      {
        ...renderer,
        categories: categories.map((category, position) =>
          position === index ? { ...category, symbol: withPrimaryColor(category.symbol, color) } : category,
        ),
      },
      { defer: true },
    )
  }

  /**
   * Applies one symbol's size/width to every category and to the fallback, colours
   * untouched. The fallback has to move with the categories: it renders every object the
   * classification does not cover, and without this it would keep the layer's default
   * size while the categories took on the new one, with no control anywhere to fix it --
   * the "Sonstige" row below only ever offered a colour.
   */
  function setSharedSymbol(symbol: LayerSymbol, options?: { defer?: boolean }) {
    onChange(
      {
        ...renderer,
        categories: withSharedSymbol(categories, symbol),
        fallbackSymbol: withSharedSymbolShape(renderer.fallbackSymbol, symbol),
      },
      options,
    )
  }

  const withoutValue = data?.values.find((entry) => entry.value === null)

  return (
    <>
      <Row label="Feld">
        <Select value={fieldIdOfColumn(fields, renderer.field)} onValueChange={(value) => value && selectField(value)}>
          <SelectTrigger size="sm" className="w-full">
            <SelectValue placeholder="Feld wählen">
              {(value: string) => sourceNameOfField(fields, value) || 'Feld wählen'}
            </SelectValue>
          </SelectTrigger>
          <SelectContent>
            {fields.map((field) => (
              <SelectItem key={field.id} value={field.id}>
                {field.sourceName}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </Row>

      <Row label="Farben">
        <PaletteSelect value={palette} onValueChange={recolor} />
        {/* An icon, not a label: with the palette select beside it the row is 286px wide
            in a dock that is 257px across at its default size, and the whole panel would
            scroll sideways -- in a tool panel that is worse than an icon with a title. */}
        <Button
          variant="outline"
          size="icon-sm"
          className="size-7 shrink-0"
          disabled={categories.length === 0}
          title="Farben neu über die Kategorien verteilen"
          aria-label="Farben neu über die Kategorien verteilen"
          onClick={() => recolor(palette)}
        >
          <Shuffle className="size-3.5" />
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

      {categories.length > 0 && (
        // Colour comes from the palette, per category below -- everything else (size,
        // width, ...) is one shared symbol, edited here for every category (and the
        // fallback) at once.
        <SymbolEditor symbol={sharedSymbolOf(categories, renderer.fallbackSymbol)} onChange={setSharedSymbol} hideColor />
      )}

      {categories.length > 0 && (
        <ScrollArea className="max-h-56">
          <ul className="grid gap-0.5 py-1">
            {categories.map((category, index) => {
              // The label is an optional member and may not have survived the API, so it
              // falls back to how the value itself reads.
              const label = category.label || formatCategoryValue(category.value)
              return (
              <li key={`${index}-${String(category.value)}`} className="flex items-center gap-1.5">
                <ColorInput
                  value={primaryColorOf(category.symbol)}
                  onChange={(color) => setCategoryColor(index, color)}
                  ariaLabel={`Farbe für ${label}`}
                />
                <span className="truncate text-xs" title={label}>
                  {label}
                </span>
                <span className="ml-auto shrink-0 text-xs text-muted-foreground tabular-nums">
                  {countOf(data?.values, category.value)}
                </span>
              </li>
              )
            })}
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

function countOf(values: FieldValue[] | undefined, value: StyleCategory['value']): string {
  const entry = values?.find((candidate) => candidate.value === value)
  return entry ? formatCount(entry.count) : ''
}
