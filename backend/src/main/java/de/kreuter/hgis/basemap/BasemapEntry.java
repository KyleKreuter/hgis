package de.kreuter.hgis.basemap;

import java.util.List;
import java.util.Map;

/**
 * One entry of the basemap catalog (VERTRAG.md "GET /api/basemaps") -- the backend's
 * single source of truth for what {@code frontend/src/map/basemap.ts}'s {@code BASEMAPS}
 * array and {@code backend/.../common/Basemap.java} each used to hardcode on their own.
 * {@link BasemapCatalog} holds the fixed list of these; nothing here is ever built from a
 * request.
 *
 * @param id unique, kebab-case, stable -- see {@link BasemapCatalog} for why the five
 *     original ids in particular must never change
 * @param title shown in the picker
 * @param hint one line under the title; explains what the entry actually is
 * @param group groups entries in the picker; one of {@link BasemapCatalog}'s
 *     {@code GROUP_*} constants
 * @param urlTemplate XYZ or WMTS-KVP tile URL template with {@code {z}}, {@code {x}},
 *     {@code {y}}; null only for {@code "none"}. Note that a WMTS template often puts
 *     {@code {y}} before {@code {x}} in the path -- intentional, not a typo, see the
 *     entries that do it
 * @param attribution license/credit text, in order; concatenating every part's
 *     {@code text} gives the notice the provider actually requires
 * @param minZoom lowest zoom the tile source actually serves
 * @param maxZoom highest zoom the tile source actually serves -- past this MapLibre would
 *     keep requesting tiles that 404
 * @param coverage {@code "DE"}, {@code "HH"}, {@code "EU"} or {@code "world"} -- only the
 *     hint shown in the picker, nothing technical
 * @param requiresAccount true for the nine Esri layers: they answer without a key and
 *     without a watermark, but Esri's terms require an ArcGIS account -- shown in the
 *     picker, not hidden and not blocked
 * @param deprecated true once a provider has announced retiring a service; nobody today,
 *     the field exists for the next time it happens
 * @param paint optional MapLibre raster-paint properties ({@code raster-saturation} and
 *     siblings), the same ones {@code osm-light}/{@code osm-dark} already carried in
 *     {@code basemap.ts} before this catalog existed; null when there is none
 */
public record BasemapEntry(
		String id,
		String title,
		String hint,
		String group,
		String urlTemplate,
		List<AttributionPart> attribution,
		int minZoom,
		int maxZoom,
		String coverage,
		boolean requiresAccount,
		boolean deprecated,
		Map<String, Object> paint) {
}
