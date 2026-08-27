import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Layers, Link2, RotateCcw } from 'lucide-react'
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
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Slider } from '@/components/ui/slider'
import { useBasemaps } from '@/api/basemaps'
import { layerKeys, useUpdateLayer, type LayerSummary } from '@/api/layers'
import { projectKeys, useUpdateProject, type ProjectDetail } from '@/api/projects'
import { cn } from '@/lib/utils'
import { basemapChange, isCustomBasemapUrl, resolveBasemap, validateBasemapUrlTemplate } from './basemap'
import { BasemapEntryDetails } from './BasemapEntryDetails'
import { CustomBasemapUrlField } from './CustomBasemapUrlField'
import { groupBasemaps } from './groupBasemaps'
import { hasLayerBasemapOverride, resolveBasemapSettings } from './resolveBasemapSettings'

/** How long the opacity slider may keep moving before the change is written out. */
const DEFER_MS = 400

interface BasemapControlProps {
  projectId: string
  /** The project's own background map and opacity -- what "Für dieses Projekt" reads and writes. */
  project: Pick<ProjectDetail, 'basemap' | 'basemapOpacity'>
  /** The active layer, or null while none is selected -- enables the "just this layer" scope. */
  activeLayer: LayerSummary | null
}

type Scope = 'project' | 'layer'

/**
 * Picks the background map, and the scope the pick applies to (CONTRACT.md phase 18).
 *
 * The control still writes straight to the query cache -- no draft of its own -- but now
 * to one of two places. "Für dieses Projekt" always means the project's own row, even
 * while a layer is active and overriding it on the map: redirecting it to the layer
 * instead would make one control write to two different places depending on state,
 * with nothing on screen to say which. "Nur für Layer X" is the explicit second target,
 * offered only while a layer is active.
 *
 * The catalog itself (VERTRAG.md) comes from `useBasemaps`, grouped into sections
 * (`groupBasemaps`) so ~35 entries stay scannable, plus one more entry that opens a
 * small dialog for a free-text tile URL -- see the comment at that menu item for why
 * it is a separate dialog and not one more radio choice with an input inside the menu.
 */
