import { isMapImageLayer, useUpdateLayer, type LayerSummary } from '@/api/layers'
import { Slider } from '@/components/ui/slider'
import { formatCount, formatRelative } from '@/lib/format'
import { NumberInput, Row, Section } from '@/styling/controls'
import { useMapImageOpacityEditor } from './useMapImageOpacityEditor'
import { LAYER_ZOOM_MAX, LAYER_ZOOM_MIN, withMaxZoom, withMinZoom } from './zoomRange'

interface LayerPropertiesProps {
  layer: LayerSummary
  projectId: string
}

/**
 * Read-only facts plus the scale window for the selected layer (plan B.1 / Phase 4).
 *
 * Zoom bounds live on the layer itself, not in `layer.style` -- MapLibre applies them as
 * `minzoom`/`maxzoom` on every sublayer, independent of symbology.
 */
export function LayerProperties({ layer, projectId }: LayerPropertiesProps) {
  const updateLayer = useUpdateLayer(layer.id, projectId)
  // Called unconditionally regardless of layer kind -- Rules of Hooks -- see the hook's
  // own doc comment for why that is safe here.
  const opacityEditor = useMapImageOpacityEditor(layer, projectId)

  return (
    <div>
      <div className="flex h-7 items-center border-b bg-card px-2 text-xs font-medium tracking-wide uppercase text-muted-foreground">
        Eigenschaften
      </div>

      {/* A Kartenbild has no objects and no storage SRID (contract: "geometryType und
          srid sind null", "featureCount ist 0 und wird nicht angezeigt") -- the service
          address and the layers drawn from it stand in their place. */}
      {isMapImageLayer(layer) ? (
        <Section title="Kartenbild">
          <Row label="Dienst">
            <a
              href={layer.wms.serviceUrl}
              target="_blank"
              rel="noreferrer"
              className="text-xs underline underline-offset-2 hover:text-foreground"
              title={layer.wms.serviceUrl}
            >
              {layer.wms.serviceUrl}
            </a>
          </Row>
          <Row label="Layer">
            <span className="text-xs">{layer.wms.layers.join(', ')}</span>
          </Row>
          {/* No symbology for a Kartenbild -- opacity is the one paint property it has,
              so it lives here rather than behind a symbology panel it does not get
              (contract addendum: `style` for a WMS layer holds nothing else). */}
          <Row label="Deckkraft">
            <Slider
              value={opacityEditor.opacity}
              min={0}
              max={1}
              step={0.05}
              // Continuous while dragging, written once on release -- same rule
              // `SymbologyPanel`'s own Deckkraft slider follows.
              onValueChange={(opacity) => opacityEditor.apply(opacity, { defer: true })}
              onValueCommitted={(opacity) => opacityEditor.apply(opacity)}
              aria-label="Deckkraft des Kartenbilds"
            />
            <span className="w-9 shrink-0 text-right text-xs text-muted-foreground tabular-nums">
              {Math.round(opacityEditor.opacity * 100)} %
            </span>
          </Row>
        </Section>
      ) : (
        <Section title="Layer">
          <Row label="Objekte">
            <span className="text-xs tabular-nums">{formatCount(layer.featureCount)}</span>
          </Row>
          <Row label="CRS">
            <span className="text-xs tabular-nums">EPSG:{layer.srid}</span>
          </Row>
        </Section>
      )}

      {/* Only for a layer imported from the Geoportal Hamburg (CONTRACT.md phase 23,
          section 11.7) -- the licence's clause 2 requires attribution, licence name and
          link, and the fetch time, spelled out in full here rather than the shortened
          form the map's attribution line uses. `datasetId` and `featureIdField` are
          deliberately not shown -- they exist for a later stage's reconcile only. */}
      {layer.source && (
        <Section title="Herkunft">
          {/* Only where the service directory names an agency. `source` says the layer
              came from the Geoportal, `attribution` whether anybody is named in it --
              two separate questions (CONTRACT.md 11.7). A "Quelle" caption with nothing
              behind it claims the entry is missing rather than absent. */}
          {layer.source.attribution && (
            <Row label="Quelle">
              <span className="text-xs">{layer.source.attribution}</span>
            </Row>
          )}
          <Row label="Lizenz">
            <a
              href={layer.source.licenseUrl}
              target="_blank"
              rel="noreferrer"
              className="text-xs underline underline-offset-2 hover:text-foreground"
            >
              {layer.source.licenseName}
            </a>
          </Row>
          {layer.source.metadataUrl && (
            <Row label="Metadaten">
              <a
                href={layer.source.metadataUrl}
                target="_blank"
                rel="noreferrer"
                className="text-xs underline underline-offset-2 hover:text-foreground"
              >
                Metadatensatz
              </a>
            </Row>
          )}
          <Row label="Abgerufen">
            <span className="text-xs tabular-nums text-muted-foreground">
              {formatRelative(layer.source.fetchedAt)}
            </span>
          </Row>
        </Section>
      )}

      <Section title="Sichtbarkeit">
        <Row label="Zoom">
          <NumberInput
            label="von"
            value={layer.minZoom}
            min={LAYER_ZOOM_MIN}
            max={LAYER_ZOOM_MAX}
            onChange={(minZoom) => updateLayer.mutate(withMinZoom(minZoom, layer.maxZoom))}
          />
          <NumberInput
            label="bis"
            value={layer.maxZoom}
            min={LAYER_ZOOM_MIN}
            max={LAYER_ZOOM_MAX}
            onChange={(maxZoom) => updateLayer.mutate(withMaxZoom(layer.minZoom, maxZoom))}
          />
        </Row>
        <p className="text-xs text-muted-foreground">
          Außerhalb dieses Zoomfensters blendet das Programm den Layer aus.
        </p>
      </Section>
    </div>
  )
}
