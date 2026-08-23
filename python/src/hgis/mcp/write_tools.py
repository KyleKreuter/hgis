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
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import hgis
from hgis.client import _looks_like_id
from hgis.errors import InvalidArgumentError
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


# --- what the person at the screen sees --------------------------------------


@server.tool()
def select_features(project: str, fids: list[int], layer: str | None = None) -> WriteResult:
    """
    Setzt die Auswahl in einem Layer -- das, was am Bildschirm markiert
    erscheint. Nichts geht dabei verloren: die Objekte selbst bleiben
    unberührt, und der nächste Aufruf ersetzt die Auswahl wieder.

    Das Werkzeug, mit dem ein Agent ein Ergebnis zeigt statt es nur zu
    beschreiben -- zum Beispiel die Treffer einer vorherigen Filterabfrage.

    :param fids: die auszuwählenden Objekte; eine leere Liste hebt die
        Auswahl auf
    :param layer: Name oder Id des Layers. Ohne Angabe der zuletzt aktive
        Layer dieses Projekts -- fehlt der, schlägt der Aufruf fehl und
        nennt, wie der Layer stattdessen anzugeben ist. Der genannte Layer
        wird dabei zum aktiven Layer, falls er es nicht schon war.
    """
    try:
        proj = _project(project)
        target = proj.layer(layer) if layer is not None else None
        proj.select(fids, layer=target)
        if target is not None:
            summary = f"{len(fids)} Objekt(e) in '{target.name}' ausgewählt."
        else:
            summary = f"{len(fids)} Objekt(e) im aktiven Layer ausgewählt."
        return WriteResult(summary=summary)
    except Exception as error:
        raise tool_error(error, doing=f"Auswählen in Projekt '{project}'") from error


@server.tool()
def set_view(project: str, layer: str) -> WriteResult:
    """
    Macht ``layer`` zum aktiven Layer des Projekts -- das, worauf der
    Anwender gerade schaut. Nichts geht dabei verloren: Auswahl, Sortierung
    und Filter jedes Layers, dieses eingeschlossen, bleiben unverändert.

    Bewegt NICHT den Kartenausschnitt (Zentrum, Zoom): der Server hat dafür
    einen eigenen Weg, aber die Python-Bibliothek, auf der dieser MCP-Server
    steht, öffnet ihn in dieser Stufe noch nicht -- siehe die Anmerkungen
    zu diesem Werkzeug im Bericht der schreibenden Stufe. Wer den Ausschnitt
    bewegen will, muss das vorerst am Bildschirm selbst tun.
    """
    try:
        proj = _project(project)
        target = proj.layer(layer)
        current = proj.selection(layer=target)
        proj.select(current.fids, layer=target)
        return WriteResult(summary=f"'{target.name}' ist jetzt der aktive Layer in '{proj.name}'.")
    except Exception as error:
        raise tool_error(error, doing=f"Aktivieren des Layers '{layer}' in '{project}'") from error


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
    layer: str, features: list[hgis.NewFeature], project: str | None = None
) -> WriteResult:
    """
    Legt neue Objekte in einem Layer an.

    Rückgängig machen lässt sich das nur, indem die zurückgegebenen fids mit
    delete_features wieder gelöscht werden -- ein Fehlgriff hier ist also
    kein Datenverlust, aber ein zweiter Aufruf.

    :param features: je Objekt mindestens die Geometrie (GeoJSON, EPSG:4326);
        ``properties`` nach Spaltenname, eine weggelassene Spalte bekommt
        ihren Vorgabewert
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
    layer: str, updates: list[FeatureChange], project: str | None = None
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

    :param updates: je Objekt siehe :class:`FeatureChange`
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
def delete_features(layer: str, fids: list[int], project: str | None = None) -> WriteResult:
    """
    Löscht Objekte aus einem Layer. Nicht rückgängig zu machen über dieses
    Werkzeug oder diese Bibliothek -- nur das Änderungsprotokoll des Servers
    kennt Geometrie und Attribute danach noch. Anders als ein gelöschter
    ganzer Layer (siehe delete_layer), der in den Papierkorb geht.

    :param fids: die zu löschenden Objekte, einzeln benannt. Es gibt keinen
        Filter-basierten oder "alles löschen"-Weg -- das würde eine leere
        oder falsch verstandene Auswahl zum ganzen Layer machen.
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
    project: str,
    name: str,
    geometry_type: str,
    fields: dict[str, str] | None = None,
) -> WriteResult:
    """
    Legt einen neuen, leeren Layer in einem Projekt an. Rückgängig machen
    lässt sich das mit delete_layer -- ein leerer Layer, der niemandem
    fehlt, ist kein Verlust.

    :param geometry_type: MULTIPOINT, MULTILINESTRING, MULTIPOLYGON oder
        GEOMETRY -- Letzteres für einen Layer, der von Anfang an
        unterschiedliche Geometrietypen mischen soll
    :param fields: Attributfelder, die gleich mitangelegt werden -- Name auf
        Typ. Typ ist einer von TEXT, INTEGER, BIGINT, DOUBLE, NUMERIC,
        BOOLEAN, DATE, TIME, TIMESTAMP. Ohne Angabe ist der Layer gültig,
        zeigt aber nur fid.
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
    layer: str,
    name: str | None = None,
    visible: bool | None = None,
    project: str | None = None,
) -> WriteResult:
    """
    Ändert Name und/oder Sichtbarkeit eines Layers. Nichts geht dabei
    verloren -- ein erneuter Aufruf setzt beides wieder zurück.

    Beide Argumente sind einzeln optional; weggelassen bleibt der jeweilige
    Wert unverändert. Für den Stil siehe set_style, für Löschen delete_layer.
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
def delete_layer(layer: str, project: str | None = None) -> WriteResult:
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
def restore_layer(layer_id: str) -> WriteResult:
    """
    Holt einen Layer aus dem Papierkorb zurück, mit allen seinen Objekten.

    Braucht die Id, nicht den Namen -- ein Layer im Papierkorb ist über sein
    Projekt nicht mehr auflösbar. delete_layer nennt diese Id in seiner
    eigenen Rückmeldung.
    """
    try:
        data = client().restore_layer(layer_id)
        name = data.get("name") if isinstance(data, dict) else None
        label = f"Layer '{name}' (Id {layer_id})" if name else f"Layer {layer_id}"
        return WriteResult(summary=f"{label} wiederhergestellt.")
    except Exception as error:
        raise tool_error(error, doing=f"Wiederherstellen des Layers {layer_id}") from error


