"""
The tools that only look.

Nothing here changes anything in hGIS, which is why an agent can call them
freely while it works out what it is dealing with.

**A docstring here is not documentation, it is the tool's interface.** It is
the entire text the agent reads before deciding whether to call, so it says
what the tool answers and when to reach for it -- not how it is implemented.
The parameter descriptions travel too, and are where a filter syntax or a unit
gets explained.

**One resolution path, used everywhere.** Every tool below that takes a
``layer`` or ``project`` argument accepts a name or an id, resolved the same
way: :func:`_resolve_project` and :func:`_resolve_layer`. A name that fits
more than one candidate is refused rather than guessed at -- the error names
every candidate, the way :meth:`hgis.project.Project.layer` and
:meth:`hgis.layer.Layer.field` already do.
"""

from __future__ import annotations

import itertools
import re
from dataclasses import dataclass
from dataclasses import field as dataclass_field
from typing import Annotated, Any

from pydantic import Field

from hgis import Layer, Project, Query
from hgis.errors import UnknownNameError
from hgis.mcp.server import client, server
from hgis.mcp.shapes import (
    FeatureRow,
    LayerSummary,
    ProjectSummary,
    QueryResult,
    ToolError,
    ValueCount,
    extent_list,
    tool_error,
)
from hgis.query import PAGE_SIZE

#: The shape hgis.client uses internally to tell an id from a name. Private
#: there (see hgis.client._looks_like_id), so duplicated here -- this module
#: needs the same routing decision one level up, before deciding whether a
#: name search has to look at every project.
_UUID_PATTERN = re.compile(
    r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
)


def _looks_like_id(value: str) -> bool:
    """Whether this reads as a UUID rather than a name."""
    return bool(_UUID_PATTERN.fullmatch(value))


def _resolve_project(project: str) -> Project:
    """One project by name or id. See :meth:`hgis.client.Client.project`."""
    return client().project(project)


def _find_layer_by_name(name: str) -> Layer:
    """
    Search every project for a layer with this name.

    Only reached when no project was given and the name is not an id: a layer
    name is unique within one project, not across the server, so this is the
    one case a name alone does not settle. Costs one request per project.

    :raises hgis.errors.UnknownNameError: no project has a layer by this name,
        or more than one does -- naming every match with its project
    """
    matches: list[tuple[Project, Layer]] = []
    for project in client().projects():
        for candidate in project.layers():
            if candidate.name.casefold() == name.casefold():
                matches.append((project, candidate))

    if len(matches) == 1:
        return matches[0][1]
    if not matches:
        raise UnknownNameError(f"Kein Layer heißt {name!r}, in keinem Projekt.")
    listed = ", ".join(
        f"{layer.name!r} in {project.name!r} ({layer.id})" for project, layer in matches
    )
    raise UnknownNameError(
        f"Mehrere Layer heißen {name!r}: {listed}. Geben Sie project mit an, "
        "oder verwenden Sie die Layer-Id."
    )


def _resolve_layer(layer: str, project: str | None) -> Layer:
    """
    A layer by name or id, the one path every tool below uses.

    With ``project`` given, resolved within it -- id or name, ambiguity and
    unknown names named the way :meth:`hgis.project.Project.layer` names
    them. Without ``project``, an id is looked up directly; a name is
    searched across every project, since a layer's name is unique only
    within its own project. Give ``project`` when you have it -- it is both
    faster and cannot be ambiguous.

    :raises hgis.errors.UnknownNameError: the name or id matches no layer, or
        -- without ``project`` -- matches layers in more than one
    """
    if project is not None:
        return _resolve_project(project).layer(layer)
    if _looks_like_id(layer):
        return client().layer(layer)
    return _find_layer_by_name(layer)


def _build_query(
    layer_obj: Layer,
    *,
    where: str | None,
    bbox: list[float] | None,
    bbox_mode: str | None,
    search: str | None,
) -> Query:
    """
    The restriction ``where``, ``bbox`` and ``search`` describe, built but not
    sent -- shared by :func:`query_features` and :func:`count_features` so the
    two stay in step.
    """
    query = layer_obj.query()
    if where:
        query = query.where(where)
    if search:
        query = query.search(search)
    if bbox is not None:
        if len(bbox) != 4:
            raise ToolError(
                "bbox braucht genau vier Zahlen: min_lng, min_lat, max_lng, max_lat."
            )
        query = query.bbox(*bbox, mode=bbox_mode)
    return query


