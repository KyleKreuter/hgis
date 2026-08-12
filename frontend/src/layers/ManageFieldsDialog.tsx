import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { toast } from 'sonner'
import { Check, Loader2, Plus, Trash2 } from 'lucide-react'
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
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { ApiError } from '@/api/client'
import {
  layerDetailQuery,
  useAddLayerField,
  useRenameLayerField,
  type FieldType,
  type LayerField,
  type LayerSummary,
} from '@/api/layers'
import { useEditing } from '@/state/editing'
import { useTableEditing } from '@/table/useTableEditing'
import { DeleteFieldDialog } from './DeleteFieldDialog'
import { FIELD_TYPE_LABELS, FIELD_TYPE_OPTIONS, MAX_FIELDS } from './createLayer'
import {
  buildAddFieldInput,
  buildRenameFieldInput,
  dataTypeLabel,
  existingFieldNameError,
} from './manageFields'

interface ManageFieldsDialogProps {
  layer: LayerSummary | null
  projectId: string
  onOpenChange: (open: boolean) => void
}

/**
 * Lets an existing layer's attribute fields be renamed, added and deleted -- the ways
 * fields ever change after a layer exists, next to `CreateLayerDialog`'s one-time set at
 * creation (CONTRACT.md "Attributfelder hinzufügen, umbenennen und löschen").
 *
 * Deliberately does not offer changing a field's type or reordering it -- out of scope
 * by the contract, and each would touch the physical column this dialog otherwise never
 * has to think about.
 */
