import { useCallback } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { RotateCcw } from 'lucide-react'
import { layerDetailQuery, type VectorLayerSummary } from '@/api/layers'
import { Button } from '@/components/ui/button'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Slider } from '@/components/ui/slider'
import { CategorizedEditor } from './CategorizedEditor'
import {
  initialCategorizedPalette,
  initialGraduatedControls,
  requestCategorizedCategories,
  requestGraduatedClasses,
} from './classification'
import { Row, Section } from './controls'
import { defaultStyleFor } from './defaults'
import { GraduatedEditor } from './GraduatedEditor'
import { HeatmapEditor } from './HeatmapEditor'
import { LabelEditor } from './LabelEditor'
import { RENDERER_LABELS, labelOf } from './labels'
import { convertRenderer } from './renderer'
import { SymbolEditor } from './SymbolEditor'
import type { LabelStyle, LayerStyle, Renderer, RendererType } from './types'
import { useStyleEditor } from './useStyleEditor'

interface SymbologyPanelProps {
  layer: VectorLayerSummary
  projectId: string
}

/**
 * Symbology of the selected layer (plan section C.3).
 *
 * Holds no draft of its own: what it renders is the style in the layer list cache, and
 * that is the same value the map reads -- see `useStyleEditor` for why the request is
 * the only thing that lags behind.
 */
export function SymbologyPanel({ layer, projectId }: SymbologyPanelProps) {
  const queryClient = useQueryClient()
  const { data: detail } = useQuery(layerDetailQuery(layer.id))
  const { style: stored, apply } = useStyleEditor(layer, projectId)
  const fields = detail?.fields ?? []

  // A layer without a style renders exactly like this one, so opening the panel changes
  // nothing on the map -- and nothing is written until something is actually changed.
  const style = stored ?? defaultStyleFor(layer.geometryType)

  const setRenderer = useCallback(
    (renderer: Renderer, options?: { defer?: boolean }) => apply({ ...style, renderer }, options),
    [apply, style],
  )

  function setLabels(labels: LabelStyle | null, options?: { defer?: boolean }) {
    apply({ ...style, labels }, options)
  }

  /**
   * `convertRenderer` can carry a field over with nothing classified yet -- switching
   * from categorized to graduated keeps a numeric field, switching between either and
   * back keeps it too (see `renderer.ts`). Classifying that field is part of this same
   * action, not a background effect: `GraduatedEditor`/`CategorizedEditor` no longer
   * watch state on their own, on purpose (CONTRACT.md, package B1) -- an effect that
   * did would be exactly the pattern that turned "open the panel" into "lose the saved
   * classes".
   */
  async function switchRenderer(type: RendererType) {
    const base: LayerStyle = { ...style, renderer: convertRenderer(style, type, layer.geometryType, fields) }
    apply(base)
    const renderer = base.renderer

    if (renderer.type === 'graduated' && renderer.field) {
      const { method, classCount, ramp } = initialGraduatedControls(renderer, renderer.classes ?? [])
      try {
        const { classes } = await requestGraduatedClasses(
          queryClient,
          layer.id,
          layer.geometryType,
          renderer.field,
          method,
          classCount,
          ramp,
          renderer.classes ?? [],
          renderer.fallbackSymbol,
        )
        apply({ ...base, renderer: { ...renderer, classes } })
      }
      catch {
        // The field stays selected with an empty class list; GraduatedEditor's own
        // controls (Methode, Klassen, Farbverlauf) offer a retry.
      }
    }

    if (renderer.type === 'categorized' && renderer.field) {
      try {
        const { categories } = await requestCategorizedCategories(
          queryClient,
          layer.id,
          layer.geometryType,
          renderer.field,
          initialCategorizedPalette(renderer),
          renderer.categories ?? [],
          renderer.fallbackSymbol,
        )
        apply({ ...base, renderer: { ...renderer, categories } })
      }
      catch {
        // Same as above: the field stays selected, CategorizedEditor's own Feld picker
        // offers a retry.
      }
    }
  }

  return (
    <div>
      <div className="sticky top-0 z-10 flex h-7 items-center gap-2 border-b bg-card px-2 text-xs font-medium tracking-wide uppercase text-muted-foreground">
        <span className="truncate">Symbologie</span>
        <Button
          variant="ghost"
          size="icon-sm"
          className="ml-auto size-5"
          disabled={stored === null}
          title="Auf die Standarddarstellung zurücksetzen"
          aria-label="Symbologie zurücksetzen"
          onClick={() => apply(null)}
        >
          <RotateCcw className="size-3.5" />
        </Button>
      </div>

      <Section title="Darstellung">
        <Row label="Typ">
          <Select value={style.renderer.type} onValueChange={(value) => switchRenderer(value as RendererType)}>
            <SelectTrigger size="sm" className="w-full">
              <SelectValue>{(value: string) => labelOf(RENDERER_LABELS, value)}</SelectValue>
            </SelectTrigger>
            <SelectContent>
              {RENDERER_LABELS.map(([value, label]) => (
                <SelectItem key={value} value={value}>
                  {label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </Row>

        {style.renderer.type === 'single' && (
          <SymbolEditor
            symbol={style.renderer.symbol}
            onChange={(symbol, options) => setRenderer({ type: 'single', symbol }, options)}
          />
        )}

        {style.renderer.type === 'categorized' && (
          <CategorizedEditor
            layerId={layer.id}
            geometryType={layer.geometryType}
            renderer={style.renderer}
            fields={fields}
            onChange={setRenderer}
          />
        )}

        {style.renderer.type === 'graduated' && (
          <GraduatedEditor
            layerId={layer.id}
            geometryType={layer.geometryType}
            renderer={style.renderer}
            fields={fields}
            onChange={setRenderer}
          />
        )}

        {style.renderer.type === 'heatmap' && (
          <HeatmapEditor layerId={layer.id} renderer={style.renderer} fields={fields} onChange={setRenderer} />
        )}

        <Row label="Deckkraft">
          <Slider
            value={style.opacity}
            min={0}
            max={1}
            step={0.05}
            // Continuous while dragging, written once on release: the map follows every
            // step, the server hears about it once.
            onValueChange={(opacity) => apply({ ...style, opacity }, { defer: true })}
            onValueCommitted={(opacity) => apply({ ...style, opacity })}
            aria-label="Deckkraft des Layers"
          />
          <span className="w-9 shrink-0 text-right text-xs text-muted-foreground tabular-nums">
            {Math.round(style.opacity * 100)} %
          </span>
        </Row>
      </Section>

      <Section title="Beschriftung">
        <LabelEditor labels={style.labels} fields={fields} onChange={setLabels} />
      </Section>
    </div>
  )
}