@server.tool()
def list_projects() -> list[ProjectSummary]:
    """
    Alle Projekte dieses hGIS, mit Anzahl der Layer und Objekte.

    Der übliche erste Aufruf: er liefert die Projekt-Id, die jedes andere
    Werkzeug braucht, und zeigt an den Zahlen, welches Projekt Daten enthält.
    """
    try:
        return [
            ProjectSummary(
                id=project.id,
                name=project.name,
                layer_count=project.layer_count,
                feature_count=project.feature_count,
                description=project.description,
                srid=project.srid,
                extent=extent_list(project.extent),
            )
            for project in client().projects()
        ]
    except Exception as error:
        raise tool_error(error, doing="Lesen der Projektliste") from error


@dataclass(frozen=True)
class ProjectDetail:
    """Ein Projekt mit seiner Layer-Liste, wie describe_project es fand."""

    id: str
    name: str
    description: str | None
    srid: int
    feature_count: int
    extent: list[float] | None
    layers: list[LayerSummary]


@server.tool()
def describe_project(
    project: Annotated[str, Field(description="Name oder Id des Projekts.")],
) -> ProjectDetail:
    """
    Ein Projekt mit der Liste seiner Layer.

    Der Schritt nach list_projects: zeigt, welche Layer ein Projekt enthält,
    mit deren Id, Geometrietyp und Objektzahl -- alles, was describe_layer und
    query_features als layer-Argument brauchen. Ein Name, der auf kein oder
    mehrere Projekte passt, wird mit den vorhandenen Namen gemeldet.
    """
    try:
        found = _resolve_project(project)
        layers = [
            LayerSummary(
                id=item.id,
                name=item.name,
                kind=item.kind,
                feature_count=item.feature_count,
                geometry_type=item.geometry_type,
                srid=item.srid,
                visible=item.visible,
                extent=extent_list(item.extent),
            )
            for item in found.layers()
        ]
        return ProjectDetail(
            id=found.id,
            name=found.name,
            description=found.description,
            srid=found.srid,
            feature_count=found.feature_count,
            extent=extent_list(found.extent),
            layers=layers,
        )
    except Exception as error:
        raise tool_error(error, doing=f"Beschreiben des Projekts {project!r}") from error


@dataclass(frozen=True)
class FieldInfo:
    """Ein Feld eines Layers, wie describe_layer es fand. Siehe hgis.layer.FieldSummary."""

    id: str
    name: str
    column: str
    type: str
    ambiguous: bool = False
    null_count: int | None = None
    minimum: float | None = None
    maximum: float | None = None
    top_values: list[ValueCount] = dataclass_field(default_factory=list)
    truncated: bool = False
    note: str | None = None


@dataclass(frozen=True)
class LayerDetail:
    """
    Was describe_layer über einen Layer fand -- als Struktur, und in ``text``
    zusätzlich als fertiger Fließtext.
    """

    id: str
    name: str
    kind: str
    geometry_type: str | None
    srid: int | None
    feature_count: int
    extent: list[float] | None
    fields: list[FieldInfo]
    sample: list[FeatureRow]
    #: Dieselbe Antwort als druckfertiger Text -- siehe
    #: hgis.layer.LayerDescription.to_text, das genau dafür gebaut ist, in den
    #: Kontext eines Agenten zu gehen.
    text: str


