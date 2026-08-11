import { useCallback } from 'react'
import { useQuery } from '@tanstack/react-query'
import { RotateCcw } from 'lucide-react'
import { layerDetailQuery, type LayerField, type LayerSummary } from '@/api/layers'
import { Button } from '@/components/ui/button'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Slider } from '@/components/ui/slider'
import { CategorizedEditor } from './CategorizedEditor'
import { Row, Section } from './controls'
import { defaultStyleFor, defaultSymbolFor, withPrimaryColor } from './defaults'
import { isNumericField } from './fields'
import { GraduatedEditor } from './GraduatedEditor'
import { LabelEditor } from './LabelEditor'
import { SymbolEditor } from './SymbolEditor'
import type { LabelStyle, LayerStyle, Renderer, RendererType } from './types'
import { useStyleEditor } from './useStyleEditor'

const RENDERER_LABELS: [RendererType, string][] = [
  ['single', 'Einzelsymbol'],
  ['categorized', 'Kategorisiert'],
  ['graduated', 'Abgestuft'],
]

/** Neutral grey for "everything the classification does not cover". */
const FALLBACK_COLOR = '#a3a3a3'

interface SymbologyPanelProps {
  layer: LayerSummary
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

  function switchRenderer(type: RendererType) {
    apply({ ...style, renderer: convertRenderer(style, type, layer.geometryType, fields) })
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
              <SelectValue />
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

/**
 * Switching the renderer keeps the symbol the user has already set up and only adds
 * what the new type needs. The classification itself is not carried over -- a field
 * chosen for categories rarely makes sense as a numeric class, and `field: ''` is the
 * signal the editors use to ask for one.
 */
function convertRenderer(
  style: LayerStyle,
  type: RendererType,
  geometryType: LayerSummary['geometryType'],
  fields: LayerField[],
): Renderer {
  const base = style.renderer.type === 'single' ? style.renderer.symbol : style.renderer.fallbackSymbol
  if (type === 'single') return { type: 'single', symbol: base }

  const fallbackSymbol = withPrimaryColor(defaultSymbolFor(geometryType), FALLBACK_COLOR)
  const field = 'field' in style.renderer ? style.renderer.field : ''

  if (type === 'categorized') {
    return { type: 'categorized', field, categories: [], fallbackSymbol }
  }
  // A graduated renderer on a text column is a guaranteed 400 from `/classify`.
  const numeric = fields.some((candidate) => candidate.sourceName === field && isNumericField(candidate))
  return { type: 'graduated', field: numeric ? field : '', classes: [], fallbackSymbol }
}
