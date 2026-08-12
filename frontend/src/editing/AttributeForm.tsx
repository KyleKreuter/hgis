import { useEffect, useState } from 'react'
import { Eraser } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import type { LayerField } from '@/api/layers'
import { useEditing, type DraftFeature } from '@/state/editing'
import { FieldInput } from '@/table/FieldInput'
import { kindOf } from '@/table/fieldKind'

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
        Kein Objekt ausgewählt. Klicken Sie ein Objekt an, oder zeichnen Sie eines.
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
