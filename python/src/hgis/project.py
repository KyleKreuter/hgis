"""A project: its layers, what the user selected, and what the map shows."""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING, Any, Iterator, Sequence

from .errors import UnknownNameError

if TYPE_CHECKING:
    from .client import Client
    from .layer import Layer


class Project:
    """
    One hGIS project.

    Built from the list or from a single read; both carry the same summary
    fields, so nothing here costs a request until you ask for layers, the
    selection or the view.
    """

    def __init__(self, client: "Client", data: dict[str, Any]) -> None:
        self._client = client
        self._data = data

    # --- what the server already told us -----------------------------------

    @property
    def id(self) -> str:
        return self._data["id"]

    @property
    def name(self) -> str:
        return self._data["name"]

    @property
    def description(self) -> str | None:
        return self._data.get("description")

    @property
    def srid(self) -> int:
        """The CRS the project stores its geometries in, e.g. 25832."""
        return self._data["srid"]

    @property
    def layer_count(self) -> int:
        return self._data["layerCount"]

    @property
    def feature_count(self) -> int:
        """Objects across every layer of this project."""
        return self._data["featureCount"]

    @property
    def extent(self) -> tuple[float, float, float, float] | None:
        """(minLng, minLat, maxLng, maxLat) over every layer, or None if empty."""
        return _as_box(self._data.get("extent"))

    def __repr__(self) -> str:
        return (
            f"<hgis.Project {self.name!r} "
            f"layers={self.layer_count} features={self.feature_count}>"
        )

    # --- layers ------------------------------------------------------------

    def layers(self) -> list["Layer"]:
        """Every layer of this project, bottom of the drawing order first."""
        from .layer import Layer

        items = self._client.get(f"/api/projects/{self.id}/layers")
        return [Layer(self._client, item, project=self) for item in items]

    def layer(self, name_or_id: str) -> "Layer":
        """
        One layer, by name or by id.

        The name is matched case-insensitively but in full.

        :raises UnknownNameError: when this project has no such layer, naming
            the ones it does have
        """
        from .client import _looks_like_id
        from .layer import Layer

        if _looks_like_id(name_or_id):
            data = self._client.get(f"/api/layers/{name_or_id}")
            return Layer(self._client, data, project=self)

        layers = self.layers()
        matches = [item for item in layers if item.name.casefold() == name_or_id.casefold()]
        if len(matches) == 1:
            return matches[0]
        if len(matches) > 1:
            ids = ", ".join(item.id for item in matches)
            raise UnknownNameError(
                f"Mehrere Layer heißen {name_or_id!r}. "
                f"Verwenden Sie eine Kennung: {ids}."
            )
        available = ", ".join(item.name for item in layers) if layers else "keine"
        raise UnknownNameError(
            f"Unbekannter Layer: {name_or_id}. Verfügbar: {available}."
        )

    # --- what the user is looking at ---------------------------------------

    def view(self) -> "View":
        """
        Where the map stands: centre, zoom, extent and the active layer.

        Two requests, because the server keeps the two halves apart -- centre
        and zoom belong to the project, the active layer to the saved view
        state.
        """
        from .layer import Layer

        detail = self._client.get(f"/api/projects/{self.id}")
        self._data = detail
        state = self._client.get(f"/api/projects/{self.id}/view-state")

        active_id = state.get("activeLayerId")
        active = None
        if active_id:
            active = Layer(
                self._client, self._client.get(f"/api/layers/{active_id}"), project=self
            )

        centre = detail.get("center")
        return View(
            center=(centre[0], centre[1]) if centre else None,
            zoom=detail.get("zoom"),
            extent=_as_box(detail.get("extent")),
            active_layer=active,
            basemap=detail.get("basemap"),
        )

    def selection(self, layer: "Layer | str | None" = None) -> "Selection":
        """
        What is selected -- what the user clicked, or what a previous
        :meth:`select` put there.

        The server keeps one selection per layer. Without an argument this reads
        the active layer's, which is the one the user is working in.

        :param layer: a layer, its name or its id; the active layer when omitted
        """
        state = self._client.get(f"/api/projects/{self.id}/view-state")
        target = self._resolve_layer_id(layer, state)
        if target is None:
            return Selection(layer=None, fids=[])

        entry = (state.get("layers") or {}).get(target) or {}
        return Selection(
            layer=self._layer_for(target, layer),
            fids=list(entry.get("selection") or []),
        )

    def select(self, fids: Sequence[int], layer: "Layer | str | None" = None) -> None:
        """
        Select these objects, so the user sees what was found.

        Writes the saved view state -- the working state, never the data. The
        layer also becomes the active one: a selection in a layer the user is
        not looking at would change nothing they can see, which is the opposite
        of what selecting is for.

        Everything else in the view state survives: the other layers' selections,
        every sort and every saved query are read and written back unchanged,
        because the endpoint replaces the state wholesale.

        >>> big = layer.where("stammumfang > 300").fids()
        >>> project.select(big)

        :param fids: object ids; an empty list clears the selection
        :param layer: a layer, its name or its id; the active layer when omitted
        :raises UnknownNameError: when no layer is given and none is active
        """
        state = self._client.get(f"/api/projects/{self.id}/view-state")
        target = self._resolve_layer_id(layer, state)
        if target is None:
            raise UnknownNameError(
                "Kein Layer angegeben und keiner aktiv. Nennen Sie den Layer: "
                "project.select(fids, layer=...)."
            )

        layers = dict(state.get("layers") or {})
        entry = dict(layers.get(target) or {})
        entry["selection"] = [int(fid) for fid in fids]
        entry.setdefault("sort", None)
        entry.setdefault("query", None)
        layers[target] = entry

        self._client.put(
            f"/api/projects/{self.id}/view-state",
            {"version": 1, "activeLayerId": target, "layers": layers},
        )
        # Returns nothing on purpose. Handing back a Selection would mean
        # reading the layer to name it -- one more request for something the
        # caller already holds.

    # --- helpers -----------------------------------------------------------

    def _resolve_layer_id(
        self, layer: "Layer | str | None", state: dict[str, Any]
    ) -> str | None:
        """The layer id to act on: the given one, or whichever is active."""
        from .client import _looks_like_id

        if layer is None:
            return state.get("activeLayerId")
        if isinstance(layer, str):
            return layer if _looks_like_id(layer) else self.layer(layer).id
        return layer.id

    def _layer_for(self, layer_id: str, given: "Layer | str | None") -> "Layer":
        """Reuse the caller's layer object when it is the one we acted on."""
        from .layer import Layer

        if given is not None and not isinstance(given, str) and given.id == layer_id:
            return given
        return Layer(self._client, self._client.get(f"/api/layers/{layer_id}"), project=self)