@server.tool()
def purge_layer(layer_id: str) -> WriteResult:
    """
    Löscht einen Layer aus dem Papierkorb endgültig -- Geometrie und
    Attribute sind danach weg, auch aus dem Änderungsprotokoll. Der einzige
    wirklich unwiederbringliche Schritt in diesem Werkzeugsatz.

    Nur für einen Layer, der bereits per delete_layer im Papierkorb liegt.
    Prüfen Sie im Zweifel erst, was dort liegt, bevor Sie dies aufrufen --
    dieser Server bietet dafür bislang kein eigenes Werkzeug, siehe den
    Bericht der schreibenden Stufe.
    """
    try:
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
def create_field(layer: str, name: str, type: str, project: str | None = None) -> WriteResult:  # noqa: A002
    """
    Fügt einem Layer ein Attributfeld hinzu -- eine neue Spalte in seiner
    Tabelle. Rückgängig machen lässt sich das mit delete_field, solange noch
    niemand Werte hineingeschrieben hat, die dabei verloren gingen.

    :param type: einer von TEXT, INTEGER, BIGINT, DOUBLE, NUMERIC, BOOLEAN,
        DATE, TIME, TIMESTAMP. Ein unbekanntes Token meldet der Server mit
        den gültigen Namen.
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
def delete_field(layer: str, field: str, project: str | None = None) -> WriteResult:
    """
    Löscht ein Attributfeld -- die Spalte und ihr Inhalt sind danach weg.
    Nur über das Änderungsprotokoll des Servers wiederherstellbar, nicht
    über dieses Werkzeug oder diese Bibliothek.

    :param field: Name, Spaltenname oder Id des Felds. Ein Name, der zu
        mehreren Feldern eines Layers passt, wird abgelehnt statt geraten --
        nennen Sie dann die Feld-Id.
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
def set_style(layer: str, style: hgis.Style | None, project: str | None = None) -> WriteResult:
    """
    Ersetzt den Stil eines Layers vollständig -- es gibt keine
    Teil-Aktualisierung. Der vorherige Stil ist danach weg, aber nicht
    unwiederbringlich: ein erneuter Aufruf mit dem alten Stand stellt ihn
    wieder her. Lesen Sie dafür vorher den Stil des Layers, statt ihn zu
    raten.

    ``style`` muss angegeben werden, darf aber ausdrücklich ``null`` sein --
    das setzt den Layer auf die monochrome Standarddarstellung zurück.

    Vier Renderer-Typen, in ``style.renderer.type``: "single" (ein Symbol für
    alle Objekte), "categorized" (ein Symbol je Attributwert), "graduated"
    (ein Symbol je Wertebereich), "heatmap" (Dichte statt einzelner Symbole).
    Welche der übrigen Renderer-Felder gebraucht werden, hängt von diesem
    Typ ab -- "single" braucht nur symbol, "heatmap" braucht field, radius
    und ramp, und so weiter; siehe die Felder von Renderer im Eingabe-Schema
    dieses Werkzeugs.

    Eine Heatmap zum Beispiel:
        {"renderer": {"type": "heatmap", "field": "lautstaerke", "ramp": "inferno"}}
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
