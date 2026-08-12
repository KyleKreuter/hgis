import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { ApiError } from '@/api/client'
import { useUpdateProject, type ProjectSummary } from '@/api/projects'

interface RenameProjectDialogProps {
  project: ProjectSummary | null
  onOpenChange: (open: boolean) => void
}

export function RenameProjectDialog({ project, onOpenChange }: RenameProjectDialogProps) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [nameError, setNameError] = useState<string>()

  const updateProject = useUpdateProject(project?.id ?? '')

  // Refill whenever a different project is picked from the list.
  useEffect(() => {
    if (project) {
      setName(project.name)
      setDescription(project.description ?? '')
      setNameError(undefined)
    }
  }, [project])

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    if (!project) return
    setNameError(undefined)

    try {
      await updateProject.mutateAsync({ name, description })
      toast.success('Projekt aktualisiert')
      onOpenChange(false)
    } catch (error) {
      if (error instanceof ApiError) {
        setNameError(error.fieldError('name'))
        if (!error.fieldError('name')) toast.error(error.message)
      } else {
        toast.error('Das Programm konnte die Änderung nicht speichern')
      }
    }
  }

  return (
    <Dialog open={Boolean(project)} onOpenChange={(next) => !next && onOpenChange(false)}>
      <DialogContent className="sm:max-w-lg">
        <form onSubmit={handleSubmit}>
          <DialogHeader>
            <DialogTitle>Projekt bearbeiten</DialogTitle>
          </DialogHeader>

          <div className="grid gap-4 py-4">
            <div className="grid gap-1.5">
              <Label htmlFor="rename-name">Name</Label>
              <Input
                id="rename-name"
                value={name}
                onChange={(e) => setName(e.target.value)}
                autoFocus
                aria-invalid={nameError ? true : undefined}
              />
              {nameError && <p className="text-xs text-destructive">{nameError}</p>}
            </div>

            <div className="grid gap-1.5">
              <Label htmlFor="rename-description">Beschreibung</Label>
              <Textarea
                id="rename-description"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                rows={2}
              />
            </div>
          </div>

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => onOpenChange(false)}
              disabled={updateProject.isPending}
            >
              Abbrechen
            </Button>
            <Button type="submit" disabled={!name.trim() || updateProject.isPending}>
              {updateProject.isPending ? 'Wird gespeichert…' : 'Speichern'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