@server.tool()
def describe_layer(
    layer: Annotated[str, Field(description="Name oder Id des Layers.")],
    project: Annotated[
        str | None,
        Field(
            description="Name oder Id des Projekts. Macht die Suche schneller und "
            "eindeutig, wenn layer ein Name ist."
        ),
    ] = None,
    stats: Annotated[
        bool,
        Field(
            description="Auch Leeranteil und Wertebereich je Feld sammeln -- kostet "
            "einen Request pro Feld. Ohne das nur Namen und Typen."
        ),
    ] = True,
    sample: Annotated[
        int, Field(description="Wie viele Beispielzeilen mitgeliefert werden.", ge=1)
    ] = 5,
) -> LayerDetail:
    """
    Alles Wesentliche zu einem Layer in einem Aufruf: Felder, Wertebereiche und
    Beispielzeilen.

    Der Aufruf vor jedem Filtern: er zeigt die echten Feldnamen und was ihre
    Werte tatsächlich sind, statt sie zu raten -- ein Feld, dessen Name auch
    zu einem anderen passt, ist hier mit ambiguous markiert und trägt seine
    Id. text enthält dieselbe Antwort als Fließtext, fertig zum Lesen.
    """
    try:
        layer_obj = _resolve_layer(layer, project)
        description = layer_obj.describe(stats=stats, sample=sample)
        fields = [
            FieldInfo(
                id=item.id,
                name=item.name,
                column=item.column,
                type=item.type,
                ambiguous=item.ambiguous,
                null_count=item.null_count,
                minimum=item.minimum,
                maximum=item.maximum,
                top_values=[ValueCount(value=v, count=c) for v, c in item.top_values],
                truncated=item.truncated,
                note=item.note,
            )
            for item in description.fields
        ]
        sample_rows = [
            FeatureRow(fid=row.fid, properties=row.properties, geometry=row.geometry)
            for row in description.sample
        ]
        return LayerDetail(
            id=layer_obj.id,
            name=description.name,
            kind=description.kind,
            geometry_type=description.geometry_type,
            srid=description.srid,
            feature_count=description.feature_count,
            extent=extent_list(description.extent),
            fields=fields,
            sample=sample_rows,
            text=description.to_text(),
        )
    except Exception as error:
        raise tool_error(error, doing=f"Beschreiben des Layers {layer!r}") from error


@server.tool()
def query_features(
    layer: Annotated[str, Field(description="Name oder Id des Layers.")],
    where: Annotated[
        str | None,
        Field(
            description="Filterausdruck wie bei hgis.Layer.where(): Feldnamen mit "
            "Leerzeichen oder Umlauten in doppelten Anführungszeichen, Werte in "
            "einfachen -- z.B. \"baujahr > 1990 AND \\\"Straße\\\" LIKE 'Kehrwieder%'\". "
            "Vergleiche, LIKE/ILIKE, IS [NOT] NULL, IN, AND, OR, NOT und Klammern sind "
            "erlaubt. Ein unbekanntes Feld meldet der Server mit den vorhandenen Namen."
        ),
    ] = None,
    bbox: Annotated[
        list[float] | None,
        Field(description="[min_lng, min_lat, max_lng, max_lat] in EPSG:4326."),
    ] = None,
    bbox_mode: Annotated[
        str | None,
        Field(
            description='"intersects" für Objekte, die das Rechteck berühren, '
            '"contains" für vollständig enthaltene. Ohne Angabe vergleicht der Server '
            "nur die Bounding Boxes -- schneller, aber gröber."
        ),
    ] = None,
    search: Annotated[
        str | None,
        Field(
            description="Freitext über alle Textfelder des Layers, mit where per AND "
            "verknüpft."
        ),
    ] = None,
    order_by: Annotated[
        str | None, Field(description="Feld, nach dem sortiert wird.")
    ] = None,
    desc: Annotated[
        bool, Field(description="Absteigend statt aufsteigend sortieren.")
    ] = False,
    limit: Annotated[
        int, Field(description="Höchstens so viele Objekte.", ge=1, le=PAGE_SIZE)
    ] = 50,
    geometry: Annotated[
        bool,
        Field(
            description="Geometrie mitliefern. Meist nicht nötig und macht die "
            "Antwort deutlich größer."
        ),
    ] = False,
    project: Annotated[
        str | None, Field(description="Name oder Id des Projekts.")
    ] = None,
) -> QueryResult:
    """
    Objekte eines Layers, gefiltert, sortiert und auf limit begrenzt.

    Der Server filtert, nicht dieses Werkzeug: where, bbox und search
    schränken ein, bevor etwas den Server verlässt -- holen Sie nie einen
    ganzen Layer, um ihn hier zu sieben. truncated sagt, ob es mehr Treffer
    gibt als geliefert wurden; match_count nennt deren echte Gesamtzahl. Bei
    truncated=true reicht limit nicht für alle Treffer -- where enger fassen
    oder limit erhöhen, statt die Antwort für vollständig zu halten.

    Rufen Sie describe_layer zuerst auf, um die echten Feldnamen zu sehen,
    sonst rät where.
    """
    try:
        layer_obj = _resolve_layer(layer, project)
        query = _build_query(
            layer_obj, where=where, bbox=bbox, bbox_mode=bbox_mode, search=search
        )
        if order_by:
            resolved_field = layer_obj.field(order_by)
            query = query.order_by(layer_obj.reference(resolved_field), desc=desc)

        clamped = max(1, min(limit, PAGE_SIZE))
        match_count = query.count()
        rows = list(itertools.islice(query, clamped))
        features = [
            FeatureRow(
                fid=row.fid,
                properties=row.properties,
                geometry=row.geometry if geometry else None,
            )
            for row in rows
        ]
        return QueryResult(
            layer_id=layer_obj.id,
            layer_name=layer_obj.name,
            match_count=match_count,
            features=features,
            truncated=match_count > len(features),
        )
    except Exception as error:
        raise tool_error(error, doing=f"Abfragen des Layers {layer!r}") from error