export function BasemapControl({ projectId, project, activeLayer }: BasemapControlProps) {
  const queryClient = useQueryClient()
  const updateProject = useUpdateProject(projectId)
  const updateLayer = useUpdateLayer(activeLayer?.id ?? '', projectId)
  const [scope, setScope] = useState<Scope>('project')

  // Already prefetched by the workspace route's loader (`ensureBasemapsLoaded`) --
  // never a request of its own.
  const { data: catalog = [] } = useBasemaps()
  const groups = groupBasemaps(catalog)

  const [customUrlDialogOpen, setCustomUrlDialogOpen] = useState(false)
  const [customUrlDraft, setCustomUrlDraft] = useState('')
  const [customUrlSubmitted, setCustomUrlSubmitted] = useState(false)

  // What is actually on the map for the active layer -- the trigger always shows this,
  // no matter which scope the picker itself happens to be open on.
  const effective = resolveBasemapSettings(activeLayer, project)
  const effectiveBasemap = resolveBasemap(catalog, effective.basemapId)

  const layerScoped = scope === 'layer' && activeLayer !== null
  // The layer's own value where it is set; the project's otherwise -- so opening the
  // picker on "Nur für Layer X" shows what the layer is actually inheriting, not a
  // blank slate.
  const scopedBasemapId = layerScoped ? (activeLayer!.basemap ?? project.basemap) : project.basemap
  const scopedOpacity = layerScoped ? (activeLayer!.basemapOpacity ?? project.basemapOpacity) : project.basemapOpacity
  const scopedBasemap = resolveBasemap(catalog, scopedBasemapId)

  // Deferred exactly like the symbology panel's own opacity slider: the map follows
  // every drag step, the server hears about it once the pointer settles or lets go.
  const pendingOpacity = useRef<number | null>(null)
  const opacityTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  // A keyboard-stepped slider can leave a write pending past the 400ms and into a
  // render where the scope or the active layer has already moved on (e.g. arrow-key
  // steps followed by picking a different layer row before the timer fires). The
  // deferred write reads through these refs instead of closing over this render's
  // values, the same guard `useStyleEditor` keeps for its own debounced save.
  const layerScopedRef = useRef(layerScoped)
  layerScopedRef.current = layerScoped
  const updateLayerRef = useRef(updateLayer)
  updateLayerRef.current = updateLayer
  const updateProjectRef = useRef(updateProject)
  updateProjectRef.current = updateProject

  function cancelPendingOpacity() {
    if (opacityTimer.current !== null) {
      clearTimeout(opacityTimer.current)
      opacityTimer.current = null
    }
    pendingOpacity.current = null
  }

  function writeOpacity(opacity: number) {
    if (layerScopedRef.current) {
      updateLayerRef.current.mutate(
        { basemapOpacity: opacity },
        { onError: () => toast.error('Das Programm konnte die Deckkraft des Layers nicht speichern') },
      )
      return
    }
    updateProjectRef.current.mutate(
      { basemapOpacity: opacity },
      { onError: () => toast.error('Das Programm konnte die Deckkraft nicht speichern') },
    )
  }

  function flushOpacity() {
    if (opacityTimer.current !== null) {
      clearTimeout(opacityTimer.current)
      opacityTimer.current = null
    }
    if (pendingOpacity.current === null) return
    const opacity = pendingOpacity.current
    pendingOpacity.current = null
    writeOpacity(opacity)
  }

  // Never re-runs (empty deps): closing the picker while a deferred change is still
  // waiting must not drop it, same reasoning as `useStyleEditor`'s own unmount flush.
  // Safe with a plain function rather than a `useCallback` here, because `flushOpacity`
  // and everything it calls read only through refs above -- whichever render's copy
  // this effect happens to capture behaves identically to any other.
  useEffect(() => flushOpacity, [])

  function changeOpacity(opacity: number, options: { defer?: boolean } = {}) {
    // Previews on the map immediately, same as `useUpdateProject`'s own optimistic
    // write would -- but without sending a request for every step of the drag.
    if (layerScoped) {
      queryClient.setQueryData<LayerSummary[]>(layerKeys.list(projectId), (current) =>
        current?.map((entry) => (entry.id === activeLayer!.id ? { ...entry, basemapOpacity: opacity } : entry)),
      )
    }
    else {
      queryClient.setQueryData<ProjectDetail>(projectKeys.detail(projectId), (current) =>
        current ? { ...current, basemapOpacity: opacity } : current,
      )
    }

    pendingOpacity.current = opacity
    if (opacityTimer.current !== null) clearTimeout(opacityTimer.current)
    if (options.defer) {
      opacityTimer.current = setTimeout(flushOpacity, DEFER_MS)
    }
    else {
      flushOpacity()
    }
  }

  function selectBasemap(chosen: string) {
    if (layerScoped) {
      const basemap = basemapChange(catalog, activeLayer!.basemap ?? null, chosen)
      if (!basemap) return
      updateLayer.mutate(
        { basemap },
        { onError: () => toast.error('Das Programm konnte die Hintergrundkarte des Layers nicht speichern') },
      )
      return
    }
    const basemap = basemapChange(catalog, project.basemap, chosen)
    if (!basemap) return
    updateProject.mutate(
      { basemap },
      { onError: () => toast.error('Das Programm konnte die Hintergrundkarte nicht speichern') },
    )
  }

  function openCustomUrlDialog() {
    // Prefills with the URL that is actually in effect for the current scope, so
    // reopening the dialog to tweak one character does not start from a blank field.
    setCustomUrlDraft(isCustomBasemapUrl(scopedBasemapId) ? (scopedBasemapId ?? '') : '')
    setCustomUrlSubmitted(false)
    setCustomUrlDialogOpen(true)
  }

  function applyCustomUrl(event: React.FormEvent) {
    event.preventDefault()
    setCustomUrlSubmitted(true)
    if (validateBasemapUrlTemplate(customUrlDraft) !== null) return
    selectBasemap(customUrlDraft)
    setCustomUrlDialogOpen(false)
  }

  function resetLayerBasemap() {
    updateLayer.mutate(
      { basemap: null },
      { onError: () => toast.error('Das Programm konnte die Hintergrundkarte des Layers nicht zurücksetzen') },
    )
  }

  function resetLayerOpacity() {
    cancelPendingOpacity()
    updateLayer.mutate(
      { basemapOpacity: null },
      { onError: () => toast.error('Das Programm konnte die Deckkraft des Layers nicht zurücksetzen') },
    )
  }

  return (
    <>
      <DropdownMenu
        onOpenChange={(open) => {
          // Opens on whichever scope is actually in effect, so a layer that already
          // overrides the project shows that override right away instead of hiding it
          // behind a second click.
          if (open) setScope(hasLayerBasemapOverride(activeLayer) ? 'layer' : 'project')
        }}
      >
        <DropdownMenuTrigger
          render={
            <Button
              variant="outline"
              size="sm"
              className="max-w-52 shadow-sm"
              aria-label={`Hintergrundkarte: ${effectiveBasemap.label}`}
            >
              <Layers />
              {/* Drops to the icon alone once the map panel gets narrow: the measurement
                  readout sits in the opposite corner, and the two must not meet. The
                  aria-label above carries the name either way. */}
              <span className="truncate @max-sm:hidden">{effectiveBasemap.label}</span>
            </Button>
          }
        />
        {/* Aligned to the trigger's right edge: the control sits in the top right corner,
            so the popup has to grow inwards over the map, not off it. Wider than before
            (w-72, was w-64): the catalog grew from five entries to about 35 across six
            groups, and the status hints (`BasemapEntryDetails`) need the extra room to
            stay on one line. */}
        <DropdownMenuContent align="end" className="w-72">
          {activeLayer && (
            <>
              {/* Every label is a group part in Base UI (`MenuPrimitive.GroupLabel`) and
                  throws "MenuGroupContext is missing" outside a group -- which takes the
                  whole workspace down, not just the menu. A label therefore always sits
                  inside a `DropdownMenuGroup` or a `DropdownMenuRadioGroup`. */}
              <DropdownMenuGroup>
                <DropdownMenuLabel>Geltungsbereich</DropdownMenuLabel>
                <div className="grid grid-cols-2 gap-1 px-1.5 pb-1.5">
                  <ScopeButton active={!layerScoped} onClick={() => setScope('project')}>
                    Für dieses Projekt
                  </ScopeButton>
                  <ScopeButton active={layerScoped} onClick={() => setScope('layer')}>
                    Nur für Layer „{activeLayer.name}"
                  </ScopeButton>
                </div>
              </DropdownMenuGroup>
              <DropdownMenuSeparator />
            </>
          )}

          <DropdownMenuRadioGroup value={scopedBasemap.id} onValueChange={(value: string) => selectBasemap(value)}>
            {groups.map((group, index) => (
              <div key={group.group}>
                {index > 0 && <DropdownMenuSeparator />}
                <DropdownMenuLabel>{group.group}</DropdownMenuLabel>
                {group.entries.map((entry) => (
                  <DropdownMenuRadioItem key={entry.id} value={entry.id} className="items-start">
                    <BasemapEntryDetails entry={entry} />
                  </DropdownMenuRadioItem>
                ))}
              </div>
            ))}
          </DropdownMenuRadioGroup>

          <DropdownMenuSeparator />
          {/* A plain menu item that opens a separate <Dialog> below, not one more
              DropdownMenuRadioItem with a text field living inside it: Base UI's Menu
              runs its own type-ahead on every single-character keydown while open and
              calls preventDefault() unconditionally (useTypeahead in @base-ui/react,
              no check of the actual event target), so a <Input> mounted inside this
              still-open popup would never receive a character a user types into it --
              confirmed by an actual failing test, not a guess, before this was written
              this way. Do not try the inline field again without first checking
              whether that still holds. */}
          <DropdownMenuItem onClick={openCustomUrlDialog}>
            <Link2 className="size-3.5" />
            <span className="flex flex-col">
              Eigene Kachel-URL…
              {isCustomBasemapUrl(scopedBasemapId) && (
                <span className="truncate text-xs text-muted-foreground">Aktuell: {scopedBasemapId}</span>
              )}
            </span>
          </DropdownMenuItem>

          {layerScoped && activeLayer!.basemap != null && (
            <DropdownMenuItem onClick={resetLayerBasemap}>
              <RotateCcw className="size-3.5" />
              Karte des Projekts verwenden
            </DropdownMenuItem>
          )}

          {/* "Keine Hintergrundkarte" has no raster layer to carry an opacity, so the
              slider would sit there without effect -- hidden instead. */}
          {scopedBasemap.id !== 'none' && (
            <>
              <DropdownMenuSeparator />
              <DropdownMenuGroup>
                <DropdownMenuLabel>Deckkraft</DropdownMenuLabel>
                <div className="flex items-center gap-1.5 px-1.5 pb-1.5">
                <Slider
                  value={scopedOpacity}
                  min={0}
                  max={1}
                  step={0.05}
                  onValueChange={(opacity) => changeOpacity(opacity, { defer: true })}
                  onValueCommitted={(opacity) => changeOpacity(opacity)}
                  aria-label="Deckkraft der Hintergrundkarte"
                />
                <span className="w-9 shrink-0 text-right text-xs text-muted-foreground tabular-nums">
                  {Math.round(scopedOpacity * 100)} %
                </span>
                {layerScoped && activeLayer!.basemapOpacity != null && (
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    className="size-5 shrink-0"
                    title="Deckkraft des Projekts verwenden"
                    aria-label="Deckkraft des Projekts verwenden"
                    onClick={resetLayerOpacity}
                  >
                    <RotateCcw className="size-3" />
                  </Button>
                )}
                </div>
              </DropdownMenuGroup>
            </>
          )}
        </DropdownMenuContent>
      </DropdownMenu>

      <Dialog open={customUrlDialogOpen} onOpenChange={setCustomUrlDialogOpen}>
        <DialogContent className="sm:max-w-md">
          <form onSubmit={applyCustomUrl}>
            <DialogHeader>
              <DialogTitle>Eigene Kachel-URL</DialogTitle>
              <DialogDescription>
                Gilt für {layerScoped ? `Layer „${activeLayer?.name}"` : 'dieses Projekt'}.
              </DialogDescription>
            </DialogHeader>
            <div className="py-4">
              <CustomBasemapUrlField
                value={customUrlDraft}
                onChange={setCustomUrlDraft}
                showError={customUrlSubmitted}
                autoFocus
              />
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setCustomUrlDialogOpen(false)}>
                Abbrechen
              </Button>
              <Button type="submit">Übernehmen</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  )
}

/**
 * One half of the scope switch. A plain button, not a `DropdownMenuItem`: an item
 * closes the whole picker on click, and switching scope must keep it open -- the point
 * is to then pick a map or move the slider for that scope in the same session.
 */
function ScopeButton({
  active,
  onClick,
  children,
}: {
  active: boolean
  onClick: () => void
  children: ReactNode
}) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      className={cn(
        'truncate rounded-md px-1.5 py-1 text-xs outline-none focus-visible:ring-3 focus-visible:ring-ring/50',
        active ? 'bg-accent font-medium text-accent-foreground' : 'text-muted-foreground hover:bg-accent/50',
      )}
    >
      {children}
    </button>
  )
}
