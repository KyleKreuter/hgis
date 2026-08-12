import { useRef, useState } from 'react'
import { toast } from 'sonner'
import { Plus, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { ApiError } from '@/api/client'
import { useCreateLayer, type CreatableGeometryType, type FieldType } from '@/api/layers'
import {
  buildCreateLayerInput,
  canSubmitLayer,
  CREATABLE_GEOMETRY_TYPES,
  fieldNameErrors,
  FIELD_TYPE_LABELS,
  FIELD_TYPE_OPTIONS,
  MAX_FIELDS,
  MIXED_GEOMETRY_HINT,
  MIXED_GEOMETRY_LABEL,
  type DraftField,
} from './createLayer'
import { GEOMETRY_LABELS } from './geometry'

/** Trigger and item share the same short label -- only the item also shows the hint. */
function geometryTypeLabel(type: CreatableGeometryType): string {
  return type === 'GEOMETRY' ? MIXED_GEOMETRY_LABEL : GEOMETRY_LABELS[type]
}

interface CreateLayerDialogProps {
  projectId: string
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Fired after a successful create so the caller can select the new layer in the tree. */
  onCreated: (layerId: string) => void
}

/**
 * Layers so far only ever came out of a file import. This dialog is the second way in:
 * an empty layer with just a name, a geometry type and optionally a few attribute
 * fields, ready to draw into right away (CONTRACT.md "Layer selbst anlegen").
 */
export function CreateLayerDialog({
  projectId,
  open,
  onOpenChange,
  onCreated,
}: CreateLayerDialogProps) {
  const createLayer = useCreateLayer(projectId)

  const [name, setName] = useState('')
  const [geometryType, setGeometryType] = useState<CreatableGeometryType>('MULTIPOINT')
  const [fields, setFields] = useState<DraftField[]>([])
  const [nameError, setNameError] = useState<string>()
  const [fieldsError, setFieldsError] = useState<string>()
  // Module-independent counter for row keys -- an index would shift onto the wrong row
  // when an earlier row is removed.
  const nextFieldId = useRef(0)

  function reset() {
    setName('')
    setGeometryType('MULTIPOINT')
    setFields([])
    setNameError(undefined)
    setFieldsError(undefined)
  }

  function addField() {
    if (fields.length >= MAX_FIELDS) return
    nextFieldId.current += 1
    setFields((current) => [
      ...current,
      { id: `field-${nextFieldId.current}`, name: '', type: 'TEXT' },
    ])
  }

  function removeField(id: string) {
    setFields((current) => current.filter((field) => field.id !== id))
  }

  function updateFieldName(id: string, value: string) {
    setFields((current) =>
      current.map((field) => (field.id === id ? { ...field, name: value } : field)),
    )
  }

  function updateFieldType(id: string, type: FieldType) {
    setFields((current) =>
      current.map((field) => (field.id === id ? { ...field, type } : field)),
    )
  }

  const rowErrors = fieldNameErrors(fields)

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    setNameError(undefined)
    setFieldsError(undefined)

    try {
      const layer = await createLayer.mutateAsync(
        buildCreateLayerInput(name, geometryType, fields),
      )
      toast.success(`Layer „${layer.name}" angelegt`)
      reset()
      onOpenChange(false)
      onCreated(layer.id)
    } catch (error) {
      if (error instanceof ApiError) {
        const onName = error.fieldError('name')
        const onFields = error.fieldError('fields')
        setNameError(onName)
        setFieldsError(onFields)
        // geometryType cannot fail here -- the select only ever offers valid values --
        // but a generic message still has to reach the user if the server disagrees.
        if (!onName && !onFields && !error.fieldError('geometryType')) toast.error(error.message)
      } else {
        toast.error('Layer konnte nicht angelegt werden')
      }
    }
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) reset()
        onOpenChange(next)
      }}
    >
      <DialogContent className="sm:max-w-lg">
        <form onSubmit={handleSubmit}>
          <DialogHeader>
            <DialogTitle>Neuer Layer</DialogTitle>
            <DialogDescription>
              Legt einen leeren Layer an, in den sich anschließend direkt hineinzeichnen
              lässt.
            </DialogDescription>
          </DialogHeader>

          <div className="grid gap-4 py-4">
            <div className="grid gap-1.5">
              <Label htmlFor="layer-name">Name</Label>
              <Input
                id="layer-name"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="z. B. Baumkataster"
                autoFocus
                aria-invalid={nameError ? true : undefined}
              />
              {nameError && <p className="text-xs text-destructive">{nameError}</p>}
            </div>

            <div className="grid gap-1.5">
              <Label htmlFor="layer-geometry">Geometrietyp</Label>
              <Select
                value={geometryType}
                onValueChange={(value) => value && setGeometryType(value as CreatableGeometryType)}
              >
                <SelectTrigger id="layer-geometry" className="w-full">
                  <SelectValue>
                    {(value: string) => geometryTypeLabel(value as CreatableGeometryType)}
                  </SelectValue>
                </SelectTrigger>
                <SelectContent>
                  {CREATABLE_GEOMETRY_TYPES.map((type) =>
                    type === 'GEOMETRY' ? (
                      <SelectItem key={type} value={type}>
                        <span className="flex flex-col items-start">
                          <span>{MIXED_GEOMETRY_LABEL}</span>
                          <span className="text-xs text-muted-foreground">{MIXED_GEOMETRY_HINT}</span>
                        </span>
                      </SelectItem>
                    ) : (
                      <SelectItem key={type} value={type}>
                        {GEOMETRY_LABELS[type]}
                      </SelectItem>
                    ),
                  )}
                </SelectContent>
              </Select>
            </div>

            <div className="grid gap-1.5">
              <div className="flex items-center justify-between">
                <Label>Attributfelder</Label>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={addField}
                  disabled={fields.length >= MAX_FIELDS}
                >
                  <Plus className="size-3.5" />
                  Feld hinzufügen
                </Button>
              </div>
              <p className="text-xs text-muted-foreground">
                Optional — ein Layer ohne Felder lässt sich ebenso bezeichnen und
                bearbeiten, die Attributtabelle zeigt dann nur die fortlaufende Nummer.
                Felder lassen sich nach dem Anlegen nicht mehr ergänzen.
              </p>

              {fields.length > 0 && (
                <div className="grid gap-2">
                  {fields.map((field, index) => (
                    <div key={field.id} className="flex items-start gap-2">
                      <div className="flex-1">
                        <Input
                          value={field.name}
                          onChange={(e) => updateFieldName(field.id, e.target.value)}
                          placeholder="Feldname"
                          aria-invalid={rowErrors[index] ? true : undefined}
                          aria-label={`Name von Feld ${index + 1}`}
                        />
                        {rowErrors[index] && (
                          <p className="text-xs text-destructive">{rowErrors[index]}</p>
                        )}
                      </div>
                      <Select
                        value={field.type}
                        onValueChange={(value) => value && updateFieldType(field.id, value as FieldType)}
                      >
                        <SelectTrigger className="w-40 shrink-0" aria-label={`Typ von Feld ${index + 1}`}>
                          <SelectValue>
                            {(value: string) => FIELD_TYPE_LABELS[value as FieldType]}
                          </SelectValue>
                        </SelectTrigger>
                        <SelectContent>
                          {FIELD_TYPE_OPTIONS.map((type) => (
                            <SelectItem key={type} value={type}>
                              {FIELD_TYPE_LABELS[type]}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                      <Button
                        type="button"
                        variant="ghost"
                        size="icon-sm"
                        onClick={() => removeField(field.id)}
                        aria-label={`Feld ${index + 1} entfernen`}
                      >
                        <Trash2 className="size-3.5" />
                      </Button>
                    </div>
                  ))}
                </div>
              )}
              {fieldsError && <p className="text-xs text-destructive">{fieldsError}</p>}
            </div>
          </div>

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => onOpenChange(false)}
              disabled={createLayer.isPending}
            >
              Abbrechen
            </Button>
            <Button type="submit" disabled={!canSubmitLayer(name, fields) || createLayer.isPending}>
              {createLayer.isPending ? 'Wird angelegt…' : 'Anlegen'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
