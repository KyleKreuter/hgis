import { useState } from 'react'
import { useQueries } from '@tanstack/react-query'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { formatAttributeNumber, formatCount } from '@/lib/format'
import { featureDetailQuery, type Feature } from '@/api/features'
import type { LayerField } from '@/api/layers'
import { useMergeFeatures } from '@/api/structure'
import { mergeObjection, mergeRowVersions, structureErrorMessage } from './structureTools'

interface MergeDialogProps {
  layerId: string
  projectId: string
  /** The selected features, in the order the list shows them. */
  fids: number[]
  /** The layer's fields, for the attribute values the choice is made on. */
  fields: LayerField[]
  onCancel: () => void
  /** The lead's fid, which is what the merged feature keeps. */
  onDone: (fid: number) => void
}

/** How many attributes a row shows before it stops. Enough to tell two objects apart. */
const PREVIEW_FIELDS = 6

/**
 * Picks the object whose attributes the merge keeps, and writes the merge.
 *
 * The lead is named explicitly and has no default: the contract has the client send
 * `leadFid` precisely because the user picked it, and the order of a selection is not a
 * decision. Pre-selecting the first row would turn that decision back into exactly the
 * accident the field exists to avoid, so "Zusammenführen" stays disabled until a row is
 * chosen.
 *
 * The attributes are shown, not merely the fids. "Objekt 42 oder Objekt 43" is not a
 * question anybody can answer; "Alte Landstraße oder Neue Landstraße" is.
 */
export function MergeDialog({
  layerId,
  projectId,
  fids,
  fields,
  onCancel,
  onDone,
}: MergeDialogProps) {
  const [leadFid, setLeadFid] = useState<number | null>(null)
  const merge = useMergeFeatures(layerId, projectId)

  // One request per feature, through the normal cache: Identify and the attribute table
  // fetch the very same query, so a feature that was just looked at costs nothing here.
  const results = useQueries({ queries: fids.map((fid) => featureDetailQuery(layerId, fid)) })
  const loading = results.some((result) => result.isPending)
  const loadFailed = results.some((result) => result.isError)
  const features = results
    .map((result) => result.data)
    .filter((feature): feature is Feature => feature !== undefined)

  const objection = loading ? null : mergeObjection(features.map((feature) => feature.geometry?.type))
  const previewFields = fields.slice(0, PREVIEW_FIELDS)
  const hiddenFields = fields.length - previewFields.length

  const blocked = loading || loadFailed || objection !== null || leadFid === null || merge.isPending

  async function confirm() {
    if (leadFid === null) return
    try {
      const result = await merge.mutateAsync({
        fids: features.map((feature) => feature.fid),
        leadFid,
        rowVersions: mergeRowVersions(features),
      })
      onDone(result.fid)
    } catch {
      // Shown below, from `merge.error`. Swallowed here only so an unhandled rejection
      // does not take the workspace down with it.
    }
  }

  return (
    <Dialog open onOpenChange={(next) => !next && onCancel()}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{formatCount(fids.length)} Objekte zusammenführen</DialogTitle>
          <DialogDescription>
            Wählen Sie das führende Objekt. Es behält seine Objektnummer und alle seine Attribute.
            Das Programm löscht die anderen Objekte und schreibt sofort auf dem Server. Sie können
            das nicht rückgängig machen.
          </DialogDescription>
        </DialogHeader>

        {loading && <p className="text-sm text-muted-foreground">Lädt…</p>}

        {loadFailed && (
          <p role="alert" className="text-sm text-destructive">
            Das Programm konnte nicht alle Objekte laden. Schließen Sie den Dialog und versuchen Sie
            es noch einmal.
          </p>
        )}

        {!loading && !loadFailed && (
          <div
            role="radiogroup"
            aria-label="Führendes Objekt"
            className="max-h-72 overflow-auto rounded-md border"
          >
            {features.map((feature) => (
              <label
                key={feature.fid}
                className="flex cursor-pointer items-start gap-2 border-b p-2 last:border-b-0 hover:bg-muted/40 has-checked:bg-muted/60"
              >
                <input
                  type="radio"
                  name="merge-lead"
                  className="mt-1 size-3.5 accent-foreground"
                  checked={leadFid === feature.fid}
                  onChange={() => setLeadFid(feature.fid)}
                  aria-label={`Objekt ${feature.fid} führt`}
                />
                <div className="min-w-0 flex-1">
                  <div className="text-xs font-medium">Objekt {feature.fid}</div>
                  <dl className="mt-1 grid grid-cols-[minmax(0,10rem)_minmax(0,1fr)] gap-x-2 text-xs">
                    {previewFields.map((field) => (
                      <div key={field.id} className="contents">
                        <dt className="truncate text-muted-foreground" title={field.sourceName}>
                          {field.sourceName}
                        </dt>
                        <dd className="truncate">{formatValue(feature.properties[field.columnName])}</dd>
                      </div>
                    ))}
                  </dl>
                  {hiddenFields > 0 && (
                    <p className="mt-1 text-[0.6875rem] text-muted-foreground">
                      und {formatCount(hiddenFields)} weitere Attribute
                    </p>
                  )}
                </div>
              </label>
            ))}
          </div>
        )}

        {/* The two client-side refusals carry the server's own wording, so the answer
            reads the same whichever side caught it -- see `mergeObjection`. */}
        {objection && (
          <p role="alert" className="text-sm text-destructive">
            {objection}
          </p>
        )}

        {merge.error && (
          <p role="alert" className="text-sm text-destructive">
            {structureErrorMessage(merge.error, 'merge')}
          </p>
        )}

        <DialogFooter>
          <Button variant="outline" disabled={merge.isPending} onClick={onCancel}>
            Abbrechen
          </Button>
          <Button disabled={blocked} onClick={() => void confirm()}>
            {merge.isPending ? 'Wird zusammengeführt…' : 'Zusammenführen'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

/**
 * One attribute value, formatted the way the attribute table formats it: numbers without
 * grouping separators (a year is not a quantity), and NULL stated rather than left blank,
 * because an empty cell and a missing value are different facts.
 */
function formatValue(value: string | number | boolean | null | undefined) {
  if (value === null || value === undefined) {
    return <span className="text-muted-foreground/60 italic">NULL</span>
  }
  return typeof value === 'number' ? formatAttributeNumber(value) : String(value)
}
