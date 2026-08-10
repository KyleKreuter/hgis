import { useEffect, useState } from 'react'
import { Eraser } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { cn } from '@/lib/utils'
import type { LayerField } from '@/api/layers'
import { useEditing, type DraftFeature } from '@/state/editing'

const NULL_OPTION = '__null__'

type FieldKind = 'text' | 'integer' | 'decimal' | 'boolean' | 'date' | 'timestamp' | 'readonly'

/** Maps a PostgreSQL column type to the input it deserves (plan section D.4). */
function kindOf(dataType: string): FieldKind {
  const type = dataType.toLowerCase()
  if (type.startsWith('timestamp')) return 'timestamp'
  if (type === 'date') return 'date'
  if (type === 'boolean') return 'boolean'
  if (type === 'uuid') return 'readonly'
  if (/^(smallint|integer|bigint)$/.test(type)) return 'integer'
  if (/^(double precision|numeric|real|decimal)/.test(type)) return 'decimal'
  return 'text'
}

interface AttributeFormProps {
  fields: LayerField[]
  feature: DraftFeature | null
}

/**
 * Attributes of the feature currently being edited, generated at runtime from
 * `layer_field` -- there are no per-layer components, because there is no per-layer
 * anything.
 *
 * Labels show `source_name`, values are sent keyed by `column_name`: the normalised SQL
 * name never reaches the screen, and the display name is never sent.
 */
export function AttributeForm({ fields, feature }: AttributeFormProps) {
  const updateProperties = useEditing((state) => state.updateProperties)
  const [draft, setDraft] = useState<Record<string, unknown>>({})

  useEffect(() => {
    setDraft(feature?.properties ?? {})
  }, [feature])

  if (!feature) {
    return (
      <p className="p-3 text-sm text-muted-foreground">
        Kein Objekt ausgewählt. Ein Objekt anklicken oder eines zeichnen.
      </p>
    )
  }

  function commit(next: Record<string, unknown>) {
    setDraft(next)
    if (feature) updateProperties(feature, next)
  }

  function setValue(column: string, value: unknown) {
    commit({ ...draft, [column]: value })
  }

  return (
    <div className="grid gap-3 p-3">
      <div className="flex items-baseline gap-2">
        <span className="text-xs font-medium">
          {feature.fid < 0 ? 'Neues Objekt' : `Objekt #${feature.fid}`}
        </span>
        {feature.fid < 0 && (
          <span className="text-xs text-muted-foreground">wird beim Speichern angelegt</span>
        )}
      </div>

      {fields.map((field) => {
        const value = draft[field.columnName]
        const kind = kindOf(field.dataType)
        const inputId = `attr-${field.id}`

        return (
          <div key={field.id} className="grid gap-1">
            <div className="flex items-center justify-between gap-2">
              <Label htmlFor={inputId} className="truncate text-xs" title={field.sourceName}>
                {field.sourceName}
              </Label>
              {/* NULL and an empty string are different values, and a text input cannot
                  express the difference. Without this button, clearing a field would
                  silently turn a missing value into an empty one. */}
              {kind !== 'boolean' && value !== null && value !== undefined && (
                <Button
                  variant="ghost"
                  size="icon-sm"
                  className="size-4"
                  aria-label={`${field.sourceName} auf NULL setzen`}
                  onClick={() => setValue(field.columnName, null)}
                >
                  <Eraser className="size-3" />
                </Button>
              )}
            </div>

            <FieldInput
              id={inputId}
              kind={kind}
              value={value}
              onChange={(next) => setValue(field.columnName, next)}
            />

            {(value === null || value === undefined) && (
              <span className="text-[0.6875rem] text-muted-foreground/70 italic">NULL</span>
            )}
          </div>
        )
      })}
    </div>
  )
}

function FieldInput({
  id,
  kind,
  value,
  onChange,
}: {
  id: string
  kind: FieldKind
  value: unknown
  onChange: (value: unknown) => void
}) {
  const isNull = value === null || value === undefined

  if (kind === 'boolean') {
    // Three states, not a switch: a nullable boolean has one more value than a switch can
    // show, and guessing which of the two it means is exactly the wrong move.
    return (
      <Select
        value={isNull ? NULL_OPTION : String(value)}
        onValueChange={(next) => {
          if (!next) return
          onChange(next === NULL_OPTION ? null : next === 'true')
        }}
      >
        <SelectTrigger id={id} className="h-7 w-full text-xs">
          <SelectValue>
            {(current: string) =>
              current === NULL_OPTION ? 'NULL' : current === 'true' ? 'ja' : 'nein'
            }
          </SelectValue>
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="true">ja</SelectItem>
          <SelectItem value="false">nein</SelectItem>
          <SelectItem value={NULL_OPTION}>NULL</SelectItem>
        </SelectContent>
      </Select>
    )
  }

  if (kind === 'readonly') {
    return (
      <Input id={id} value={isNull ? '' : String(value)} readOnly className="h-7 text-xs" />
    )
  }

  const inputType =
    kind === 'integer' || kind === 'decimal'
      ? 'number'
      : kind === 'date'
        ? 'date'
        : kind === 'timestamp'
          ? 'datetime-local'
          : 'text'

  return (
    <Input
      id={id}
      type={inputType}
      step={kind === 'integer' ? 1 : kind === 'decimal' ? 'any' : undefined}
      value={isNull ? '' : toInputValue(value, kind)}
      className={cn('h-7 text-xs', isNull && 'placeholder:italic')}
      placeholder={isNull ? 'NULL' : undefined}
      onChange={(event) => {
        const raw = event.target.value
        // An emptied field means NULL, not "". The eraser button is the explicit route,
        // this is the one people take by habit -- both have to land on the same value.
        if (raw === '') return onChange(null)
        onChange(kind === 'integer' || kind === 'decimal' ? Number(raw) : raw)
      }}
    />
  )
}

/** datetime-local wants "YYYY-MM-DDTHH:mm"; the API delivers a full ISO timestamp. */
function toInputValue(value: unknown, kind: FieldKind): string {
  const text = String(value)
  if (kind === 'timestamp') return text.slice(0, 16)
  if (kind === 'date') return text.slice(0, 10)
  return text
}
