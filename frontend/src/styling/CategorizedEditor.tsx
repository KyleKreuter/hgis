import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, Shuffle } from 'lucide-react'
import type { FieldValue, FieldValuesResult, GeometryType, LayerField } from '@/api/layers'
import { Button } from '@/components/ui/button'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { formatCount } from '@/lib/format'
import {
  columnNameOfField,
  fieldIdOfColumn,
  initialCategorizedPalette,
  requestCategorizedCategories,
  sharedSymbolOf,
  sourceNameOfField,
  withSharedSymbol,
  withSharedSymbolShape,
} from './classification'
import { ColorInput, Row } from './controls'
import { primaryColorOf, withPrimaryColor } from './defaults'
import { formatCategoryValue } from './fields'
import { PaletteSelect } from './PaletteSelect'
import { paletteColors, resolvePaletteId } from './palettes'
import { SymbolEditor } from './SymbolEditor'
import type { LayerSymbol, Renderer, StyleCategory } from './types'

interface CategorizedEditorProps {
  layerId: string
  geometryType: GeometryType
  renderer: Extract<Renderer, { type: 'categorized' }>
  fields: LayerField[]
  onChange: (renderer: Renderer, options?: { defer?: boolean }) => void
}

/** What `/values` last answered, kept outside `useQuery` -- see the comment on `request` below. */
interface ValuesState {
  isFetching: boolean
  isError: boolean
  data?: FieldValuesResult
}

