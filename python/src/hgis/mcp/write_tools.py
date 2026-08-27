"""
The tools that change something.

Two kinds live here, and the difference matters to whoever reads a docstring
in a hurry:

* **What the person at the screen sees** -- the selection and the map view.
  Nothing is lost when these are wrong; the next call puts them right. They are
  how an agent shows a result instead of describing it.
* **What is stored** -- objects, layers, fields, styles. A wrong call here
  costs data. Deleting a whole layer goes to the trash and comes back;
  deleting single objects is recoverable only through the change log.

Every docstring for the second kind says plainly what it destroys. That is the
only safeguard this server has, since it offers the full surface without a
switch -- the operator's decision, made deliberately.

**Every parameter carries its own description**, the same way
:mod:`hgis.mcp.read_tools` does it: ``Annotated[type, Field(description=...)]``
rather than a ``:param:`` line in the docstring. A plain docstring line never
reaches the JSON schema as a per-parameter description -- only the whole
docstring does, as the tool's one description -- so a client that shows
parameter hints while the agent is filling in a call would otherwise show
nothing next to each one. Destructive tools are exactly where that gap costs
the most.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Annotated, Any

from pydantic import Field

import hgis
from hgis.client import _looks_like_id
from hgis.errors import InvalidArgumentError, UnknownNameError
from hgis.mcp.server import client, server  # noqa: F401
from hgis.mcp.shapes import WriteResult, tool_error  # noqa: F401

# --- resolving a name into what it actually addresses -----------------------
#
# Every tool below takes a layer as a name or an id, and most take a project
# the same way. The rule is the same everywhere: an id is enough on its own;
# a name needs the project it lives in, because layer names are only unique
# within one project (see hgis.project.Project.layer). Chosen over asking for
# an id everywhere, because most of what an agent has in hand after a read
# tool is a name, and over guessing a project from a bare layer name, because
# two projects can hold a layer called the same thing and a wrong guess would
# write to data nobody meant to touch.

#: Repeated on every ``layer`` parameter, so an agent reads the same rule no
#: matter which tool it is about to call.
_LAYER_HELP = (
    "Name oder Id des Layers. Ein Name wird nur zusammen mit project "
    "aufgelöst -- ohne project ist ausschließlich die Id gültig."
)

#: Repeated on every optional ``project`` parameter that exists solely to
#: resolve a ``layer`` name.
_PROJECT_FOR_LAYER_HELP = (
    "Name oder Id des Projekts. Nötig, damit layer als Name aufgelöst werden "
    "kann; bei einer Layer-Id entbehrlich."
)


def _project(name_or_id: str) -> hgis.Project:
    """A project, by name or by id -- see :meth:`hgis.client.Client.project`."""
    return client().project(name_or_id)


def _layer(name_or_id: str, project: str | None) -> hgis.Layer:
    """
    A layer, by name (with ``project``) or by id (with or without one).

    :raises hgis.errors.InvalidArgumentError: a name was given without a
        project to resolve it in
    :raises hgis.errors.UnknownNameError: the name or id matches no layer, or
        matches more than one
    """
    if project is not None:
        return _project(project).layer(name_or_id)
    if _looks_like_id(name_or_id):
        return client().layer(name_or_id)
    raise InvalidArgumentError(
        f"'{name_or_id}' ist keine Layer-Id. Geben Sie project mit an, damit "
        "der Name aufgelöst werden kann, oder nennen Sie die Layer-Id."
    )


def _require_layer_id(layer_id: str) -> str:
    """
    ``layer_id``, checked to actually look like one before it reaches the
    guarded client methods below.

    Without this, a name reaches :class:`hgis.client.RequestGuard` as part of
    a URL it does not match, and the agent reads the guard's explanation of
    which write paths exist -- true, but not about what it actually did
    wrong. This says that instead.

    :raises hgis.errors.InvalidArgumentError: ``layer_id`` is not id-shaped
    """
    if not _looks_like_id(layer_id):
        raise InvalidArgumentError(
            f"'{layer_id}' ist keine Layer-Id. Ein Layer im Papierkorb ist über "
            "seinen Namen nicht mehr auflösbar -- nennen Sie die Id, die "
            "delete_layer zurückgegeben hat."
        )
    return layer_id


def _fid_summary(fids: list[int]) -> str:
    """
    fids for a summary sentence a person can check against their own intent.

    A contiguous run collapses to ``"1201-1203"``, the shape a batch insert
    usually produces. A short, non-contiguous list is spelled out in full; a
    long one is cut with a count, so the sentence stays one line regardless
    of how many objects were touched.
    """
    if not fids:
        return "keine"
    if len(fids) == 1:
        return str(fids[0])
    ordered = sorted(fids)
    if ordered == list(range(ordered[0], ordered[-1] + 1)):
        return f"{ordered[0]}-{ordered[-1]}"
    if len(fids) <= 8:
        return ", ".join(str(fid) for fid in fids)
    return ", ".join(str(fid) for fid in fids[:8]) + f", ... ({len(fids)} insgesamt)"


def _view_for_extent(extent: tuple[float, float, float, float]) -> tuple[list[float], float]:
    """
    A center and a zoom that show this whole extent on screen, roughly.

    Web Mercator distorts latitude, and this ignores that -- it sizes both
    dimensions from raw degree spans against a generous assumed viewport
    (900x600 CSS pixels), then backs the result off by one more zoom level.
    Not what a real ``fitBounds()`` would compute, but wrong in the direction
    that is safe to be wrong in: a little too far out, never so far in that
    part of what was asked for is cut off the edge.

    :param extent: (min_lng, min_lat, max_lng, max_lat) in EPSG:4326, as
        :attr:`hgis.layer.Layer.extent` returns it
    """
    min_lng, min_lat, max_lng, max_lat = extent
    center = [(min_lng + max_lng) / 2, (min_lat + max_lat) / 2]

    lng_span = max(max_lng - min_lng, 1e-9)
    lat_span = max(max_lat - min_lat, 1e-9)
    zoom_for_lng = math.log2(900 * 360 / (256 * lng_span))
    zoom_for_lat = math.log2(600 * 180 / (256 * lat_span))
    zoom = max(0.0, min(24.0, min(zoom_for_lng, zoom_for_lat) - 1))
    return center, zoom


def _view_change_note(center: list[float] | None, zoom: float | None, computed: bool) -> str:
    """One clause for a set_view summary, naming whether the numbers came from an extent."""
    how = "aus seinem Ausschnitt berechnet" if computed else "gesetzt"
    if center is not None and zoom is not None:
        return f"Kartenausschnitt {how}: Mitte {center[0]:.5f}, {center[1]:.5f}, Zoom {zoom:.1f}"
    if center is not None:
        return f"Kartenmitte {how}: {center[0]:.5f}, {center[1]:.5f}"
    return f"Zoom {how}: {zoom:.1f}"  # zoom is not None here, since one of the two must be


def _visibility_note(target: hgis.Layer) -> str | None:
    """
    A clause naming that a layer is hidden, or None when it is visible.

    select_features and set_view exist to show something on screen -- a
    success message that does not mention a hidden layer would be true about
    the write and false about the effect a person actually sees.
    """
    if target.visible:
        return None
    return (
        f"'{target.name}' ist derzeit ausgeblendet -- am Bildschirm zeigt sich "
        "nichts davon, bis update_layer(visible=true) das ändert"
    )


# --- what the person at the screen sees --------------------------------------


@server.tool()
def select_features(
    project: Annotated[str, Field(description="Name oder Id des Projekts.")],
    fids: Annotated[
        list[int],
        Field(description="Die auszuwählenden Objekte. Eine leere Liste hebt die Auswahl auf."),
    ],
    layer: Annotated[
        str | None,
        Field(
            description="Name oder Id des Layers. Ohne Angabe der zuletzt aktive Layer "
            "dieses Projekts -- fehlt der, schlägt der Aufruf fehl und nennt, wie der "
            "Layer stattdessen anzugeben ist. Der genannte Layer wird dabei zum aktiven "
            "Layer, falls er es nicht schon war."
        ),
    ] = None,
) -> WriteResult:
    """
    Setzt die Auswahl in einem Layer -- das, was am Bildschirm markiert
    erscheint. Nichts geht dabei verloren: die Objekte selbst bleiben
    unberührt, und der nächste Aufruf ersetzt die Auswahl wieder.

    Das Werkzeug, mit dem ein Agent ein Ergebnis zeigt statt es nur zu
    beschreiben -- zum Beispiel die Treffer einer vorherigen Filterabfrage.
    Kombinieren Sie es mit set_view, um den Ausschnitt gleich mit dorthin zu
    bewegen.

    Prüft, wie viele der angegebenen fids es im Layer tatsächlich gibt, und
    sagt es in der summary -- eine veraltete oder erfundene fid ist erlaubt,
    zeigt am Bildschirm aber ins Leere, und das soll nicht unbemerkt bleiben.
    Ebenso, wenn der Layer selbst gerade ausgeblendet ist (visible=false):
    die Auswahl wird trotzdem gesetzt, aber die summary sagt, dass sie am
    Bildschirm nicht zu sehen ist.
    """
    try:
        proj = _project(project)
        if layer is not None:
            target = proj.layer(layer)
        else:
            target = proj.selection().layer
            if target is None:
                raise UnknownNameError(
                    "Kein Layer angegeben und keiner aktiv. Nennen Sie layer."
                )

        matched = 0
        if fids:
            fid_list = ", ".join(str(fid) for fid in fids)
            matched = target.where(f"fid IN ({fid_list})").count()

        proj.select(fids, layer=target)

        if matched == len(fids):
            parts = [f"{len(fids)} Objekt(e) in '{target.name}' ausgewählt"]
        else:
            missing = len(fids) - matched
            parts = [
                f"{len(fids)} Objekt(e) in '{target.name}' ausgewählt, davon "
                f"gibt es {matched} tatsächlich; {missing} zeigen am Bildschirm "
                "ins Leere"
            ]
        note = _visibility_note(target)
        if note:
            parts.append(note)
        return WriteResult(summary=". ".join(parts) + ".")
    except Exception as error:
        raise tool_error(error, doing=f"Auswählen in Projekt '{project}'") from error


@server.tool()
def set_view(
    project: Annotated[str, Field(description="Name oder Id des Projekts.")],
    layer: Annotated[
        str | None,
        Field(
            description="Name oder Id des Layers. Wird zum aktiven Layer, seine eigene "
            "Auswahl bleibt dabei unverändert. Ohne center und zoom berechnet dieses "
            'Werkzeug beide aus dem Ausschnitt (extent) dieses Layers, damit er ganz '
            'sichtbar wird -- die übliche Bitte "zeig mir dieses Ergebnis", ohne dass '
            "der Aufrufer selbst rechnen muss. Ein leerer Layer hat keinen Ausschnitt; "
            "dann müssen center und/oder zoom selbst angegeben werden."
        ),
    ] = None,
    center: Annotated[
        list[float] | None,
        Field(
            description="[lng, lat] in EPSG:4326. Gegeben, überschreibt es eine aus "
            "layer berechnete Mitte."
        ),
    ] = None,
    zoom: Annotated[
        float | None,
        Field(
            description="0 bis 24, vom Server geprüft. Gegeben, überschreibt es einen "
            "aus layer berechneten Zoom.",
            ge=0,
            le=24,
        ),
    ] = None,
) -> WriteResult:
    """
    Bewegt die Karte des Projekts und/oder wechselt den aktiven Layer -- das,
    worauf der Anwender gerade schaut. Nichts geht dabei verloren: der
    nächste Aufruf setzt Ausschnitt und aktiven Layer wieder um.

    Mindestens eines von layer, center oder zoom muss angegeben sein.

    Ist der genannte Layer gerade ausgeblendet (visible=false), wechselt er
    trotzdem in den aktiven Layer, aber die summary sagt, dass am Bildschirm
    nichts davon zu sehen ist.
    """
    try:
        if layer is None and center is None and zoom is None:
            raise InvalidArgumentError(
                "Mindestens eines von layer, center oder zoom muss angegeben werden -- "
                "sonst gibt es nichts zu bewegen."
            )
        if center is not None and len(center) != 2:
            raise InvalidArgumentError(f"center muss [lng, lat] sein: {center!r}.")

        proj = _project(project)
        parts: list[str] = []
        computed_from_layer = False

        if layer is not None:
            target = proj.layer(layer)
            if center is None and zoom is None and target.extent is None:
                # Vor jedem Schreiben geprueft: sonst waere der aktive Layer
                # schon gewechselt, bevor der Aufruf hier doch noch scheitert.
                raise InvalidArgumentError(
                    f"Layer '{target.name}' hat keine Objekte, also keinen "
                    "Ausschnitt. Geben Sie center und/oder zoom selbst an."
                )

            current = proj.selection(layer=target)
            proj.select(current.fids, layer=target)
            parts.append(f"'{target.name}' ist jetzt der aktive Layer in '{proj.name}'")
            note = _visibility_note(target)
            if note:
                parts.append(note)

            if center is None and zoom is None:
                center, zoom = _view_for_extent(target.extent)
                computed_from_layer = True

        if center is not None or zoom is not None:
            proj.update(center=center, zoom=zoom)
            parts.append(_view_change_note(center, zoom, computed_from_layer))

        return WriteResult(summary=". ".join(parts) + ".")
    except Exception as error:
        raise tool_error(error, doing=f"Bewegen der Ansicht in '{project}'") from error


# --- projects: what is stored --------------------------------------------


@server.tool()
def create_project(
    name: Annotated[str, Field(description="Name des neuen Projekts.")],
    description: Annotated[
        str | None,
        Field(description="Kurzbeschreibung. Weggelassen bleibt sie leer."),
    ] = None,
    srid: Annotated[
        int | None,
        Field(
            description="Speicher-CRS als EPSG-Code, z. B. 25832. Weggelassen gilt "
            "der Server-Standard (EPSG:25832). Nach dem Anlegen nicht mehr änderbar."
        ),
    ] = None,
    basemap: Annotated[
        str | None,
        Field(description="Basiskarte. Weggelassen gilt der Server-Standard."),
    ] = None,
) -> WriteResult:
    """
    Legt ein neues, leeres Projekt an -- eine eigene Arbeitsfläche, statt in
    ein Projekt zu schreiben, das einem Menschen gehört. Rückgängig zu
    machen mit delete_project, das dafür den Projektnamen wörtlich verlangt.
    """
    try:
        created = client().create_project(
            name, description=description, srid=srid, basemap=basemap
        )
        return WriteResult(
            summary=(
                f"Projekt '{created.name}' angelegt (Id {created.id}), "
                f"SRID {created.srid}."
            )
        )
    except Exception as error:
        raise tool_error(error, doing=f"Anlegen des Projekts '{name}'") from error


@server.tool()
def delete_project(
    project: Annotated[str, Field(description="Name oder Id des zu löschenden Projekts.")],
    confirm_name: Annotated[
        str,
        Field(
            description="Der Projektname, wörtlich und vollständig, als zweite, "
            "unabhängige Angabe. Weicht er ab -- auch nur in Groß-/Kleinschreibung "
            "oder Leerzeichen --, wird nichts gelöscht."
        ),
    ],
) -> WriteResult:
    """
    Löscht ein ganzes Projekt endgültig, mit jedem seiner Layer und jedem
    Objekt darin -- der einzige Weg in diesem gesamten Werkzeugsatz, der
    sich nicht rückgängig machen lässt. Der Papierkorb hinter delete_layer/
    restore_layer deckt einzelne Layer, kein ganzes Projekt.

    Verlangt deshalb den Projektnamen ein zweites Mal, wörtlich, als
    confirm_name -- unabhängig davon, ob project bereits der Name oder eine
    Id ist. Stimmt confirm_name nicht mit dem tatsächlichen Namen überein,
    wird nichts gelöscht; die Ablehnung nennt den Namen, der gepasst hätte.
    """
    try:
        resolved = _project(project)
        if confirm_name != resolved.name:
            raise InvalidArgumentError(
                f"confirm_name '{confirm_name}' stimmt nicht mit dem Namen des "
                f"Projekts '{resolved.name}' überein. Nichts wurde gelöscht. "
                f"Zum Löschen confirm_name='{resolved.name}' übergeben."
            )
        name, project_id = resolved.name, resolved.id
        impact = client().deletion_impact(project_id)
        layer_count = impact.get("layerCount") if isinstance(impact, dict) else None
        feature_count = impact.get("featureCount") if isinstance(impact, dict) else None
        client().delete_project(project_id)
        counts = (
            f" ({layer_count} Layer, {feature_count} Objekt(e) mit ihm)"
            if layer_count is not None and feature_count is not None
            else ""
        )
        return WriteResult(
            summary=f"Projekt '{name}' (Id {project_id}) endgültig gelöscht{counts}.",
            deleted=feature_count or 0,
        )
    except Exception as error:
        raise tool_error(error, doing=f"Löschen des Projekts '{project}'") from error


# --- importing: what is stored --------------------------------------------


@dataclass(frozen=True)
class ImportResult:
    """
    Was ein Import-Werkzeug erreicht hat -- unter Umständen noch nicht
    fertig, wenn die Frist vor dem Job verstrichen ist.

    status ist PENDING, RUNNING, SUCCEEDED oder FAILED. Bei SUCCEEDED sind
    layer_id/layer_name/feature_count gesetzt: der fertige Layer, ohne
    eigenen describe_layer-Aufruf. Bei FAILED nennt message den Grund. Bei
    PENDING/RUNNING ist job_id der Anknüpfpunkt für job_wait.
    """

    summary: str
    job_id: str
    status: str
    layer_id: str | None = None
    layer_name: str | None = None
    feature_count: int | None = None
    message: str | None = None


def _import_result(job: hgis.Job) -> ImportResult:
    """Turn a waited-on :class:`hgis.Job` into the shape an import tool answers with."""
    if job.succeeded:
        layer = client().layer(job.output_layer_id) if job.output_layer_id else None
        note = f" -- {job.message}" if job.message else ""
        summary = (
            f"Import abgeschlossen: Layer '{layer.name}' (Id {layer.id}), "
            f"{layer.feature_count} Objekt(e){note}."
            if layer is not None
            else f"Import abgeschlossen{note}."
        )
        return ImportResult(
            summary=summary,
            job_id=job.id,
            status=job.status,
            layer_id=layer.id if layer is not None else None,
            layer_name=layer.name if layer is not None else None,
            feature_count=layer.feature_count if layer is not None else None,
            message=job.message,
        )
    if job.failed:
        return ImportResult(
            summary=f"Import fehlgeschlagen: {job.message}",
            job_id=job.id,
            status=job.status,
            message=job.message,
        )
    return ImportResult(
        summary=(
            f"Import läuft nach Ablauf der Frist noch (Job {job.id}, Status "
            f"{job.status}). Nachfassen mit job_wait(job_id='{job.id}', project=...)."
        ),
        job_id=job.id,
        status=job.status,
    )


_FILE_PATH_HELP = (
    "Pfad zu einer Datei auf dem Dateisystem des MCP-Servers -- bei einem lokal "
    "laufenden Server derselbe Rechner, auf dem auch der Agent läuft. Genau eins "
    "von file_path/upload_id."
)
_UPLOAD_ID_HELP = (
    "uploadId aus einer vorigen inspect_import- oder import_file-Antwort, um "
    "dieselbe Datei erneut zu verwenden, ohne sie neu zu senden. Genau eins von "
    "file_path/upload_id."
)
_SRID_HELP = "Quell-CRS überschreiben, wenn die Datei keins oder das falsche trägt."
_CHARSET_HELP = (
    "Zeichenkodierung überschreiben, wenn crs_confidence oder die Beispielwerte "
    "falsch aussehen -- typisch bei einem Shapefile ohne .cpg."
)
_IMPORT_TIMEOUT_HELP = (
    "Sekunden, die auf den fertigen Layer gewartet wird, bevor stattdessen die "
    "Job-Id zum Nachfassen mit job_wait zurückkommt."
)


@server.tool()
def inspect_import(
    project: Annotated[str, Field(description="Name oder Id des Projekts.")],
    file_path: Annotated[str | None, Field(description=_FILE_PATH_HELP)] = None,
    upload_id: Annotated[str | None, Field(description=_UPLOAD_ID_HELP)] = None,
    srid: Annotated[int | None, Field(description=_SRID_HELP)] = None,
    charset: Annotated[str | None, Field(description=_CHARSET_HELP)] = None,
) -> hgis.Inspection:
    """
    Sagt, was ein Import erzeugen würde -- Geometrietyp, Objektzahl, Felder
    mit Beispielwerten, Ausschnitt --, ohne etwas anzulegen. Folgenlos,
    beliebig oft wiederholbar, auch mit anderem srid/charset über die
    zurückgegebene upload_id.

    Rufen Sie das vor import_file auf, um Feldnamen und Geometrietyp zu
    kennen, bevor ein Layer entsteht, statt sie zu raten.
    """
    try:
        proj = _project(project)
        return proj.inspect_import(file_path, upload_id=upload_id, srid=srid, charset=charset)
    except Exception as error:
        raise tool_error(error, doing=f"Prüfen des Imports in '{project}'") from error


@server.tool()
def import_file(
    project: Annotated[str, Field(description="Name oder Id des Projekts.")],
    file_path: Annotated[str | None, Field(description=_FILE_PATH_HELP)] = None,
    upload_id: Annotated[str | None, Field(description=_UPLOAD_ID_HELP)] = None,
    name: Annotated[
        str | None,
        Field(description="Name des neuen Layers. Weggelassen: der Dateiname ohne Endung."),
    ] = None,
    srid: Annotated[int | None, Field(description=_SRID_HELP)] = None,
    charset: Annotated[str | None, Field(description=_CHARSET_HELP)] = None,
    timeout: Annotated[float, Field(description=_IMPORT_TIMEOUT_HELP, ge=1)] = 120.0,
) -> ImportResult:
    """
    Importiert eine Datei (oder eine mit inspect_import geprüfte Upload) in
    einen neuen Layer und wartet, bis er fertig ist -- ein Aufruf, eine
    Antwort mit dem fertigen Layer, statt einer Abfrageschleife.

    Eine kaputte Datei oder ein unplausibles CRS kommt sofort als Ablehnung
    zurück, nicht als spät fehlschlagender Job. Läuft die Frist ab, bevor
    der Job fertig ist, kommt stattdessen seine Id zurück -- job_wait fasst
    damit nach.

    Rückgängig zu machen wie jeder neue Layer: delete_layer mit der
    zurückgegebenen layer_id.
    """
    try:
        proj = _project(project)
        job = proj.import_file(
            file_path, upload_id=upload_id, name=name, srid=srid, charset=charset
        )
        job.wait(timeout=timeout)
        return _import_result(job)
    except Exception as error:
        raise tool_error(error, doing=f"Importieren nach '{project}'") from error


@server.tool()
def import_geoportal(
    project: Annotated[str, Field(description="Name oder Id des Projekts.")],
    dataset_id: Annotated[
        str,
        Field(
            description="Id des Datensatzes im Geoportal-Katalog Hamburg, z. B. "
            "'verkehr_strassen/verkehrsnetz'. Kein eigenes Werkzeug dafür in dieser "
            "Stufe -- der Katalog steht unter GET /api/geoportal/datasets."
        ),
    ],
    bbox: Annotated[
        list[float] | None,
        Field(
            description="minLng, minLat, maxLng, maxLat in EPSG:4326. Weggelassen: "
            "der ganze Datensatz."
        ),
    ] = None,
    fields: Annotated[
        list[str] | None,
        Field(description="Technische Feldnamen, die mitkommen sollen. Weggelassen: alle."),
    ] = None,
    name: Annotated[
        str | None,
        Field(description="Name des neuen Layers. Weggelassen: der Titel des Datensatzes."),
    ] = None,
    timeout: Annotated[float, Field(description=_IMPORT_TIMEOUT_HELP, ge=1)] = 120.0,
) -> ImportResult:
    """
    Importiert einen Datensatz aus dem Geoportal Hamburg in einen neuen
    Layer und wartet, bis er fertig ist -- dasselbe Ergebnis wie import_file,
    aus einem Netzabruf statt einer Datei.
    """
    try:
        proj = _project(project)
        job = proj.import_geoportal(dataset_id, bbox=bbox, fields=fields, name=name)
        job.wait(timeout=timeout)
        return _import_result(job)
    except Exception as error:
        raise tool_error(error, doing=f"Geoportal-Import nach '{project}'") from error


@server.tool()
def job_wait(
    job_id: Annotated[
        str,
        Field(description="Die Id, die import_file oder import_geoportal zurückgegeben hat."),
    ],
    project: Annotated[
        str,
        Field(
            description="Name oder Id genau des Projekts, das an import_file/"
            "import_geoportal ging, als dieser Job entstand -- der Job selbst "
            "nennt es nicht, und ohne das richtige Projekt wartet dies auf das "
            "falsche."
        ),
    ],
    timeout: Annotated[float, Field(description=_IMPORT_TIMEOUT_HELP, ge=1)] = 120.0,
) -> ImportResult:
    """
    Wartet weiter auf einen Job, dessen Frist bei import_file oder
    import_geoportal verstrichen ist, bevor er fertig war.
    """
    try:
        proj = _project(project)
        data = client().get(f"/api/jobs/{job_id}")
        job = hgis.Job(client(), data, project_id=proj.id)
        job.wait(timeout=timeout)
        return _import_result(job)
    except Exception as error:
        raise tool_error(error, doing=f"Warten auf Job {job_id}") from error


# --- objects: what is stored --------------------------------------------


@dataclass(frozen=True)
class FeatureChange:
    """
    Eine Änderung an einem vorhandenen Objekt, für :func:`update_features`.

    :param fid: das zu ändernde Objekt
    :param geometry: GeoJSON in EPSG:4326; weggelassen bleibt die Geometrie
        unverändert
    :param properties: nach Spaltenname; ein weggelassenes Attribut bleibt
        unverändert, ein genanntes mit Wert ``null`` wird auf SQL NULL
        gesetzt
    """

    fid: int
    geometry: dict[str, Any] | None = None
    properties: dict[str, Any] | None = None


@server.tool()
def insert_features(
    layer: Annotated[str, Field(description=_LAYER_HELP)],
    features: Annotated[
        list[hgis.NewFeature],
        Field(
            description="Je Objekt mindestens die Geometrie (GeoJSON, EPSG:4326); "
            "properties nach Spaltenname -- eine weggelassene Spalte bekommt ihren "
            "Vorgabewert."
        ),
    ],
    project: Annotated[str | None, Field(description=_PROJECT_FOR_LAYER_HELP)] = None,
) -> WriteResult:
    """
    Legt neue Objekte in einem Layer an.

    Rückgängig machen lässt sich das nur, indem die zurückgegebenen fids mit
    delete_features wieder gelöscht werden -- ein Fehlgriff hier ist also
    kein Datenverlust, aber ein zweiter Aufruf.
    """
    try:
        resolved = _layer(layer, project)
        fids = resolved.insert_many(features)
        return WriteResult(
            summary=(
                f"{len(fids)} Objekt(e) in '{resolved.name}' eingefügt, "
                f"fids {_fid_summary(fids)}."
            ),
            inserted=len(fids),
            new_fids=fids,
        )
    except Exception as error:
        raise tool_error(error, doing=f"Einfügen in '{layer}'") from error


@server.tool()
def update_features(
    layer: Annotated[str, Field(description=_LAYER_HELP)],
    updates: Annotated[
        list[FeatureChange],
        Field(
            description="Je Objekt siehe FeatureChange: fid, wahlweise geometry "
            "und/oder properties."
        ),
    ],
    project: Annotated[str | None, Field(description=_PROJECT_FOR_LAYER_HELP)] = None,
) -> WriteResult:
    """
    Ändert Geometrie und/oder Attribute vorhandener Objekte. Ein Fehlgriff
    überschreibt die vorherigen Werte -- rückgängig machen lässt sich das nur
    über das Änderungsprotokoll des Servers, nicht über dieses Werkzeug.

    Liest für jedes fid zuerst den aktuellen Stand, um Änderungskonflikte zu
    erkennen, und schreibt danach alle Änderungen in einem Stapel. Ändert
    jemand ein Objekt genau in diesem kurzen Fenster zwischen Lesen und
    Schreiben, meldet der Server einen Konflikt statt die fremde Änderung
    stillschweigend zu überschreiben -- ein erneuter Aufruf löst das dann auf
    Basis des neuen Stands.
    """
    try:
        resolved = _layer(layer, project)
        versioned = [
            hgis.FeatureUpdate(
                fid=change.fid,
                row_version=resolved.feature(change.fid).row_version,
                geometry=change.geometry,
                properties=change.properties,
            )
            for change in updates
        ]
        result = resolved.edit(updates=versioned)
        fids = [change.fid for change in updates]
        return WriteResult(
            summary=(
                f"{result.updated} Objekt(e) in '{resolved.name}' geändert, "
                f"fids {_fid_summary(fids)}."
            ),
            updated=result.updated,
        )
    except Exception as error:
        raise tool_error(error, doing=f"Ändern in '{layer}'") from error


@server.tool()
def delete_features(
    layer: Annotated[str, Field(description=_LAYER_HELP)],
    fids: Annotated[
        list[int],
        Field(
            description="Die zu löschenden Objekte, einzeln benannt. Es gibt keinen "
            'Filter-basierten oder "alles löschen"-Weg -- das würde eine leere oder '
            "falsch verstandene Auswahl zum ganzen Layer machen."
        ),
    ],
    project: Annotated[str | None, Field(description=_PROJECT_FOR_LAYER_HELP)] = None,
) -> WriteResult:
    """
    Löscht Objekte aus einem Layer. Nicht rückgängig zu machen über dieses
    Werkzeug oder diese Bibliothek -- nur das Änderungsprotokoll des Servers
    kennt Geometrie und Attribute danach noch. Anders als ein gelöschter
    ganzer Layer (siehe delete_layer), der in den Papierkorb geht.
    """
    try:
        resolved = _layer(layer, project)
        result = resolved.delete_features(fids)
        return WriteResult(
            summary=(
                f"{result.deleted} Objekt(e) in '{resolved.name}' gelöscht, "
                f"fids {_fid_summary(list(fids))}."
            ),
            deleted=result.deleted,
        )
    except Exception as error:
        raise tool_error(error, doing=f"Löschen in '{layer}'") from error


# --- layers: what is stored --------------------------------------------


@server.tool()
def create_layer(
    project: Annotated[str, Field(description="Name oder Id des Projekts.")],
    name: Annotated[str, Field(description="Name des neuen Layers.")],
    geometry_type: Annotated[
        str,
        Field(
            description="MULTIPOINT, MULTILINESTRING, MULTIPOLYGON oder GEOMETRY -- "
            "Letzteres für einen Layer, der von Anfang an unterschiedliche "
            "Geometrietypen mischen soll."
        ),
    ],
    fields: Annotated[
        dict[str, str] | None,
        Field(
            description="Attributfelder, die gleich mitangelegt werden -- Name auf "
            "Typ. Typ ist einer von TEXT, INTEGER, BIGINT, DOUBLE, NUMERIC, BOOLEAN, "
            "DATE, TIME, TIMESTAMP. Ohne Angabe ist der Layer gültig, zeigt aber nur "
            "fid."
        ),
    ] = None,
) -> WriteResult:
    """
    Legt einen neuen, leeren Layer in einem Projekt an. Rückgängig machen
    lässt sich das mit delete_layer -- ein leerer Layer, der niemandem
    fehlt, ist kein Verlust.
    """
    try:
        proj = _project(project)
        created = proj.create_layer(name, geometry_type, fields=fields)
        field_note = f", Felder: {', '.join(fields)}" if fields else ""
        return WriteResult(
            summary=(
                f"Layer '{created.name}' angelegt (Id {created.id}), "
                f"{geometry_type}{field_note}."
            )
        )
    except Exception as error:
        raise tool_error(error, doing=f"Anlegen des Layers '{name}' in '{project}'") from error


@server.tool()
def update_layer(
    layer: Annotated[str, Field(description=_LAYER_HELP)],
    name: Annotated[
        str | None, Field(description="Neuer Name. Weggelassen bleibt der bisherige.")
    ] = None,
    visible: Annotated[
        bool | None,
        Field(
            description="Sichtbar schalten (true) oder ausblenden (false). "
            "Weggelassen bleibt es, wie es war."
        ),
    ] = None,
    project: Annotated[str | None, Field(description=_PROJECT_FOR_LAYER_HELP)] = None,
) -> WriteResult:
    """
    Ändert Name und/oder Sichtbarkeit eines Layers. Nichts geht dabei
    verloren -- ein erneuter Aufruf setzt beides wieder zurück.

    Für den Stil siehe set_style, für Löschen delete_layer.
    """
    try:
        resolved = _layer(layer, project)
        resolved.update(name=name, visible=visible)
        parts = []
        if name is not None:
            parts.append(f"umbenannt in '{name}'")
        if visible is not None:
            parts.append("sichtbar geschaltet" if visible else "unsichtbar geschaltet")
        change = ", ".join(parts) if parts else "keine Änderung angegeben"
        return WriteResult(summary=f"Layer '{resolved.name}': {change}.")
    except Exception as error:
        raise tool_error(error, doing=f"Ändern des Layers '{layer}'") from error


@server.tool()
def delete_layer(
    layer: Annotated[str, Field(description=_LAYER_HELP)],
    project: Annotated[str | None, Field(description=_PROJECT_FOR_LAYER_HELP)] = None,
) -> WriteResult:
    """
    Verschiebt einen Layer in den Papierkorb des Projekts. Die Daten bleiben
    erhalten: restore_layer holt ihn zurück, bis jemand purge_layer aufruft
    -- das ist der einzige endgültige Schritt der beiden.

    Die Antwort nennt die Id, die restore_layer und purge_layer danach
    brauchen -- ein gelöschter Layer ist über seinen Namen oder sein Projekt
    nicht mehr auffindbar.
    """
    try:
        resolved = _layer(layer, project)
        name, layer_id = resolved.name, resolved.id
        trashed = resolved.delete()
        count = trashed.feature_count if trashed is not None else resolved.feature_count
        return WriteResult(
            summary=(
                f"Layer '{name}' (Id {layer_id}) in den Papierkorb verschoben, "
                f"{count} Objekt(e) mit ihm. Wiederherstellen mit "
                f"restore_layer('{layer_id}')."
            ),
            deleted=count,
        )
    except Exception as error:
        raise tool_error(error, doing=f"Löschen des Layers '{layer}'") from error


@server.tool()
def restore_layer(
    layer_id: Annotated[
        str,
        Field(
            description="Die Id, die delete_layer zurückgegeben hat -- ein Layer im "
            "Papierkorb ist über seinen Namen nicht mehr auflösbar."
        ),
    ],
) -> WriteResult:
    """
    Holt einen Layer aus dem Papierkorb zurück, mit allen seinen Objekten.
    """
    try:
        layer_id = _require_layer_id(layer_id)
        data = client().restore_layer(layer_id)
        name = data.get("name") if isinstance(data, dict) else None
        label = f"Layer '{name}' (Id {layer_id})" if name else f"Layer {layer_id}"
        return WriteResult(summary=f"{label} wiederhergestellt.")
    except Exception as error:
        raise tool_error(error, doing=f"Wiederherstellen des Layers {layer_id}") from error


@server.tool()
def purge_layer(
    layer_id: Annotated[
        str,
        Field(
            description="Die Id, die delete_layer zurückgegeben hat -- ein Layer im "
            "Papierkorb ist über seinen Namen nicht mehr auflösbar."
        ),
    ],
) -> WriteResult:
    """
    Löscht einen Layer aus dem Papierkorb endgültig -- Geometrie und
    Attribute sind danach weg, auch aus dem Änderungsprotokoll. Der einzige
    wirklich unwiederbringliche Schritt in diesem Werkzeugsatz.

    Nur für einen Layer, der bereits per delete_layer im Papierkorb liegt.
    Prüfen Sie im Zweifel erst, was dort liegt, bevor Sie dies aufrufen.
    """
    try:
        layer_id = _require_layer_id(layer_id)
        data = client().purge_layer(layer_id)
        name = data.get("name") if isinstance(data, dict) else None
        count = data.get("featureCount") if isinstance(data, dict) else None
        label = f"Layer '{name}' (Id {layer_id})" if name else f"Layer {layer_id}"
        count_note = f", {count} Objekt(e) mit ihm" if count is not None else ""
        return WriteResult(
            summary=f"{label} endgültig gelöscht{count_note}.",
            deleted=count or 0,
        )
    except Exception as error:
        raise tool_error(error, doing=f"Endgültiges Löschen des Layers {layer_id}") from error


# --- fields: what is stored --------------------------------------------


@server.tool()
def create_field(
    layer: Annotated[str, Field(description=_LAYER_HELP)],
    name: Annotated[str, Field(description="Anzeigename des neuen Felds.")],
    type: Annotated[  # noqa: A002
        str,
        Field(
            description="Einer von TEXT, INTEGER, BIGINT, DOUBLE, NUMERIC, BOOLEAN, "
            "DATE, TIME, TIMESTAMP. Ein unbekanntes Token meldet der Server mit den "
            "gültigen Namen."
        ),
    ],
    project: Annotated[str | None, Field(description=_PROJECT_FOR_LAYER_HELP)] = None,
) -> WriteResult:
    """
    Fügt einem Layer ein Attributfeld hinzu -- eine neue Spalte in seiner
    Tabelle. Rückgängig machen lässt sich das mit delete_field, solange noch
    niemand Werte hineingeschrieben hat, die dabei verloren gingen.
    """
    try:
        resolved = _layer(layer, project)
        created = resolved.create_field(name, type)
        return WriteResult(
            summary=f"Feld '{created.name}' ({created.type}) zu '{resolved.name}' hinzugefügt."
        )
    except Exception as error:
        raise tool_error(error, doing=f"Anlegen des Felds '{name}' in '{layer}'") from error


@server.tool()
def delete_field(
    layer: Annotated[str, Field(description=_LAYER_HELP)],
    field: Annotated[
        str,
        Field(
            description="Name, Spaltenname oder Id des Felds. Ein Name, der zu "
            "mehreren Feldern eines Layers passt, wird abgelehnt statt geraten -- "
            "nennen Sie dann die Feld-Id."
        ),
    ],
    project: Annotated[str | None, Field(description=_PROJECT_FOR_LAYER_HELP)] = None,
) -> WriteResult:
    """
    Löscht ein Attributfeld -- die Spalte und ihr Inhalt sind danach weg.
    Nur über das Änderungsprotokoll des Servers wiederherstellbar, nicht
    über dieses Werkzeug oder diese Bibliothek.
    """
    try:
        resolved = _layer(layer, project)
        resolved_field = resolved.field(field)
        resolved.delete_field(resolved_field)
        return WriteResult(summary=f"Feld '{resolved_field.name}' aus '{resolved.name}' gelöscht.")
    except Exception as error:
        raise tool_error(error, doing=f"Löschen des Felds '{field}' aus '{layer}'") from error


# --- style: what is stored -----------------------------------------------


@server.tool()
def set_style(
    layer: Annotated[str, Field(description=_LAYER_HELP)],
    style: Annotated[
        hgis.Style | None,
        Field(
            description="Aufgebaut wie hgis.Style: renderer, wahlweise labels/opacity/"
            "min_zoom/max_zoom. Vier Renderer-Typen in renderer.type: \"single\" (ein "
            "Symbol für alle Objekte, braucht nur symbol), \"categorized\" (ein Symbol "
            "je Attributwert, braucht field, categories und fallback_symbol), "
            '"graduated" (ein Symbol je Wertebereich, braucht field, classes und '
            'fallback_symbol), "heatmap" (Dichte statt einzelner Symbole, braucht '
            "field, radius und ramp). fallback_symbol färbt jedes Objekt, das keine "
            "Kategorie oder Klasse trifft, etwa bei fehlendem Attributwert -- der "
            "Server lehnt categorized/graduated ohne fallback_symbol mit einem 400 ab. "
            "Muss angegeben werden, "
            "darf aber ausdrücklich null sein -- das setzt den Layer auf die monochrome "
            'Standarddarstellung zurück. Heatmap-Beispiel: {"renderer": {"type": '
            '"heatmap", "field": "lautstaerke", "ramp": "inferno"}}.'
        ),
    ],
    project: Annotated[str | None, Field(description=_PROJECT_FOR_LAYER_HELP)] = None,
) -> WriteResult:
    """
    Ersetzt den Stil eines Layers vollständig -- es gibt keine
    Teil-Aktualisierung. Der vorherige Stil ist danach weg, aber nicht
    unwiederbringlich: ein erneuter Aufruf mit dem alten Stand stellt ihn
    wieder her. Lesen Sie dafür vorher get_style, statt den alten Stand zu
    raten.
    """
    try:
        resolved = _layer(layer, project)
        applied = resolved.set_style(style)
        if applied is None:
            return WriteResult(
                summary=f"Stil von '{resolved.name}' zurückgesetzt (Standarddarstellung)."
            )
        renderer_type = applied.renderer.type if applied.renderer else "?"
        return WriteResult(
            summary=f"Stil von '{resolved.name}' gesetzt: Renderer '{renderer_type}'."
        )
    except Exception as error:
        raise tool_error(error, doing=f"Setzen des Stils von '{layer}'") from error


# --- basemap: what draws under everything else --------------------------


#: Das echte Beispiel aus VERTRAG.md ("Zwei Formen von urlTemplate", 27.08.) --
#: eine WMS-GetMap-URL der Hamburger Luftbilder, Form B. Als eigene Konstante,
#: damit die Feldbeschreibung unten nicht an einer einzigen, über 100 Zeichen
#: langen Quellzeile scheitert (ruff E501) und trotzdem als eine
#: zusammenhängende, unveränderte URL bei der Anfrage ankommt.
_WMS_BBOX_EXAMPLE = (
    "https://geodienste.hamburg.de/wms_dop_zeitreihe_unbelaubt?SERVICE=WMS"
    "&VERSION=1.3.0&REQUEST=GetMap&LAYERS=dop_zeitreihe_unbelaubt&STYLES="
    "&CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png"
)


@server.tool()
def set_basemap(
    project: Annotated[str, Field(description="Name oder Id des Projekts.")],
    basemap: Annotated[
        str | None,
        Field(
            description="Entweder eine Katalog-Id aus list_basemaps (z.B. \"osm\") "
            "oder eine eigene Kachel-URL-Vorlage für einen selbst gehosteten oder "
            "sonst nicht gelisteten Dienst -- zwei Formen: mit {z}, {x}, {y} (XYZ "
            'oder WMTS), z.B. "https://kacheln.example.org/{z}/{x}/{y}.png", oder '
            "mit {bbox-epsg-3857} an ihrer Stelle (eine WMS-GetMap-URL), z.B. "
            f'"{_WMS_BBOX_EXAMPLE}". Ein Wert, der mit "https://" beginnt, gilt als '
            "eigene URL-Vorlage; jeder andere Wert muss eine gültige Katalog-Id "
            "sein -- eine unbekannte Id meldet der Server mit den gültigen. Die "
            "Katalog-Id \"none\" bedeutet \"bewusst gar keine Hintergrundkarte "
            "zeichnen\" -- etwas anderes als das Weglassen dieses Arguments: "
            "Weggelassen heißt stattdessen \"die Karte des Projekts übernehmen\" "
            "(bei layer) oder \"nur opacity ändern, die Karte selbst unverändert "
            "lassen\" (ohne layer, zusammen mit opacity). Weder layer noch "
            "basemap noch opacity angegeben ist ein Fehler -- dann gibt es "
            "nichts, das dieser Aufruf täte."
        ),
    ] = None,
    layer: Annotated[
        str | None,
        Field(
            description="Name oder Id des Layers. Angegeben, betrifft dies die "
            "Hintergrundkarte dieses einen Layers statt der des Projekts. Dabei "
            "lässt sich basemap weglassen: ohne opacity setzt das den Layer auf "
            "'folgt der Karte des Projekts' zurück; mit opacity ändert es "
            "stattdessen nur die Deckkraft und lässt die Karte des Layers "
            "unangetastet. Karte und opacity werden unabhängig voneinander "
            "vererbt und zurückgesetzt -- ein Layer mit eigener Karte, aber ohne "
            "eigene opacity, übernimmt weiterhin die opacity des Projekts, und "
            "umgekehrt."
        ),
    ] = None,
    opacity: Annotated[
        float | None,
        Field(
            description="Deckkraft der Hintergrundkarte, 0 bis 1. Weggelassen bleibt "
            "sie, wie sie zuvor war.",
            ge=0,
            le=1,
        ),
    ] = None,
) -> WriteResult:
    """
    Setzt die Hintergrundkarte -- des Projekts, oder eines einzelnen Layers,
    wenn layer angegeben ist. Rufen Sie list_basemaps zuerst auf, um eine
    gültige Id zu sehen, statt sie zu raten.

    Bei layer lässt sich basemap weglassen: ohne opacity setzt das den Layer
    auf die Karte des Projekts zurück, mit opacity ändert es nur die
    Deckkraft und lässt die Karte des Layers unangetastet. Ohne layer lässt
    sich basemap ebenfalls weglassen, wenn opacity angegeben ist -- dann
    ändert dieser Aufruf nur die Deckkraft der Karte des Projekts. Weder
    layer noch basemap noch opacity angegeben, gibt es nichts zu tun, und
    das wird abgelehnt statt still nichts zu bewirken.
    """
    try:
        if layer is None and basemap is None and opacity is None:
            raise InvalidArgumentError(
                "Weder basemap noch opacity angegeben. Ohne layer setzt dieser "
                "Aufruf die Karte des Projekts -- dafür braucht es mindestens "
                "eines von beiden, denn ein Projekt hat keine übergeordnete "
                "Karte, auf die es zurückfallen könnte, wie ein Layer sie mit "
                "layer=... hat. Nennen Sie eine Katalog-Id (siehe list_basemaps) "
                "oder eine eigene URL-Vorlage, oder eine Deckkraft."
            )

        proj = _project(project)
        kwargs: dict[str, Any] = {}
        if opacity is not None:
            kwargs["basemap_opacity"] = opacity

        if layer is not None:
            target = proj.layer(layer)
            if basemap is not None:
                kwargs["basemap"] = basemap
            elif opacity is None:
                # Weder basemap noch opacity angegeben: der reine Reset-Fall.
                kwargs["basemap"] = None
            # Sonst (basemap weggelassen, opacity angegeben): basemap bleibt
            # aus kwargs -- Layer.update() lässt es dann unangetastet, siehe
            # dessen _UNSET-Default.
            target.update(**kwargs)
            where = f"Layer '{target.name}'"

            if basemap is not None:
                opacity_note = f", Deckkraft {opacity}" if opacity is not None else ""
                summary = f"{where}: Hintergrundkarte auf '{basemap}' gesetzt{opacity_note}."
            elif opacity is not None:
                summary = f"{where}: Deckkraft auf {opacity} gesetzt, Hintergrundkarte unverändert."
            else:
                summary = f"{where}: folgt jetzt wieder der Hintergrundkarte des Projekts."
        else:
            # basemap kann hier None sein (siehe die verengte Bedingung oben --
            # dann ist opacity garantiert gegeben): nur die Deckkraft des
            # Projekts ändern, ohne seine Karte anzufassen.
            if basemap is not None:
                kwargs["basemap"] = basemap
            proj.update(**kwargs)
            where = f"Projekt '{proj.name}'"

            if basemap is not None:
                opacity_note = f", Deckkraft {opacity}" if opacity is not None else ""
                summary = f"{where}: Hintergrundkarte auf '{basemap}' gesetzt{opacity_note}."
            else:
                summary = f"{where}: Deckkraft auf {opacity} gesetzt, Hintergrundkarte unverändert."

        return WriteResult(summary=summary)
    except Exception as error:
        raise tool_error(error, doing=f"Setzen der Hintergrundkarte für '{project}'") from error