export function ManageFieldsDialog({ layer, projectId, onOpenChange }: ManageFieldsDialogProps) {
  const layerId = layer?.id ?? ''
  const { data: detail, isPending: detailPending } = useQuery({
    ...layerDetailQuery(layerId),
    enabled: Boolean(layer),
  })
  const fields = detail?.fields ?? []
  const atLimit = fields.length >= MAX_FIELDS

  const addField = useAddLayerField(layerId, projectId)

  const [newName, setNewName] = useState('')
  const [newType, setNewType] = useState<FieldType>('TEXT')
  const [newNameError, setNewNameError] = useState<string>()
  const [deletingField, setDeletingField] = useState<LayerField | null>(null)

  // Deleting is blocked while this layer has an open edit session, map or table (whether
  // it also has unsaved changes yet or not) -- the edit buffer is column_name-keyed and
  // would keep the orphaned key of a deleted field, which makes
  // `EditService.collectProperties` fail the whole feature on save, not just the one
  // value (CONTRACT.md). A different layer being edited elsewhere carries no such risk
  // and stays unaffected.
  const mapEditingLayerId = useEditing((state) => state.layerId)
  const tableEditingLayerId = useTableEditing((state) => state.layerId)
  const deleteLocked =
    Boolean(layer) && (mapEditingLayerId === layerId || tableEditingLayerId === layerId)
  const deleteLockedReason =
    'Speichern oder verwerfen Sie zuerst die laufende Bearbeitung dieses Layers, um Felder zu löschen.'

  // Only the layer identity resets the draft -- typing in the "new field" row must not
  // get wiped out by an unrelated refetch, e.g. one triggered by renaming another field.
  useEffect(() => {
    if (!layer) {
      setNewName('')
      setNewType('TEXT')
      setNewNameError(undefined)
    }
  }, [layer])

  async function handleAddField(event: React.FormEvent) {
    event.preventDefault()
    const validationError = existingFieldNameError(newName, fields)
    if (validationError) {
      setNewNameError(validationError)
      return
    }
    try {
      const created = await addField.mutateAsync(buildAddFieldInput(newName, newType))
      toast.success(`Feld „${created.sourceName}" hinzugefügt`)
      setNewName('')
      setNewType('TEXT')
      setNewNameError(undefined)
    } catch (caught) {
      if (caught instanceof ApiError) {
        const onName = caught.fieldError('name')
        setNewNameError(onName)
        // type cannot fail here -- the select only ever offers valid FieldType values --
        // but a generic message still has to reach the user if the server disagrees.
        if (!onName) toast.error(caught.fieldError('type') ?? caught.message)
      } else {
        toast.error('Das Programm konnte das Feld nicht hinzufügen')
      }
    }
  }

  return (
    <>
      <Dialog open={Boolean(layer)} onOpenChange={(next) => !next && onOpenChange(false)}>
        <DialogContent className="max-h-[calc(100dvh-2rem)] overflow-y-auto sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>Felder verwalten</DialogTitle>
            <DialogDescription>
              {layer && (
                <>
                  Attributfelder von „{layer.name}" umbenennen, ergänzen oder löschen.
                  Typ und Reihenfolge stehen fest, sobald ein Feld angelegt ist.
                </>
              )}
            </DialogDescription>
          </DialogHeader>

          <div className="grid gap-4 py-4">
            {detailPending ? (
              <p className="text-xs text-muted-foreground">Felder werden geladen…</p>
            ) : fields.length === 0 ? (
              <p className="text-xs text-muted-foreground">
                Dieser Layer hat noch keine Attributfelder.
              </p>
            ) : (
              <div className="max-h-72 overflow-y-auto rounded-md border">
                <Table>
                  <TableHeader className="sticky top-0 z-10 bg-popover">
                    <TableRow>
                      <TableHead className="h-7 text-xs">Name</TableHead>
                      <TableHead className="h-7 text-xs">Typ</TableHead>
                      <TableHead className="h-7 w-16 text-xs" />
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {fields.map((field) => (
                      <FieldRow
                        key={field.id}
                        layerId={layerId}
                        projectId={projectId}
                        field={field}
                        fields={fields}
                        deleteLocked={deleteLocked}
                        deleteLockedReason={deleteLockedReason}
                        onRequestDelete={() => setDeletingField(field)}
                      />
                    ))}
                  </TableBody>
                </Table>
              </div>
            )}

            <form onSubmit={handleAddField} className="grid gap-2 rounded-md border p-2.5">
              <Label className="text-xs font-medium tracking-wide uppercase text-muted-foreground">
                Neues Feld
              </Label>
              <div className="flex items-start gap-2">
                <div className="flex-1">
                  <Input
                    value={newName}
                    onChange={(e) => {
                      setNewName(e.target.value)
                      setNewNameError(undefined)
                    }}
                    placeholder="Feldname"
                    aria-label="Name des neuen Felds"
                    aria-invalid={newNameError ? true : undefined}
                    disabled={atLimit}
                  />
                  {newNameError && <p className="text-xs text-destructive">{newNameError}</p>}
                </div>
                <Select
                  value={newType}
                  onValueChange={(value) => value && setNewType(value as FieldType)}
                  disabled={atLimit}
                >
                  <SelectTrigger className="w-40 shrink-0" aria-label="Typ des neuen Felds">
                    <SelectValue>{(value: string) => FIELD_TYPE_LABELS[value as FieldType]}</SelectValue>
                  </SelectTrigger>
                  <SelectContent>
                    {FIELD_TYPE_OPTIONS.map((type) => (
                      <SelectItem key={type} value={type}>
                        {FIELD_TYPE_LABELS[type]}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <Button type="submit" size="sm" disabled={!newName.trim() || atLimit || addField.isPending}>
                  <Plus className="size-3.5" />
                  {addField.isPending ? 'Wird angelegt…' : 'Hinzufügen'}
                </Button>
              </div>
              {atLimit && (
                <p className="text-xs text-muted-foreground">
                  Maximal {MAX_FIELDS} Felder je Layer.
                </p>
              )}
            </form>
          </div>

          <DialogFooter>
            <Button type="button" onClick={() => onOpenChange(false)}>
              Schließen
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <DeleteFieldDialog
        layerId={layerId}
        layerName={layer?.name ?? ''}
        projectId={projectId}
        field={deletingField}
        onOpenChange={(open) => !open && setDeletingField(null)}
      />
    </>
  )
}

/**
 * One row of the field list: its own rename mutation, so a save in progress only
 * disables this row, not the whole dialog.
 */
function FieldRow({
  layerId,
  projectId,
  field,
  fields,
  deleteLocked,
  deleteLockedReason,
  onRequestDelete,
}: {
  layerId: string
  projectId: string
  field: LayerField
  fields: LayerField[]
  /** Whether an open edit session on this layer blocks deleting it right now. */
  deleteLocked: boolean
  deleteLockedReason: string
  onRequestDelete: () => void
}) {
  const renameField = useRenameLayerField(layerId, projectId)
  const [value, setValue] = useState(field.sourceName)
  const [error, setError] = useState<string>()

  // Tracks the server's own value -- fires only when this field's name actually
  // changed (a successful save here, or someone else's edit landing via a refetch), not
  // on every unrelated refetch that leaves `field.sourceName` untouched.
  useEffect(() => {
    setValue(field.sourceName)
    setError(undefined)
  }, [field.sourceName])

  const trimmed = value.trim()
  const dirty = trimmed !== field.sourceName

  async function handleSave() {
    const validationError = existingFieldNameError(value, fields, field.id)
    if (validationError) {
      setError(validationError)
      return
    }
    try {
      await renameField.mutateAsync(buildRenameFieldInput(field.id, value))
      toast.success(`Feld in „${trimmed}" umbenannt`)
    } catch (caught) {
      if (caught instanceof ApiError) {
        setError(caught.fieldError('name') ?? caught.message)
      } else {
        toast.error('Das Programm konnte das Feld nicht umbenennen')
      }
    }
  }

  return (
    <TableRow>
      <TableCell className="p-1.5 align-top">
        <Input
          value={value}
          onChange={(e) => {
            setValue(e.target.value)
            setError(undefined)
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && dirty) {
              e.preventDefault()
              handleSave()
            }
          }}
          aria-label={`Name von Feld „${field.sourceName}"`}
          aria-invalid={error ? true : undefined}
          className="h-7 text-xs"
        />
        {error && <p className="mt-1 text-xs text-destructive">{error}</p>}
      </TableCell>
      <TableCell className="p-1.5 align-top text-xs text-muted-foreground">
        {dataTypeLabel(field.dataType)}
      </TableCell>
      <TableCell className="p-1.5 align-top">
        <div className="flex items-center gap-0.5">
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            onClick={handleSave}
            disabled={!dirty || renameField.isPending}
            aria-label={`Namensänderung für „${field.sourceName}" speichern`}
          >
            {renameField.isPending ? (
              <Loader2 className="size-3.5 animate-spin" />
            ) : (
              <Check className="size-3.5" />
            )}
          </Button>
          <Tooltip>
            <TooltipTrigger
              render={
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  disabled={deleteLocked}
                  onClick={onRequestDelete}
                  aria-label={`Feld „${field.sourceName}" löschen`}
                >
                  <Trash2 className="size-3.5" />
                </Button>
              }
            />
            {/* Stated rather than a button that is simply unresponsive -- a lock nobody
                can see is indistinguishable from a bug. */}
            <TooltipContent className="max-w-xs">
              {deleteLocked ? deleteLockedReason : `Feld „${field.sourceName}" löschen`}
            </TooltipContent>
          </Tooltip>
        </div>
      </TableCell>
    </TableRow>
  )
}