export function CategorizedEditor({
  layerId,
  geometryType,
  renderer,
  fields,
  onChange,
}: CategorizedEditorProps) {
  const queryClient = useQueryClient()
  // Seeded from the saved renderer, once -- see the matching comment in
  // `GraduatedEditor`. A style saved before `palette` existed falls back to the
  // default, same as it always did.
  const [palette, setPalette] = useState(initialCategorizedPalette(renderer))
  // Defensive: the server omits every null member (@JsonInclude(NON_NULL)), so an
  // empty list may not arrive as `[]` at all. `undefined.length` here would take the
  // whole workspace down over an edge case that costs one line to survive.
  const categories = renderer.categories ?? []
  const [values, setValues] = useState<ValuesState>({ isFetching: false, isError: false })

  // No `useEffect` here on purpose (CONTRACT.md, package B1), same as `GraduatedEditor`.
  // This editor used to have one too, watching `renderer`/`categories` and rebuilding
  // whenever `data` arrived with an empty category list. Its own `if (categories.length
  // > 0) return` guard happened to keep it from the graduated renderer's exact failure
  // -- a *populated* list was never touched -- but the shape was still wrong: an effect
  // cannot tell "the panel just mounted" from "the user changed something", because its
  // guard ref starts out empty either way, and an empty ref reads as a change. Kept
  // consistent with `GraduatedEditor` here rather than left as a narrower exception. Do
  // not reintroduce an effect that watches `categories`/`renderer` -- initial values
  // belong in `useState` (see `palette` above), rebuilds in a user action (see `request`
  // below).

  /**
   * The only place `/values` is asked for and the result written back. Called from
   * every control that can produce a new set of categories -- never from an effect
   * that watches `renderer` or `categories`: that pattern is what let a mere reopen
   * look exactly like a field change and overwrite hand-picked colours
   * (`GraduatedEditor`'s equivalent, fixed alongside this one, CONTRACT.md package B1).
   * `existingCategories` is passed in rather than read off `categories` above because
   * `selectField` needs to say "empty" before `renderer` itself reflects that.
   */
  async function request(nextField: string, nextPalette: string, existingCategories: StyleCategory[]) {
    if (!nextField) return
    setValues((previous) => ({ ...previous, isFetching: true, isError: false }))
    try {
      const { categories: fresh, result } = await requestCategorizedCategories(
        queryClient,
        layerId,
        geometryType,
        nextField,
        nextPalette,
        existingCategories,
        renderer.fallbackSymbol,
      )
      setValues({ isFetching: false, isError: false, data: result })
      onChange({ ...renderer, field: nextField, palette: nextPalette, categories: fresh })
    }
    catch {
      setValues((previous) => ({ ...previous, isFetching: false, isError: true }))
    }
  }

  function selectField(fieldId: string) {
    const field = columnNameOfField(fields, fieldId)
    onChange({ ...renderer, field, categories: [] })
    void request(field, palette, [])
  }

  /**
   * Writes back `resolvePaletteId(paletteId)`, not `paletteId` itself: called both from
   * `PaletteSelect` (always a name from its own list, already resolved) and from the
   * "Farben neu verteilen" button below, which replays whatever `palette` currently holds
   * -- and a style saved before this palette existed, or naming one since renamed or
   * removed, seeds that state with a name `paletteColors` cannot place
   * (`initialCategorizedPalette`, `classification.ts`). Pressing the button then repaints
   * every category from `DEFAULT_RAMP` regardless; without resolving here, `renderer.palette`
   * would go on claiming the old, unresolved name while the categories on screen no longer
   * match it (team review, package 3 addendum).
   */
  function recolor(paletteId: string) {
    const resolved = resolvePaletteId(paletteId)
    setPalette(resolved)
    const colors = paletteColors(resolved, categories.length)
    onChange({
      ...renderer,
      palette: resolved,
      categories: categories.map((category, index) => ({
        ...category,
        symbol: withPrimaryColor(category.symbol, colors[index]),
      })),
    })
  }

  function setCategoryColor(index: number, color: string, options?: { defer?: boolean }) {
    onChange(
      {
        ...renderer,
        categories: categories.map((category, position) =>
          position === index ? { ...category, symbol: withPrimaryColor(category.symbol, color) } : category,
        ),
      },
      options,
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

  const withoutValue = values.data?.values.find((entry) => entry.value === null)

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

      {values.isFetching && (
        <div className="grid gap-1 py-1">
          <Skeleton className="h-5 w-full" />
          <Skeleton className="h-5 w-4/5" />
        </div>
      )}

      {values.isError && (
        <p className="py-1 text-xs text-destructive">Das Programm konnte die Werte nicht laden</p>
      )}

      {values.data?.truncated && (
        <p className="flex items-start gap-1.5 py-1 text-xs text-muted-foreground">
          <AlertTriangle className="mt-0.5 size-3.5 shrink-0" />
          Das Feld hat mehr verschiedene Werte, als die Liste zeigt. Eine kategorisierte
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
              // min-w-0: this `<li>` is a grid item of the `<ul>` below (`overflow:
              // visible`, same as any other unset element), so its own automatic minimum
              // width is its min-content size regardless of the `truncate` label inside --
              // that rule only zeroes out the box that itself has overflow-hidden, not an
              // ancestor one level further out (see the matching fix on `SelectTrigger`,
              // `components/ui/select.tsx`). Without it, a long category label pushed the
              // whole panel past a narrow dock's edge, reachable only by scrolling sideways
              // -- the exact failure `Row`/`NumberInput` in `controls.tsx` already guard
              // against, just not retrofitted here yet.
              <li key={`${index}-${String(category.value)}`} className="flex min-w-0 items-center gap-1.5">
                <ColorInput
                  value={primaryColorOf(category.symbol)}
                  onChange={(color, options) => setCategoryColor(index, color, options)}
                  ariaLabel={`Farbe für ${label}`}
                />
                <span className="truncate text-xs" title={label}>
                  {label}
                </span>
                <span className="ml-auto shrink-0 text-xs text-muted-foreground tabular-nums">
                  {countOf(values.data?.values, category.value)}
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
          onChange={(color, options) =>
            onChange(
              { ...renderer, fallbackSymbol: withPrimaryColor(renderer.fallbackSymbol, color) },
              options,
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
