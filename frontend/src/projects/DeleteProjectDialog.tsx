import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  deletionImpactQuery,
  useDeleteProject,
  type ProjectSummary,
} from '@/api/projects'
import { formatCount } from '@/lib/format'

interface DeleteProjectDialogProps {
  project: ProjectSummary | null
  onOpenChange: (open: boolean) => void
}

/**
 * Deleting a project drops the physical layer tables, so the barrier scales with what
 * is actually at stake: an empty project needs one confirmation, a project holding data
 * requires typing its name. Putting the same hurdle everywhere would only train people
 * to click through it.
 */
export function DeleteProjectDialog({ project, onOpenChange }: DeleteProjectDialogProps) {
  const [confirmation, setConfirmation] = useState('')
  const deleteProject = useDeleteProject()

  const { data: impact } = useQuery({
    ...deletionImpactQuery(project?.id ?? ''),
    enabled: Boolean(project),
  })

  const hasData = (impact?.layerCount ?? 0) > 0
  const canDelete = !hasData || confirmation.trim() === project?.name

  async function handleDelete() {
    if (!project) return
    try {
      await deleteProject.mutateAsync(project.id)
      toast.success(`Projekt „${project.name}" gelöscht`)
      close()
    } catch {
      toast.error('Das Programm konnte das Projekt nicht löschen')
    }
  }

  function close() {
    setConfirmation('')
    onOpenChange(false)
  }

  return (
    <AlertDialog open={Boolean(project)} onOpenChange={(next) => !next && close()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Projekt löschen?</AlertDialogTitle>
          <AlertDialogDescription>
            Sie löschen „{project?.name}" endgültig. Das lässt sich nicht rückgängig
            machen.
          </AlertDialogDescription>
          {hasData && impact && (
            <p className="text-sm font-medium">
              Dabei gehen {formatCount(impact.layerCount)}{' '}
              {impact.layerCount === 1 ? 'Layer' : 'Layer'} mit insgesamt{' '}
              {formatCount(impact.featureCount)} Objekten verloren.
            </p>
          )}
        </AlertDialogHeader>

        {hasData && (
          <div className="grid gap-1.5">
            <Label htmlFor="delete-confirm">
              Geben Sie zum Bestätigen den Projektnamen ein
            </Label>
            <Input
              id="delete-confirm"
              value={confirmation}
              onChange={(e) => setConfirmation(e.target.value)}
              placeholder={project?.name}
              autoComplete="off"
            />
          </div>
        )}

        <AlertDialogFooter>
          <AlertDialogCancel disabled={deleteProject.isPending}>Abbrechen</AlertDialogCancel>
          <AlertDialogAction
            onClick={(event) => {
              event.preventDefault()
              handleDelete()
            }}
            disabled={!canDelete || deleteProject.isPending}
          >
            {deleteProject.isPending ? 'Wird gelöscht…' : 'Endgültig löschen'}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
