import { useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { toast } from 'sonner'
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
import { Textarea } from '@/components/ui/textarea'
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectSeparator,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { ApiError } from '@/api/client'
import { useBasemaps } from '@/api/basemaps'
import { useCreateProject } from '@/api/projects'
import { DEFAULT_BASEMAP_ID, resolveBasemap, validateBasemapUrlTemplate } from '@/map/basemap'
import { BasemapEntryDetails } from '@/map/BasemapEntryDetails'
import { CUSTOM_BASEMAP_OPTION, CustomBasemapUrlField } from '@/map/CustomBasemapUrlField'
import { groupBasemaps } from '@/map/groupBasemaps'

/**
 * Common storage CRS. The backend validates against spatial_ref_sys, so any EPSG code
 * PROJ knows would work -- this list just covers the cases that come up in practice.
 */
const CRS_OPTIONS = [
  { srid: 25832, short: 'EPSG:25832 - UTM 32N', hint: 'Deutschland West, metrisch' },
  { srid: 25833, short: 'EPSG:25833 - UTM 33N', hint: 'Deutschland Ost, metrisch' },
  { srid: 4326, short: 'EPSG:4326 - WGS 84', hint: 'weltweit, Grad' },
  { srid: 3857, short: 'EPSG:3857 - Web Mercator', hint: 'renderfertig, verzerrte Flächen' },
]

function crsLabel(srid: string): string {
  return CRS_OPTIONS.find((option) => String(option.srid) === srid)?.short ?? srid
}

interface CreateProjectDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

export function CreateProjectDialog({ open, onOpenChange }: CreateProjectDialogProps) {
  const navigate = useNavigate()
  const createProject = useCreateProject()
  // Already prefetched by the project browser route's loader (`ensureBasemapsLoaded`).
  const { data: catalog = [] } = useBasemaps()
  const groups = groupBasemaps(catalog)

  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [srid, setSrid] = useState('25832')
  const [basemap, setBasemap] = useState<string>(DEFAULT_BASEMAP_ID)
  const [customUrl, setCustomUrl] = useState('')
  const [customUrlSubmitted, setCustomUrlSubmitted] = useState(false)
  const [nameError, setNameError] = useState<string>()

  const isCustomBasemap = basemap === CUSTOM_BASEMAP_OPTION

  function reset() {
    setName('')
    setDescription('')
    setSrid('25832')
    setBasemap(DEFAULT_BASEMAP_ID)
    setCustomUrl('')
    setCustomUrlSubmitted(false)
    setNameError(undefined)
  }

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    setNameError(undefined)

    if (isCustomBasemap) {
      setCustomUrlSubmitted(true)
      if (validateBasemapUrlTemplate(customUrl) !== null) return
    }

    try {
      const project = await createProject.mutateAsync({
        name,
        description: description.trim() || undefined,
        srid: Number(srid),
        basemap: isCustomBasemap ? customUrl : basemap,
      })
      toast.success(`Projekt „${project.name}" angelegt`)
      reset()
      onOpenChange(false)
      navigate({ to: '/projects/$projectId', params: { projectId: project.id } })
    } catch (error) {
      if (error instanceof ApiError) {
        // Field level message if the backend flagged one, otherwise the general detail.
        setNameError(error.fieldError('name'))
        if (!error.fieldError('name')) toast.error(error.message)
      } else {
        toast.error('Das Programm konnte das Projekt nicht anlegen')
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
            <DialogTitle>Neues Projekt</DialogTitle>
            <DialogDescription>
              Ein Projekt bündelt Layer, deren Darstellung und den zuletzt betrachteten
              Kartenausschnitt.
            </DialogDescription>
          </DialogHeader>

          <div className="grid gap-4 py-4">
            <div className="grid gap-1.5">
              <Label htmlFor="project-name">Name</Label>
              <Input
                id="project-name"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="z. B. Kataster Musterstadt"
                autoFocus
                aria-invalid={nameError ? true : undefined}
              />
              {nameError && <p className="text-xs text-destructive">{nameError}</p>}
            </div>

            <div className="grid gap-1.5">
              <Label htmlFor="project-description">Beschreibung</Label>
              <Textarea
                id="project-description"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="Optional"
                rows={2}
              />
            </div>

            <div className="grid gap-1.5">
              <Label htmlFor="project-crs">Koordinatensystem</Label>
              {/* Base UI hands back string | null; null means "cleared", which this
                  select never allows -- so keep the previous value. */}
              <Select value={srid} onValueChange={(value) => value && setSrid(value)}>
                <SelectTrigger id="project-crs" className="w-full">
                  {/* Base UI renders the raw value unless given a formatter. */}
                  <SelectValue>{(value: string) => crsLabel(value)}</SelectValue>
                </SelectTrigger>
                <SelectContent>
                  {CRS_OPTIONS.map((option) => (
                    <SelectItem key={option.srid} value={String(option.srid)}>
                      <span className="flex flex-col items-start">
                        <span>{option.short}</span>
                        <span className="text-xs text-muted-foreground">{option.hint}</span>
                      </span>
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <p className="text-xs text-muted-foreground">
                Das Koordinatensystem lässt sich nach dem Anlegen nicht mehr ändern. Ein
                Wechsel müsste jede Layertabelle neu schreiben.
              </p>
            </div>

            {/* The same catalog the map's picker offers (`GET /api/basemaps`), so a
                project can start on the right background instead of on OSM and being
                corrected afterwards. Changeable at any time -- unlike the CRS above. */}
            <div className="grid gap-1.5">
              <Label htmlFor="project-basemap">Hintergrundkarte</Label>
              <Select value={basemap} onValueChange={(value) => value && setBasemap(value)}>
                <SelectTrigger id="project-basemap" className="w-full">
                  <SelectValue>
                    {(value: string) =>
                      value === CUSTOM_BASEMAP_OPTION ? 'Eigene Kachel-URL' : resolveBasemap(catalog, value).label
                    }
                  </SelectValue>
                </SelectTrigger>
                <SelectContent>
                  {groups.map((group) => (
                    <SelectGroup key={group.group}>
                      <SelectLabel>{group.group}</SelectLabel>
                      {group.entries.map((entry) => (
                        <SelectItem key={entry.id} value={entry.id}>
                          <BasemapEntryDetails entry={entry} />
                        </SelectItem>
                      ))}
                    </SelectGroup>
                  ))}
                  <SelectSeparator />
                  <SelectItem value={CUSTOM_BASEMAP_OPTION}>Eigene Kachel-URL…</SelectItem>
                </SelectContent>
              </Select>
            </div>

            {isCustomBasemap && (
              <CustomBasemapUrlField
                value={customUrl}
                onChange={setCustomUrl}
                id="project-basemap-custom-url"
                showError={customUrlSubmitted}
              />
            )}
          </div>

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => onOpenChange(false)}
              disabled={createProject.isPending}
            >
              Abbrechen
            </Button>
            <Button type="submit" disabled={!name.trim() || createProject.isPending}>
              {createProject.isPending ? 'Wird angelegt…' : 'Anlegen'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