@server.tool()
def count_features(
    layer: Annotated[str, Field(description="Name oder Id des Layers.")],
    where: Annotated[
        str | None, Field(description="Filterausdruck, wie bei query_features.")
    ] = None,
    bbox: Annotated[
        list[float] | None,
        Field(description="[min_lng, min_lat, max_lng, max_lat] in EPSG:4326."),
    ] = None,
    bbox_mode: Annotated[
        str | None,
        Field(
            description='"intersects" oder "contains", wie bei query_features. Ohne '
            "Angabe vergleicht der Server nur die Bounding Boxes."
        ),
    ] = None,
    search: Annotated[
        str | None, Field(description="Freitext über alle Textfelder, wie bei query_features.")
    ] = None,
    project: Annotated[
        str | None, Field(description="Name oder Id des Projekts.")
    ] = None,
) -> int:
    """
    Wie viele Objekte eines Layers zu einer Einschränkung passen -- ohne die
    Objekte selbst.

    Für eine reine Zahl billiger als query_features: ein Request statt einer
    Antwort mit Daten. Für "wie viele Bäume stehen im Bezirk X" reicht das.
    """
    try:
        layer_obj = _resolve_layer(layer, project)
        query = _build_query(
            layer_obj, where=where, bbox=bbox, bbox_mode=bbox_mode, search=search
        )
        return query.count()
    except Exception as error:
        raise tool_error(error, doing=f"Zählen im Layer {layer!r}") from error


@dataclass(frozen=True)
class FieldValues:
    """Die unterschiedlichen Werte eines Feldes, wie field_values sie fand."""

    field_id: str
    field_name: str
    values: list[ValueCount]
    #: Es gibt mehr unterschiedliche Werte, als values zeigt.
    truncated: bool


@server.tool()
def field_values(
    layer: Annotated[str, Field(description="Name oder Id des Layers.")],
    field: Annotated[
        str, Field(description="Name, Spaltenname oder Id des Feldes.")
    ],
    limit: Annotated[
        int,
        Field(
            description="Höchstens so viele unterschiedliche Werte, häufigster zuerst.",
            ge=1,
        ),
    ] = 20,
    project: Annotated[
        str | None, Field(description="Name oder Id des Projekts.")
    ] = None,
) -> FieldValues:
    """
    Die unterschiedlichen Werte eines Feldes, mit Häufigkeit, häufigster
    zuerst. Null zählt als eigener Wert.

    Zeigt, was in einem Feld tatsächlich steht, bevor where einen Wert
    voraussetzt -- Schreibweise, Groß-/Kleinschreibung, ob "unbekannt" ein
    eigener Wert ist oder Null. truncated sagt, ob es mehr unterschiedliche
    Werte gibt, als limit zeigt. Ein Name, der auf kein oder mehrere Felder
    passt, wird mit den Kandidaten gemeldet.
    """
    try:
        layer_obj = _resolve_layer(layer, project)
        resolved_field = layer_obj.field(field)
        reference = layer_obj.reference(resolved_field)
        # Layer.values() nimmt genau diese Antwort entgegen, wirft dabei aber
        # das eine Feld weg, das hier zählt: answer["truncated"]. Deshalb der
        # direkte Aufruf statt der öffentlichen Methode -- siehe den Bericht
        # an das Team.
        answer = layer_obj._client.get(
            f"/api/layers/{layer_obj.id}/values", field=reference, limit=max(1, limit)
        )
        values = [
            ValueCount(value=entry.get("value"), count=entry.get("count"))
            for entry in answer.get("values") or []
        ]
        return FieldValues(
            field_id=resolved_field.id,
            field_name=resolved_field.name,
            values=values,
            truncated=bool(answer.get("truncated")),
        )
    except Exception as error:
        raise tool_error(error, doing=f"Lesen der Werte von {field!r}") from error


