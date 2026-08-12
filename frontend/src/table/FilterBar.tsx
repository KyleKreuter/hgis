import { useEffect, useState } from 'react'
import { Filter, Search, X } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { ApiError } from '@/api/client'
import type { LayerField } from '@/api/layers'
import { toggleFilterMode, type FilterMode } from './filterMode'
import { SelectAllMatchesButton } from './SelectAllMatchesButton'

/** Typing pause before a filter/search runs. Long enough not to query per keystroke. */
const DEBOUNCE_MS = 300

interface FilterBarProps {
  fields: LayerField[]
  layerId: string
  mode: FilterMode
  onModeChange: (mode: FilterMode) => void
  value: string
  onChange: (value: string) => void
  error?: unknown
  /** Current restricted count -- already known from the feature page, see CONTRACT.md. */
  totalCount: number
}

/**
 * Search/filter input for the attribute table, with an explicit toggle between the
 * two -- see CONTRACT.md. Holds its own draft while typing and only lifts the value
 * after a pause: both modes run a query against the whole layer, and doing that per
 * keystroke would fire a dozen scans for an expression or search term nobody has
 * finished typing yet.
 */
export function FilterBar({
  fields,
  layerId,
  mode,
  onModeChange,
  value,
  onChange,
  error,
  totalCount,
}: FilterBarProps) {
  const [draft, setDraft] = useState(value)

  useEffect(() => {
    if (draft === value) return
    const timer = setTimeout(() => onChange(draft), DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [draft, value, onChange])

  // The parser reports a bad expression as a 400 with a readable reason, which is worth
  // showing verbatim -- it names the position and what it expected there. `search`'s own
  // 400 (a layer with no text fields) is just as readable, so this needs no branching
  // on `mode`.
  const message = error instanceof ApiError && error.status === 400 ? error.message : undefined

  const example = fields[0]
    ? `${fields[0].sourceName.includes(' ') ? `"${fields[0].sourceName}"` : fields[0].sourceName} = 'Wert'`
    : "feld = 'Wert'"

  function handleModeToggle() {
    // Switching mode clears the field rather than carrying the text over: a filter
    // expression like `name = 'Schmidt'` is not a sensible search term, and a plain
    // search word is not a valid filter expression either -- keeping it would either
    // search for stray punctuation or hand the parser text it was never meant to see.
    // Starting empty keeps the switch honest: nothing is active in the new mode until
    // something is typed for it.
    setDraft('')
    onModeChange(toggleFilterMode(mode))
  }

  return (
    <div className="flex min-w-0 flex-1 items-center gap-1">
      <Tooltip>
        <TooltipTrigger
          render={
            <button
              type="button"
              onClick={handleModeToggle}
              aria-label={mode === 'filter' ? 'Auf Suchen umschalten' : 'Auf Filtern umschalten'}
              className="shrink-0 text-muted-foreground hover:text-foreground"
            >
              {mode === 'filter' ? <Filter className="size-3.5" /> : <Search className="size-3.5" />}
            </button>
          }
        />
        <TooltipContent className="max-w-xs">
          {mode === 'filter' ? (
            <>
              <p className="font-medium">Filterausdruck</p>
              <p className="mt-1">
                Vergleiche mit <code>= &lt;&gt; &lt; &lt;= &gt; &gt;=</code>, dazu{' '}
                <code>LIKE</code>, <code>IN</code>, <code>IS NULL</code> sowie{' '}
                <code>AND</code>, <code>OR</code>, <code>NOT</code> und Klammern.
              </p>
              <p className="mt-1">
                Feldnamen mit Leerzeichen in doppelte, Werte in einfache Anführungszeichen:{' '}
                <code>{example}</code>
              </p>
              <p className="mt-1 text-muted-foreground">Klicken für die Volltextsuche.</p>
            </>
          ) : (
            <>
              <p className="font-medium">Volltextsuche</p>
              <p className="mt-1">Findet Teiltreffer in allen Textfeldern, ganz ohne Syntax.</p>
              <p className="mt-1 text-muted-foreground">Klicken für den Filterausdruck.</p>
            </>
          )}
        </TooltipContent>
      </Tooltip>

      <Input
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        placeholder={mode === 'filter' ? example : 'Suchbegriff'}
        aria-label={mode === 'filter' ? 'Filterausdruck' : 'Suchbegriff'}
        aria-invalid={message ? true : undefined}
        className="h-6 min-w-0 flex-1 border-0 bg-transparent px-1 text-xs shadow-none focus-visible:ring-0 aria-invalid:text-destructive"
      />

      {draft && (
        <Button
          variant="ghost"
          size="icon-sm"
          className="size-5 shrink-0"
          aria-label={mode === 'filter' ? 'Filter löschen' : 'Suche löschen'}
          onClick={() => {
            setDraft('')
            onChange('')
          }}
        >
          <X className="size-3" />
        </Button>
      )}

      <SelectAllMatchesButton layerId={layerId} mode={mode} value={value} totalCount={totalCount} />
      {/* The message itself belongs to the empty table area below, which has room for it.
          Repeating it here only pushed the row counter off the edge -- the invalid state
          of the input is what ties the two together. */}
    </div>
  )
}
