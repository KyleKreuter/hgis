"""The basemap catalog -- what draws under every layer of every project.

One source of truth on the server (``GET /api/basemaps``, VERTRAG.md phase
18/23): the picker in the frontend renders from it, and it is what a
project's or layer's ``basemap`` field refers to when it holds a catalog id
rather than an own tile URL. See :meth:`hgis.client.Client.basemaps`.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class BasemapAttribution:
    """
    One run of attribution text for a :class:`Basemap`, optionally linked.

    Concatenating every part's ``text`` in order, in this order, gives the
    notice the tile provider requires, verbatim -- split into runs because the
    licences ask for a *link* to the project and to the licence, not merely
    for their names in prose.

    :param href: absolute https URL, or None for a run that is only text
    """

    text: str
    href: str | None = None


@dataclass(frozen=True)
class Basemap:
    """
    One entry of the basemap catalog, exactly as ``GET /api/basemaps``
    returns it -- see :meth:`hgis.client.Client.basemaps`.

    :param id: kebab-case, unique, stable -- what
        :meth:`hgis.project.Project.update`'s ``basemap`` and
        :meth:`hgis.layer.Layer.update`'s ``basemap`` accept as the catalog
        case, and what :attr:`hgis.project.Project.basemap`/
        :attr:`hgis.layer.Layer.basemap` read back
    :param title: shown in a picker
    :param hint: one line explaining what this entry actually is
    :param group: the section this entry belongs to, e.g. "Deutschland" or
        "Luft- und Satellitenbild" -- for grouping a catalog large enough
        that a flat list stops being useful
    :param url_template: the tile URL, in one of two forms. Form A carries
        ``{z}``, ``{x}``, ``{y}`` (XYZ or WMTS -- WMTS often puts ``{y}``
        before ``{x}`` in the path, which is intentional, not a mix-up).
        Form B carries ``{bbox-epsg-3857}`` in their place instead, a WMS
        ``GetMap`` URL, e.g. the Hamburg aerial imagery: ``https://
        geodienste.hamburg.de/wms_dop_zeitreihe_unbelaubt?...&BBOX=
        {bbox-epsg-3857}&...`` (see
        :func:`hgis.mcp.write_tools.set_basemap` for the full, real URL).
        Both are ordinary raster sources to MapLibre. None for the "keine
        Hintergrundkarte" entry, which draws nothing
    :param attribution: the notice this tile provider requires, as ordered
        runs -- see :class:`BasemapAttribution`. Empty for the "keine
        Hintergrundkarte" entry: crediting a provider for data that is not on
        screen would be its own kind of wrong
    :param coverage: "DE", "HH", "EU" or "world" -- informational, not
        enforced by anything in this library
    :param requires_account: true for a service that needs its own key or
        login to actually load tiles. Shown as such, not hidden and not
        blocked -- the same choice the frontend's picker makes
    :param deprecated: true once the provider has announced this service's
        retirement
    :param paint: MapLibre raster paint properties for a display variant of
        another entry's tiles (a darkened or desaturated OSM, say), or None
    """

    id: str
    title: str
    hint: str
    group: str
    url_template: str | None
    attribution: list[BasemapAttribution]
    min_zoom: int
    max_zoom: int
    coverage: str
    requires_account: bool
    deprecated: bool
    paint: dict[str, Any] | None


def _to_basemap(data: dict[str, Any]) -> Basemap:
    return Basemap(
        id=data["id"],
        title=data["title"],
        hint=data["hint"],
        group=data["group"],
        url_template=data.get("urlTemplate"),
        attribution=[
            BasemapAttribution(text=item["text"], href=item.get("href"))
            for item in data.get("attribution") or []
        ],
        min_zoom=data["minZoom"],
        max_zoom=data["maxZoom"],
        coverage=data["coverage"],
        requires_account=data.get("requiresAccount", False),
        deprecated=data.get("deprecated", False),
        paint=data.get("paint"),
    )
