import { useEffect, useState } from 'react'
import { Filter, X } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { ApiError } from '@/api/client'
import type { LayerField } from '@/api/layers'

/** Typing pause before a filter is sent. Long enough not to query per keystroke. */
const DEBOUNCE_MS = 300

interface FilterBarProps {
  fields: LayerField[]
  value: string
  onChange: (filter: string) => void
  error?: unknown
}

/**
 * Filter input for the attribute table.
 *
 * Holds its own draft while typing and only lifts the value after a pause -- a filter
 * runs a query against the whole layer, and doing that per keystroke would fire a dozen
 * scans for an expression nobody has finished writing yet.
 */
export function FilterBar({ fields, value, onChange, error }: FilterBarProps) {
  const [draft, setDraft] = useState(value)

  useEffect(() => {
    if (draft === value) return
    const timer = setTimeout(() => onChange(draft), DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [draft, value, onChange])

  // The parser reports a bad expression as a 400 with a readable reason, which is worth
  // showing verbatim -- it names the position and what it expected there.
  const message = error instanceof ApiError && error.status === 400 ? error.message : undefined

  const example = fields[0]
    ? `${fields[0].sourceName.includes(' ') ? `"${fields[0].sourceName}"` : fields[0].sourceName} = 'Wert'`
    : "feld = 'Wert'"

  return (
    <div className="flex min-w-0 flex-1 items-center gap-1">
      <Tooltip>
        <TooltipTrigger
          render={
            <span className="shrink-0 text-muted-foreground">
              <Filter className="size-3.5" />
            </span>
          }
        />
        <TooltipContent className="max-w-xs">
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
        </TooltipContent>
      </Tooltip>

      <Input
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        placeholder={example}
        aria-label="Filterausdruck"
        aria-invalid={message ? true : undefined}
        className="h-6 min-w-0 flex-1 border-0 bg-transparent px-1 text-xs shadow-none focus-visible:ring-0 aria-invalid:text-destructive"
      />

      {draft && (
        <Button
          variant="ghost"
          size="icon-sm"
          className="size-5 shrink-0"
          aria-label="Filter löschen"
          onClick={() => {
            setDraft('')
            onChange('')
          }}
        >
          <X className="size-3" />
        </Button>
      )}
      {/* The message itself belongs to the empty table area below, which has room for it.
          Repeating it here only pushed the row counter off the edge -- the invalid state
          of the input is what ties the two together. */}
    </div>
  )
}
