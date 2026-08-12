import { useEffect, useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { toast } from 'sonner'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
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
import { Progress, ProgressLabel, ProgressValue } from '@/components/ui/progress'
import { ApiError } from '@/api/client'
import { isJobFinished, useJob } from '@/api/imports'
import {
  projectKeys,
  useDuplicateProject,
  type ProjectSummary,
} from '@/api/projects'
import { useQueryClient } from '@tanstack/react-query'
import { duplicateNameInput } from './duplicate'

interface Props {
  project: ProjectSummary | null
  onOpenChange: (open: boolean) => void
}

export function DuplicateProjectDialog({ project, onOpenChange }: Props) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const duplicate = useDuplicateProject(project?.id ?? '')
  const [name, setName] = useState('')
  const [jobId, setJobId] = useState<string | null>(null)
  const [error, setError] = useState<string>()
  const { data: job } = useJob(jobId)
  const running = jobId !== null && !isJobFinished(job?.status)

  useEffect(() => {
    if (project && !jobId) setName(`${project.name} (Kopie)`)
  }, [project, jobId])

  useEffect(() => {
    if (job?.status !== 'SUCCEEDED') return
    queryClient.invalidateQueries({ queryKey: projectKeys.all })
    if (job.outputProjectId) {
      toast.success('Projekt dupliziert')
      onOpenChange(false)
      navigate({ to: '/projects/$projectId', params: { projectId: job.outputProjectId } })
    } else {
      setError('Das Programm hat die Kopie erstellt, aber die Projekt-ID fehlt. Die Projektliste ist aktuell.')
    }
  }, [job?.status, job?.outputProjectId, navigate, onOpenChange, queryClient])

  function close() {
    if (running) toast.info('Die Projektduplizierung läuft im Hintergrund weiter.')
    setJobId(null)
    setError(undefined)
    onOpenChange(false)
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (!project) return
    setError(undefined)
    try {
      setJobId((await duplicate.mutateAsync(duplicateNameInput(project.name, name))).id)
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : 'Das Programm konnte das Projekt nicht duplizieren')
    }
  }

  const percent =
    job?.totalCount && job.totalCount > 0
      ? Math.min(100, Math.round((job.processedCount / job.totalCount) * 100))
      : null

  return (
    <Dialog open={Boolean(project)} onOpenChange={(next) => !next && close()}>
      <DialogContent className="sm:max-w-md">
        <form onSubmit={submit}>
          <DialogHeader>
            <DialogTitle>Projekt duplizieren</DialogTitle>
            <DialogDescription>
              Das Programm kopiert Layer, Daten und Darstellung von „{project?.name}" in ein neues
              Projekt.
            </DialogDescription>
          </DialogHeader>
          <div className="grid gap-4 py-4">
            {jobId ? (
              job?.status === 'FAILED' ? (
                <Alert variant="destructive">
                  <AlertTitle>Duplizierung fehlgeschlagen</AlertTitle>
                  <AlertDescription>{job.message ?? 'Das Programm hat die Kopie vollständig aufgeräumt'}</AlertDescription>
                </Alert>
              ) : job?.status === 'SUCCEEDED' ? (
                <Alert><AlertTitle>Duplizierung abgeschlossen</AlertTitle></Alert>
              ) : percent === null ? (
                <p className="text-sm text-muted-foreground">Projekt wird vorbereitet…</p>
              ) : (
                <Progress value={percent}>
                  <ProgressLabel>Objekte werden kopiert</ProgressLabel>
                  <ProgressValue>{() => `${job?.processedCount ?? 0} / ${job?.totalCount ?? 0}`}</ProgressValue>
                </Progress>
              )
            ) : (
              <div className="grid gap-1.5">
                <Label htmlFor="duplicate-name">Name</Label>
                <Input id="duplicate-name" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
              </div>
            )}
            {error && (
              <Alert variant="destructive">
                <AlertTitle>Duplizierung nicht gestartet</AlertTitle>
                <AlertDescription>{error}</AlertDescription>
              </Alert>
            )}
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={close}>
              {running ? 'Im Hintergrund fortsetzen' : 'Schließen'}
            </Button>
            {!jobId && (
              <Button type="submit" disabled={!name.trim() || duplicate.isPending}>
                {duplicate.isPending ? 'Wird gestartet…' : 'Duplizieren'}
              </Button>
            )}
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
