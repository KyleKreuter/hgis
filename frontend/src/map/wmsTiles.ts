import type { RasterLayerSpecification, RasterSourceSpecification } from 'maplibre-gl'
import type { LayerWms, MapImageLayerSummary } from '@/api/layers'
import { numberOr } from '@/styling/defaults'

/**
 * Pixel size of one WMS GetMap tile -- baked into the request URL as `WIDTH`/`HEIGHT`
 * *and* declared as the source's `tileSize`. The two have to stay equal: `sharpenRaster.ts`
 * (used for a print export) infers how many pixels a tile actually holds purely from the
 * declared `tileSize` and asks for a deeper zoom level accordingly -- a mismatch would
 * not sharpen the image, it would stretch whatever came back to fit the wrong box
 * (measured while wiring up the export, see `sharpenRaster.test.ts`).
 */
export const WMS_TILE_SIZE = 256

/**
 * The GetMap address MapLibre re-issues per tile, MapLibre's own `{bbox-epsg-3857}`
 * token standing in for the tile's extent. Every other parameter is fixed: only WMS
 * 1.3.0 in EPSG:3857 is supported (wms-api-vertrag.md, measured against 30 services),
 * and `WIDTH`/`HEIGHT` always match {@link WMS_TILE_SIZE}.
 *
 * `serviceUrl` never carries a query string of its own (contract section 1: "die reine
 * Dienstadresse ohne Anfrageparameter", enforced by the backend on import), so a plain
 * `?` always starts this one.
 *
 * Neither `layers` nor `imageFormat` is percent-encoded: the string below is the one
 * measured against a live Hamburg service (wms-api-vertrag.md, "gemessen und
 * funktionierend") and every layer name in that catalog is a bare identifier
 * (`stadtplan`, `m2500_farbig`, …) with nothing a query string would need escaped.
 */
export function buildWmsGetMapUrl(wms: Pick<LayerWms, 'serviceUrl' | 'layers' | 'imageFormat'>): string {
  const layers = wms.layers.join(',')
  return (
    `${wms.serviceUrl}?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap` +
    `&LAYERS=${layers}&STYLES=&CRS=EPSG:3857&BBOX={bbox-epsg-3857}` +
    `&WIDTH=${WMS_TILE_SIZE}&HEIGHT=${WMS_TILE_SIZE}&FORMAT=${wms.imageFormat}&TRANSPARENT=TRUE`
  )
}

/**
 * The raster source for one Kartenbild. `minzoom`/`maxzoom` come from the layer, exactly
 * as a vector source's do (`syncLayers.ts`) -- the zoom window has to behave identically
 * for both kinds (plan Stufe 4).
 */
export function wmsSourceSpec(
  layer: Pick<MapImageLayerSummary, 'minZoom' | 'maxZoom'>,
  tileUrl: string,
): RasterSourceSpecification {
  return {
    type: 'raster',
    tiles: [tileUrl],
    tileSize: WMS_TILE_SIZE,
    minzoom: layer.minZoom,
    maxzoom: layer.maxZoom,
  }
}

/**
 * The one MapLibre layer a Kartenbild renders as. A single raster layer, unlike a
 * vector layer's up-to-four sublayers (`layerSpecsFor`) -- a WMS image has no
 * geometry-type split to draw.
 *
 * Deckkraft is `raster-opacity`, not `fill-opacity` (plan Stufe 4). There is no
 * symbology panel for a Kartenbild yet (contract: "style fehlt"), so `layer.style` is
 * normally absent and this reads MapLibre's own default of full opacity -- `style.opacity`
 * is still honoured if a later stage starts writing one, the same defaulting
 * `styleToMapLibre` uses for a vector layer's own opacity.
 */
export function wmsLayerSpec(layer: MapImageLayerSummary, sourceId: string): RasterLayerSpecification {
  return {
    id: `${sourceId}-render`,
    type: 'raster',
    source: sourceId,
    minzoom: layer.minZoom,
    maxzoom: layer.maxZoom,
    layout: { visibility: layer.visible ? 'visible' : 'none' },
    paint: { 'raster-opacity': numberOr(layer.style?.opacity, 1) },
  }
}
