"""A project: its layers, what the user selected, and what the map shows."""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING, Any, Iterator, Mapping, Sequence

from .errors import UnknownNameError

if TYPE_CHECKING:
    from .client import Client
    from .jobs import Job
    from .layer import Layer, TrashEntry


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
    def basemap(self) -> str | None:
        """
        This project's basemap, e.g. ``"osm"`` -- what draws under every
        layer that does not set its own, see
        :attr:`hgis.layer.Layer.basemap`. What :meth:`update`'s ``basemap``
        writes.

        A real server always sets one; None here means only that this
        particular object was built without the field.
        """
        return self._data.get("basemap")

    @property
    def basemap_opacity(self) -> float | None:
        """
        Opacity of :attr:`basemap` itself, between 0 and 1. What
        :meth:`update`'s ``basemap_opacity`` writes.

        Carried on a single project read (:meth:`hgis.client.Client.project`
        by id, :meth:`view`, the result of :meth:`update`) but not on the
        list :meth:`hgis.client.Client.projects` returns -- None there until
        one of those reads this project in full.
        """
        return self._data.get("basemapOpacity")

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
        trashed = [
            entry for entry in self.trash() if entry.name.casefold() == name_or_id.casefold()
        ]
        if trashed:
            if len(trashed) == 1:
                t = trashed[0]
                trash_note = f" Ein Layer dieses Namens liegt im Papierkorb (Id {t.id})."
            else:
                ids = ", ".join(t.id for t in trashed)
                trash_note = f" Mehrere Layer dieses Namens liegen im Papierkorb: {ids}."
            raise UnknownNameError(
                f"Unbekannter Layer: {name_or_id}. Verfügbar: {available}.{trash_note}"
            )
        raise UnknownNameError(
            f"Unbekannter Layer: {name_or_id}. Verfügbar: {available}."
        )

    def trash(self) -> list["TrashEntry"]:
        """
        Every layer currently sitting in this project's trash, most recently deleted first.

        >>> for entry in project.trash():
        ...     print(entry.name, entry.id, entry.feature_count, entry.deleted_at)
        """
        from .layer import _to_trash_entry

        items = self._client.get(f"/api/projects/{self.id}/trash")
        return [_to_trash_entry(item) for item in items]

    def create_layer(
        self,
        name: str,
        geometry_type: str,
        *,
        fields: Mapping[str, str] | None = None,
    ) -> "Layer":
        """
        Create a new, empty layer in this project -- ready to draw into with
        :meth:`hgis.layer.Layer.insert`.

        >>> project.create_layer("Bäume", "MULTIPOINT",
        ...     fields={"Gattung": "TEXT", "Pflanzjahr": "INTEGER"})

        :param geometry_type: MULTIPOINT, MULTILINESTRING, MULTIPOLYGON or
            GEOMETRY -- the last for a layer meant to hold a genuine mix from
            the start
        :param fields: attribute fields to create alongside the layer, name to
            type, in the given order. See
            :meth:`hgis.layer.Layer.create_field` for the nine type tokens.
            None or empty is a valid layer that shows only ``fid``.
        """
        from .layer import Layer

        data = self._client.create_layer(
            self.id, name, geometry_type,
            fields=list(fields.items()) if fields else None,
        )
        return Layer(self._client, data, project=self)

    def create_map_layer(
        self,
        service_url: str,
        layers: Sequence[str],
        image_format: str,
        *,
        name: str | None = None,
        dataset_id: str | None = None,
    ) -> "Layer":
        """
        Add a WMS map image layer to this project -- a picture the service
        draws, not objects with attributes of their own (see
        :attr:`hgis.layer.Layer.kind`). No job: the layer exists by the time
        this returns, unlike :meth:`import_file` and :meth:`import_geoportal`,
        which both hand back one to poll instead.

        Read the service's own capabilities first -- ``client.get(
        "/api/wms/capabilities", url=service_url)``, or
        :func:`hgis.mcp.read_tools.wms_capabilities` from an MCP client --
        ``layers`` and ``image_format`` have to be names the service itself
        offers, checked there before anything here is stored.

        >>> caps = client.get("/api/wms/capabilities",
        ...     url="https://geodienste.hamburg.de/HH_WMS_Geobasiskarten")
        >>> project.create_map_layer(
        ...     caps["serviceUrl"], [caps["layers"][0]["name"]], "image/png")

        :param service_url: the service's own address, with or without query
            parameters
        :param layers: the chosen layer names, bottom first -- the order the
            service draws them in
        :param image_format: GetMap ``FORMAT``, e.g. ``"image/png"``
        :param name: this layer's name in the project, or None to take the
            title of the first chosen layer
        :param dataset_id: the Geoportal catalog id this dataset came from,
            or None for an address typed in by hand
        """
        from .layer import Layer

        data = self._client.create_map_layer(
            self.id, service_url, layers, image_format, name=name, dataset_id=dataset_id
        )
        return Layer(self._client, data, project=self)

    def update(
        self,
        *,
        name: str | None = None,
        description: str | None = None,
        basemap: str | None = None,
        basemap_opacity: float | None = None,
        center: tuple[float, float] | None = None,
        zoom: float | None = None,
    ) -> "Project":
        """
        Change this project's own properties -- name, description, basemap
        and where the map stands -- and return it.

        Every argument left at None keeps its current value, the same rule
        :meth:`hgis.layer.Layer.update` follows. ``center`` and ``zoom`` are
        what moves the map for whoever has this project open on screen --
        combine them with :meth:`select` to both point at and select what an
        agent found, instead of only describing it:

        >>> hits = layer.where("baujahr < 1900")
        >>> project.update(center=(9.99, 53.55), zoom=16)
        >>> project.select(hits.fids(), layer=layer)

        :param basemap: a catalog id (see :meth:`hgis.client.Client.basemaps`)
            or this project's own tile URL template, in one of two forms --
            with ``{z}``, ``{x}``, ``{y}`` (XYZ or WMTS), or with
            ``{bbox-epsg-3857}`` in their place instead (a WMS ``GetMap``
            URL, for a service such as the Hamburg aerial imagery -- see
            :func:`hgis.mcp.write_tools.set_basemap` for the full example).
            These are the two cases :attr:`basemap` reads back. A value that
            is none of the above is refused, naming the valid catalog ids.
            Unlike :meth:`hgis.layer.Layer.update`'s ``basemap``, a project
            has no parent to fall back to, so there is no reset-to-None case
            here -- None simply leaves the current basemap alone
        :param center: (lng, lat) in EPSG:4326
        :param zoom: 0 to 24, checked by the server
        """
        self._data = self._client.update_project(
            self.id,
            name=name,
            description=description,
            basemap=basemap,
            basemap_opacity=basemap_opacity,
            center=center,
            zoom=zoom,
        )
        return self

    # --- importing ---------------------------------------------------------

    def inspect_import(
        self,
        file_path: str | None = None,
        *,
        upload_id: str | None = None,
        srid: int | None = None,
        charset: str | None = None,
    ) -> "Inspection":
        """
        Report what an import would produce -- geometry type, object count,
        fields with sample values, extent -- without producing anything.
        Folgenlos, so this is safe to repeat with a corrected ``srid`` or
        ``charset`` on the same upload.

        >>> preview = project.inspect_import("bäume.geojson")
        >>> preview.feature_count, preview.geometry_type
        (312, 'MULTIPOINT')
        >>> project.import_file(upload_id=preview.upload_id, name="Bäume")

        :param file_path: a path on the local filesystem of whoever runs
            this process; see :meth:`import_file` for what that means when
            this process is an MCP server
        :param upload_id: re-inspect a file already sent by an earlier call
            -- ``preview.upload_id`` above -- instead of sending it again.
            Exactly one of ``file_path``/``upload_id``; the server names the
            mistake if this sends neither or both
        :param srid: source CRS to assume, when the file carries none or the
            wrong one
        :param charset: character encoding to assume, when the file's own
            (or the format's default) reads field values or geometry wrong
        """
        data = self._client.inspect_import(
            self.id, file_path=file_path, upload_id=upload_id, srid=srid, charset=charset
        )
        return _to_inspection(data)

    def import_file(
        self,
        file_path: str | None = None,
        *,
        upload_id: str | None = None,
        name: str | None = None,
        srid: int | None = None,
        charset: str | None = None,
    ) -> "Job":
        """
        Start importing a file, or a previously inspected upload, into a
        new layer -- Shapefile, GeoJSON or CSV, the same formats the upload
        dialog in the UI reads.

        Returns immediately, with the job still ``PENDING`` -- the write
        itself keeps running on the server. Call :meth:`hgis.jobs.Job.wait`
        to block until it finishes, or a :func:`hgis.channel.watch` for
        several imports at once.

        >>> job = project.import_file("bäume.geojson", name="Bäume")
        >>> job.wait(timeout=120)
        >>> job.succeeded, job.output_layer_id
        (True, '019ff9aa-...')

        **``file_path`` is read from the local filesystem of whoever runs
        this process, with a plain ``open()``.** For a script an agent runs
        itself, that is the agent's own machine, and this is exactly what
        it looks like. For the MCP server (``hgis.mcp.write_tools.import_file``),
        it is the filesystem the *server* process sees -- right today only
        because that server and the agent it serves are assumed to share
        one machine (see the README's MCP chapter); a server running
        somewhere else would need a different way to receive the bytes,
        which this stage does not build.

        :param upload_id: skip re-sending a file already sent to
            :meth:`inspect_import` or an earlier :meth:`import_file` --
            exactly one of ``file_path``/``upload_id``
        :param name: the new layer's name. None uses the file's own name,
            without its extension
        :param srid: see :meth:`inspect_import`
        :param charset: see :meth:`inspect_import`
        """
        from .jobs import Job

        data = self._client.start_import(
            self.id, file_path=file_path, upload_id=upload_id,
            name=name, srid=srid, charset=charset,
        )
        return Job(self._client, data, project_id=self.id)

    def import_geoportal(
        self,
        dataset_id: str,
        *,
        bbox: tuple[float, float, float, float] | None = None,
        fields: Sequence[str] | None = None,
        name: str | None = None,
    ) -> "Job":
        """
        Start importing a dataset from the Geoportal Hamburg into a new
        layer -- the network takes the place of an upload, everything past
        that is the same job :meth:`import_file` starts.

        >>> job = project.import_geoportal(
        ...     "verkehr_strassen/verkehrsnetz",
        ...     bbox=(9.9, 53.5, 10.1, 53.6),
        ... )
        >>> job.wait(timeout=120)

        :param dataset_id: id from the Geoportal catalog. No dedicated
            lookup exists on this stage yet; read
            ``client.get("/api/geoportal/datasets")`` instead -- reading is
            unrestricted
        :param bbox: (minLng, minLat, maxLng, maxLat) in EPSG:4326. None
            imports the whole dataset
        :param fields: technical field names to keep. None keeps every
            field; the dataset's id field travels along regardless
        :param name: the new layer's name. None uses the dataset's own title
        """
        from .jobs import Job

        data = self._client.start_geoportal_import(
            self.id, dataset_id, bbox=bbox, fields=fields, name=name
        )
        return Job(self._client, data, project_id=self.id)

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

        >>> alt = layer.where("pflanzjahr < 1950").fids()
        >>> project.select(alt, layer=layer)

        ``layer`` may be omitted once some layer is already active -- the
        very first call on a fresh project has none yet, so name it there.

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

        self._client.save_view_state(
            self.id, {"version": 1, "activeLayerId": target, "layers": layers}
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


@dataclass(frozen=True)
class InspectionField:
    """One field an import would produce, as :meth:`Project.inspect_import` found it.

    :param data_type: the PostgreSQL type this field would get on import
    :param sample_values: first values in file order; a None entry is a null
        value, not an empty one
    """

    name: str
    data_type: str
    sample_values: list[str | None]


@dataclass(frozen=True)
class Inspection:
    """
    What an import would produce, read with :meth:`Project.inspect_import`
    without writing anything.

    :param upload_id: send this back as ``upload_id`` to
        :meth:`Project.inspect_import` to re-inspect the same upload with a
        different ``srid`` or ``charset``, or to :meth:`Project.import_file`
        to import it without sending the file a second time
    :param filename: the name the file was uploaded under, not the name it
        is stored under on the server
    :param feature_count: None when the format does not know its total up
        front
    :param charset: None when the format leaves no room for a choice
        (GeoPackage and GeoJSON are UTF-8 by definition)
    :param extent: (minLng, minLat, maxLng, maxLat) in EPSG:4326, whatever
        the source CRS is -- None when nothing could be located
    """

    upload_id: str
    filename: str
    geometry_type: str | None
    feature_count: int | None
    charset: str | None
    srid: int
    crs_confidence: str
    extent: tuple[float, float, float, float] | None
    fields: list[InspectionField]

    def __repr__(self) -> str:
        count = self.feature_count if self.feature_count is not None else "unbekannt"
        return (
            f"<hgis.Inspection {self.filename!r} {self.geometry_type} "
            f"Objekte={count} Felder={len(self.fields)}>"
        )


def _to_inspection(data: dict[str, Any]) -> Inspection:
    return Inspection(
        upload_id=data["uploadId"],
        filename=data["filename"],
        geometry_type=data.get("geometryType"),
        feature_count=data.get("featureCount"),
        charset=data.get("charset"),
        srid=data["srid"],
        crs_confidence=data["crsConfidence"],
        extent=_as_box(data.get("extentWgs84")),
        fields=[
            InspectionField(
                name=item["name"], data_type=item["dataType"], sample_values=item["sampleValues"]
            )
            for item in data.get("fields") or []
        ],
    )