@dataclass(frozen=True)
class StyleInfo:
    """Der Stil eines Layers, wie get_style ihn fand."""

    layer_id: str
    layer_name: str
    #: Der Stil, so wie der Server ihn speichert (camelCase-Schlüssel, wie die
    #: API) -- oder None, was die monochrome Standarddarstellung bedeutet,
    #: nicht ein fehlender Wert.
    style: dict[str, Any] | None


@server.tool()
def get_style(
    layer: Annotated[str, Field(description="Name oder Id des Layers.")],
    project: Annotated[
        str | None, Field(description="Name oder Id des Projekts.")
    ] = None,
) -> StyleInfo:
    """
    Wie ein Layer gezeichnet wird: Renderer, Symbole, Klassen oder Kategorien.

    style ist None für die Standarddarstellung -- einfarbig, ohne eigene
    Klassifikation. Das ist kein Fehlerfall.
    """
    try:
        layer_obj = _resolve_layer(layer, project)
        style = layer_obj.style
        return StyleInfo(
            layer_id=layer_obj.id,
            layer_name=layer_obj.name,
            style=style.to_json() if style is not None else None,
        )
    except Exception as error:
        raise tool_error(error, doing=f"Lesen des Stils von {layer!r}") from error


@dataclass(frozen=True)
class ViewInfo:
    """Wo die Karte steht, wie get_view es fand."""

    project_id: str
    project_name: str
    #: [lng, lat] in EPSG:4326, oder None, wenn das Projekt nie geöffnet wurde.
    center: list[float] | None
    zoom: float | None
    extent: list[float] | None
    active_layer_id: str | None
    active_layer_name: str | None
    basemap: str | None


@server.tool()
def get_view(
    project: Annotated[str, Field(description="Name oder Id des Projekts.")],
) -> ViewInfo:
    """
    Wo die Karte gerade steht: Mitte, Zoom, Ausschnitt, aktiver Layer und
    Basiskarte.

    Zeigt, was der Mensch am Bildschirm sieht -- der Ausgangspunkt, um mit
    select_features oder set_view etwas dort zu zeigen, statt es nur zu
    beschreiben.
    """
    try:
        found = _resolve_project(project)
        view = found.view()
        active = view.active_layer
        return ViewInfo(
            project_id=found.id,
            project_name=found.name,
            center=list(view.center) if view.center else None,
            zoom=view.zoom,
            extent=extent_list(view.extent),
            active_layer_id=active.id if active else None,
            active_layer_name=active.name if active else None,
            basemap=view.basemap,
        )
    except Exception as error:
        raise tool_error(error, doing=f"Lesen der Ansicht von {project!r}") from error


@dataclass(frozen=True)
class SelectionInfo:
    """Was in einem Layer ausgewählt ist, wie get_selection es fand."""

    project_id: str
    project_name: str
    layer_id: str | None
    layer_name: str | None
    fids: list[int]


@server.tool()
def get_selection(
    project: Annotated[str, Field(description="Name oder Id des Projekts.")],
    layer: Annotated[
        str | None,
        Field(
            description="Name oder Id des Layers. Ohne Angabe die Auswahl des "
            "aktiven Layers."
        ),
    ] = None,
) -> SelectionInfo:
    """
    Was gerade ausgewählt ist -- was der Mensch angeklickt hat, oder was ein
    vorheriger select_features-Aufruf dort abgelegt hat.

    Ist kein Layer aktiv und keiner angegeben, ist layer_id None und fids
    leer -- kein Fehlerfall, siehe hgis.Project.selection.
    """
    try:
        found = _resolve_project(project)
        selection = found.selection(layer=layer)
        active = selection.layer
        return SelectionInfo(
            project_id=found.id,
            project_name=found.name,
            layer_id=active.id if active else None,
            layer_name=active.name if active else None,
            fids=list(selection.fids),
        )
    except Exception as error:
        raise tool_error(error, doing=f"Lesen der Auswahl in {project!r}") from error