@dataclass(frozen=True)
class View:
    """
    Where the map stands.

    :param center: (lng, lat) in EPSG:4326, or None if the project was never opened
    :param zoom: map zoom, or None
    :param extent: (minLng, minLat, maxLng, maxLat) over every layer, or None
    :param active_layer: the layer the user is working in, or None
    :param basemap: the project's basemap
    """

    center: tuple[float, float] | None
    zoom: float | None
    extent: tuple[float, float, float, float] | None
    active_layer: "Layer | None"
    basemap: str | None

    def __repr__(self) -> str:
        layer = self.active_layer.name if self.active_layer else "keiner"
        centre = (
            f"{self.center[0]:.5f}, {self.center[1]:.5f}" if self.center else "unbekannt"
        )
        zoom = f"{self.zoom:.2f}" if self.zoom is not None else "unbekannt"
        return f"<hgis.View Mitte={centre} Zoom={zoom} aktiver Layer={layer!r}>"


@dataclass(frozen=True)
class Selection:
    """
    The objects selected in one layer.

    Reads as a list of fids -- ``len(selection)``, ``for fid in selection``,
    ``list(selection)`` -- and still knows which layer they belong to.
    """

    layer: "Layer | None"
    fids: list[int]

    def __len__(self) -> int:
        return len(self.fids)

    def __iter__(self) -> Iterator[int]:
        return iter(self.fids)

    def __contains__(self, fid: object) -> bool:
        return fid in self.fids

    def __repr__(self) -> str:
        name = self.layer.name if self.layer else "keiner"
        return f"<hgis.Selection Layer={name!r} Objekte={len(self.fids)}>"


def _as_box(value: Any) -> tuple[float, float, float, float] | None:
    """A four-number array from the server as a tuple, or None."""
    if not value or len(value) != 4:
        return None
    return (value[0], value[1], value[2], value[3])
