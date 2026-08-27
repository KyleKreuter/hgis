"""
Tests for the write tools, against stored responses -- see the module
docstring of ``conftest.py`` for what those are and why most are real.

``conftest.stub_server`` only answers reads, so this file brings its own
handler for the write paths. It shares everything else with ``conftest`` --
``PROJECT_ID``/``LAYER_ID``, ``FakeTransport``, the response files -- and
overrides only ``transport``, ``client`` and ``mcp_client`` by fixture name,
the ordinary way pytest lets one file specialise another's fixtures.

Every assertion here checks two things separately: what the tool *sent* --
read from ``transport.bodies``, not merely inferred from the result -- and
what :class:`~hgis.mcp.shapes.WriteResult` reports back. A test that only
looked at the result would pass even if a tool silently wrote to the wrong
layer.
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

import pytest

import hgis
from conftest import LAYER_ID, PROJECT_ID, FakeTransport, Recorded, load, needs_mcp, ok
from hgis.transport import Response

#: Same file test_writes.py uses -- both measure against exactly the same bytes.
BAEUME_GEOJSON = Path(__file__).parent / "data" / "baeume.geojson"
KAPUTT_GEOJSON = Path(__file__).parent / "data" / "kaputt.geojson"

pytestmark = [needs_mcp, pytest.mark.mcp]

#: "Straßen" -- not the active layer in view-state.json, so aiming set_view at
#: it proves the active layer actually changes.
OTHER_LAYER_ID = "019fecc1-4098-7601-bd23-039619b9a80f"
#: "Flurstücke" -- activeLayerId in the stored view-state.json.
ACTIVE_LAYER_ID = "019fecc1-48a2-76b7-8732-019e83d5532a"
#: The "Straße" field of the stored layer.json, real id and all -- index 0
#: of its fields array, which is exactly why delete_field is not tested
#: against it: a field-mixup bug (deleting fields()[0] instead of resolving
#: by name) would still hit the right field by accident here.
STRASSE_FIELD_ID = "019fecb8-6f22-70d0-b6d7-13a6d85542bf"
#: "Höhe" -- index 1 of the same fields array, used by the delete_field test
#: instead, so a field mixup actually shows up as the wrong id in the
#: outgoing DELETE.
HOEHE_FIELD_ID = "019fecb8-6f22-725a-ad67-57e4211fb2fc"

NEW_LAYER_ID = "019ff9aa-1111-7222-8333-444455556666"
NEW_FIELD_ID = "019ff9aa-2222-7333-8444-555566667777"
#: A layer with no objects, hence no extent -- for set_view's "cannot fly to
#: an empty layer" path.
EMPTY_LAYER_ID = "019ff9aa-3333-7444-8555-666677778888"
#: A project that already exists -- for delete_project, which reads it before
#: it may destroy it.
NEW_PROJECT_ID = "019ff9aa-4444-7555-8666-777788889999"
#: What an inspect_import/import_file test uploads and gets back.
NEW_UPLOAD_ID = "019ff9aa-aaaa-7bbb-8ccc-dddd11112222"
NEW_JOB_ID = "019ff9aa-bbbb-7ccc-8ddd-eeee22223333"
IMPORT_LAYER_ID = "019ff9aa-cccc-7ddd-8eee-ffff33334444"


def _json_response(status: int, body: Any) -> Response:
    return Response(status, json.dumps(body))


def _succeeded_job(*, filename: str, feature_count: int) -> dict[str, Any]:
    """
    A ``JobDtos.Response`` that is already ``SUCCEEDED`` the moment it is
    read -- the write tools' fast path (``Job.wait()`` returns on its very
    first ``refresh()`` without ever touching the live channel), which is
    all this file's stub needs: the channel itself is
    ``hgis.jobs.Job.wait``'s own concern, proven with a dedicated scripted
    transport in ``test_jobs.py``, not re-proven here.
    """
    return {
        "id": NEW_JOB_ID,
        "type": "IMPORT",
        "status": "SUCCEEDED",
        "filename": filename,
        "processedCount": feature_count,
        "totalCount": feature_count,
        "skippedCount": 0,
        "outputLayerId": IMPORT_LAYER_ID,
        "outputProjectId": None,
        "message": None,
        "startedAt": "2026-01-01T00:00:01Z",
        "finishedAt": "2026-01-01T00:00:02Z",
        "createdAt": "2026-01-01T00:00:00Z",
    }


#: Marks that no PATCH has overridden the layer's stored style yet -- distinct
#: from ``None``, which is itself a valid style (the default rendering).
_STYLE_NOT_OVERRIDDEN = object()


def _write_stub(
    request: Recorded,
    body: Any,
    style_state: list[Any] | None = None,
    file: tuple[str, bytes] | None = None,
) -> Response:
    """
    Answers every path a write tool reads or writes.

    Takes the request body too, unlike ``conftest.stub_server`` -- ``POST
    .../edits`` needs it to invent exactly as many fids as were sent, and the
    ``PATCH .../layers/{id}`` branch echoes back what a real server would
    have canonicalised, so a test reading the result back (``set_style``'s
    renderer type, say) is checking something a server actually said, not a
    value this file merely repeated.

    ``style_state`` is the one piece of state a real server would keep and
    this stub otherwise would not: a one-element box holding whatever the
    last ``PATCH .../layers/{LAYER_ID}`` sent as ``style``, so a later GET of
    the same layer reads back what was actually written -- the only way to
    test a set_style -> get_style round trip honestly. ``None`` (no box
    given) behaves as before: every GET answers with the stored fixture,
    unaffected by any earlier write in the same test.

    ``file`` is the multipart upload an import sends, ``(filename, content)``
    -- see ``Transport.request``'s own parameter of the same name.
    """
    path, method = request.path, request.method

    if method == "GET":
        if path == "/api/projects":
            return ok("projects.json")
        if path == f"/api/projects/{PROJECT_ID}":
            return ok("project.json")
        if path == f"/api/projects/{PROJECT_ID}/layers":
            return ok("layers.json")
        if path == f"/api/projects/{PROJECT_ID}/view-state":
            return ok("view-state.json")
        if path == f"/api/projects/{NEW_PROJECT_ID}":
            return _json_response(
                200,
                {
                    "id": NEW_PROJECT_ID,
                    "name": "Agent-Test",
                    "description": None,
                    "srid": 25832,
                    "basemap": "osm",
                    "basemapOpacity": 1.0,
                    "center": None,
                    "zoom": None,
                    "extent": None,
                    "layerCount": 1,
                    "featureCount": 5,
                    "lastOpenedAt": None,
                    "createdAt": "2026-01-01T00:00:00Z",
                    "updatedAt": "2026-01-01T00:00:00Z",
                },
            )
        if path == f"/api/projects/{NEW_PROJECT_ID}/deletion-impact":
            return _json_response(200, {"layerCount": 1, "featureCount": 5})
        if path == f"/api/layers/{LAYER_ID}":
            if style_state is not None and style_state[0] is not _STYLE_NOT_OVERRIDDEN:
                data = json.loads(load("layer.json"))
                data["style"] = style_state[0]
                return _json_response(200, data)
            return ok("layer.json")
        if path == f"/api/layers/{EMPTY_LAYER_ID}":
            return _json_response(
                200,
                {
                    "id": EMPTY_LAYER_ID,
                    "name": "Leerer Layer",
                    "kind": "VECTOR",
                    "geometryType": "MULTIPOINT",
                    "srid": 25833,
                    "featureCount": 0,
                    "visible": True,
                    "extent": None,
                    "style": None,
                    "fields": [],
                },
            )
        if path in (f"/api/layers/{OTHER_LAYER_ID}", f"/api/layers/{ACTIVE_LAYER_ID}"):
            # "Straßen" bzw. "Flurstücke" -- nur ihre Summary aus layers.json,
            # da select_features/set_view keine fields() dieser Layer brauchen.
            wanted_id = path.rsplit("/", 1)[-1]
            summary = next(
                item for item in json.loads(load("layers.json")) if item["id"] == wanted_id
            )
            return _json_response(200, summary)
        if path.startswith(f"/api/layers/{LAYER_ID}/features/"):
            fid = path.rsplit("/", 1)[-1]
            return _json_response(
                200,
                {
                    "fid": int(fid),
                    "rowVersion": f"rv-{fid}",
                    "properties": {"strasse": "Beispielstraße"},
                    "geometry": {"type": "Point", "coordinates": [10.0, 53.5]},
                },
            )
        if path.startswith("/api/layers/") and path.endswith("/features"):
            # select_features prueft mit fid IN (...), wie viele der
            # angegebenen fids es wirklich gibt -- fids 1..1003 gelten hier
            # als vorhanden, unabhaengig vom Layer, das reicht fuer die Tests.
            filter_text = request.param("filter") or ""
            match = re.search(r"fid IN \(([^)]*)\)", filter_text)
            if not match:
                raise AssertionError(f"Unerwartete Filter-Anfrage: {filter_text!r}")
            requested = [int(x.strip()) for x in match.group(1).split(",") if x.strip()]
            existing = [fid for fid in requested if 1 <= fid <= 1003]
            return _json_response(200, {"totalCount": len(existing), "features": []})
        if path == f"/api/jobs/{NEW_JOB_ID}":
            return _json_response(200, _succeeded_job(filename="baeume.geojson", feature_count=3))
        if path == f"/api/layers/{IMPORT_LAYER_ID}":
            return _json_response(
                200,
                {
                    "id": IMPORT_LAYER_ID,
                    "name": "Bäume",
                    "kind": "VECTOR",
                    "geometryType": "MULTIPOINT",
                    "srid": 25833,
                    "featureCount": 3,
                    "visible": True,
                    "extent": None,
                    "style": None,
                },
            )
        raise AssertionError(f"Unerwartete Anfrage: {method} {request.url}")

    if method == "PUT" and path == f"/api/projects/{PROJECT_ID}/view-state":
        return Response(204, "")

    if method == "POST" and path == "/api/projects":
        return _json_response(
            201,
            {
                "id": NEW_PROJECT_ID,
                "name": body["name"],
                "description": body.get("description"),
                "srid": body.get("srid", 25832),
                "basemap": body.get("basemap", "osm"),
                "basemapOpacity": 1.0,
                "center": None,
                "zoom": None,
                "extent": None,
                "layerCount": 0,
                "featureCount": 0,
                "lastOpenedAt": None,
                "createdAt": "2026-01-01T00:00:00Z",
                "updatedAt": "2026-01-01T00:00:00Z",
            },
        )

    if method == "DELETE" and path == f"/api/projects/{NEW_PROJECT_ID}":
        return Response(204, "")

    if method == "PATCH" and path == f"/api/projects/{PROJECT_ID}":
        return _json_response(
            200,
            {
                "id": PROJECT_ID,
                "name": "Leitungsnetz Nord",
                "description": "Trinkwasser und Abwasser",
                "srid": 25833,
                "basemap": body.get("basemap", "osm"),
                "basemapOpacity": body.get("basemapOpacity", 1.0),
                "center": body.get("center", [10.0061, 53.5459]),
                "zoom": body.get("zoom", 15.1),
                "extent": None,
                "layerCount": 4,
                "featureCount": 3005,
                "lastOpenedAt": "2026-08-15T17:21:44.890366Z",
                "createdAt": "2026-08-10T15:11:53.100674Z",
                "updatedAt": "2026-08-15T18:00:00Z",
            },
        )

    if method == "POST" and path == f"/api/projects/{PROJECT_ID}/layers":
        return _json_response(
            200,
            {
                "id": NEW_LAYER_ID,
                "name": body["name"],
                "kind": "VECTOR",
                "geometryType": body["geometryType"],
                "srid": 25833,
                "featureCount": 0,
                "visible": True,
                "extent": None,
                "style": None,
            },
        )

    if method == "PATCH" and path == f"/api/layers/{LAYER_ID}":
        if "style" in body and style_state is not None:
            style_state[0] = body["style"]
        return _json_response(
            200,
            {
                "id": LAYER_ID,
                "name": body.get("name") or "Gebäude Speicherstadt",
                "kind": "VECTOR",
                "geometryType": "MULTIPOLYGON",
                "srid": 25833,
                "featureCount": 1003,
                "visible": body.get("visible", True),
                "extent": None,
                "style": body["style"] if "style" in body else None,
                "basemap": body.get("basemap"),
                "basemapOpacity": body.get("basemapOpacity"),
                "fields": [
                    {
                        "id": STRASSE_FIELD_ID,
                        "sourceName": "Straße",
                        "columnName": "strasse",
                        "dataType": "text",
                    },
                ],
            },
        )

    if method == "DELETE" and path == f"/api/layers/{LAYER_ID}":
        return _json_response(
            200,
            {
                "id": LAYER_ID,
                "name": "Gebäude Speicherstadt",
                "deletedAt": "2026-08-15T12:00:00Z",
                "featureCount": 1003,
                "deletedBy": "test",
            },
        )

    if method == "POST" and path == f"/api/layers/{LAYER_ID}/restore":
        return _json_response(
            200,
            {
                "id": LAYER_ID,
                "name": "Gebäude Speicherstadt",
                "kind": "VECTOR",
                "geometryType": "MULTIPOLYGON",
                "srid": 25833,
                "featureCount": 1003,
                "visible": True,
                "extent": None,
                "style": None,
            },
        )

    if method == "DELETE" and path == f"/api/layers/{LAYER_ID}/purge":
        return _json_response(
            200,
            {
                "id": LAYER_ID,
                "name": "Gebäude Speicherstadt",
                "deletedAt": "2026-08-15T12:00:00Z",
                "featureCount": 1003,
                "deletedBy": "test",
            },
        )

    if method == "POST" and path == f"/api/layers/{LAYER_ID}/edits":
        creates = body.get("creates") or []
        updates = body.get("updates") or []
        deletes = body.get("deletes") or []
        created_fids = {
            str(entry["clientId"]): 1200 + index for index, entry in enumerate(creates, start=1)
        }
        return _json_response(
            200,
            {
                "createdFids": created_fids,
                "updated": len(updates),
                "deleted": len(deletes),
                "dataVersion": 42,
                "featureCount": 1003 + len(creates) - len(deletes),
            },
        )

    if method == "POST" and path.startswith(f"/api/layers/{LAYER_ID}/features/") and path.endswith(
        "/split"
    ):
        split_fid = int(path.rsplit("/", 2)[-2])
        return _json_response(
            200, {"fids": [split_fid, 1301], "dataVersion": 77, "featureCount": 1004}
        )

    if method == "POST" and path == f"/api/layers/{LAYER_ID}/features/merge":
        return _json_response(
            200, {"fid": body["leadFid"], "dataVersion": 78, "featureCount": 1001}
        )

    if method == "POST" and path == f"/api/layers/{LAYER_ID}/fields":
        return _json_response(
            201,
            {
                "id": NEW_FIELD_ID,
                "sourceName": body["name"],
                "columnName": body["name"].lower(),
                "dataType": body["type"].lower(),
            },
        )

    if method == "DELETE" and path.startswith(f"/api/layers/{LAYER_ID}/fields/"):
        # Jedes Feld dieses Layers darf geloescht werden -- welches, sagt
        # der Pfad selbst, den der aufrufende Test danach prueft.
        return Response(204, "")

    if method == "POST" and path == f"/api/projects/{PROJECT_ID}/imports/inspect":
        filename = file[0] if file is not None else "baeume.geojson"
        return _json_response(
            200,
            {
                "uploadId": NEW_UPLOAD_ID,
                "filename": filename,
                "geometryType": "MULTIPOINT",
                "featureCount": None,
                "charset": None,
                "srid": 4326,
                "crsConfidence": "HIGH",
                "extentWgs84": [9.97, 53.53, 9.99, 53.55],
                "fields": [
                    {"name": "gattung", "dataType": "text", "sampleValues": ["Tilia", "Acer"]}
                ],
            },
        )

    if method == "POST" and path == f"/api/projects/{PROJECT_ID}/imports":
        # A real server rejects an unreadable file synchronously, before a
        # job ever exists for it -- see ImportController's own class
        # docstring. Answered here by filename, since that is what a test
        # controls without needing real broken bytes.
        if file is not None and file[0] == "kaputt.geojson":
            return _json_response(
                400,
                {
                    "detail": "Der Import kann die Datei nicht lesen: kaputt.geojson.",
                    "status": 400,
                    "title": "Ungültige Anfrage",
                },
            )
        return _json_response(202, _succeeded_job(filename="baeume.geojson", feature_count=3))

    if method == "POST" and path == f"/api/projects/{PROJECT_ID}/geoportal-imports":
        return _json_response(
            202, _succeeded_job(filename="ÜSG Blattschnitte", feature_count=12)
        )

    raise AssertionError(f"Unerwartete Anfrage: {method} {request.url}")


@pytest.fixture
def transport() -> FakeTransport:
    """
    Like ``conftest.transport``, wired to ``_write_stub`` above.

    ``_write_stub`` needs the request body, which ``FakeTransport.request()``
    does not hand to its handler directly -- only ``Recorded`` (method, url,
    headers). It is already sitting in ``self.bodies[-1]`` by the time the
    handler runs, though, so the closure below reads it from there. The box
    exists only so the handler can refer to the transport it will belong to
    before that object exists yet.

    Also carries a fresh, empty ``style_state`` box, one per test -- see
    ``_write_stub`` for what it is for.
    """
    box: list[FakeTransport] = []
    style_state: list[Any] = [_STYLE_NOT_OVERRIDDEN]

    def handler(request: Recorded) -> Response:
        return _write_stub(request, box[0].bodies[-1], style_state, box[0].files[-1])

    instance = FakeTransport(handler)
    box.append(instance)
    return instance


@pytest.fixture
def client(transport: FakeTransport) -> hgis.Client:
    return hgis.connect("http://stub", transport=transport)


@pytest.fixture
def mcp_client(client: hgis.Client):
    """Like ``conftest.mcp_client``, against the write-capable client above."""
    from hgis.mcp.server import use_client

    use_client(client)
    try:
        yield client
    finally:
        use_client(None)


def _body_of(transport: FakeTransport, request: Recorded) -> Any:
    return transport.bodies[transport.requests.index(request)]


def _write_stub_confirming_less_than_asked(request: Recorded, body: Any) -> Response:
    """
    Like ``_write_stub``, except ``POST .../edits`` confirms one fewer update
    and one fewer delete than the batch asked for -- a real server can do
    this (a row matched a where-clause the client no longer sees, say), and
    the tools must report what came back, not what was sent. Everything else
    falls through to ``_write_stub`` unchanged.
    """
    if request.method == "POST" and request.path == f"/api/layers/{LAYER_ID}/edits":
        updates = body.get("updates") or []
        deletes = body.get("deletes") or []
        return _json_response(
            200,
            {
                "createdFids": {},
                "updated": max(0, len(updates) - 1),
                "deleted": max(0, len(deletes) - 1),
                "dataVersion": 99,
                "featureCount": 1000,
            },
        )
    return _write_stub(request, body)


@pytest.fixture
def underreporting_transport() -> FakeTransport:
    """``transport``'s stub, with ``_write_stub_confirming_less_than_asked``."""
    box: list[FakeTransport] = []

    def handler(request: Recorded) -> Response:
        return _write_stub_confirming_less_than_asked(request, box[0].bodies[-1])

    instance = FakeTransport(handler)
    box.append(instance)
    return instance


@pytest.fixture
def mcp_client_underreporting(underreporting_transport: FakeTransport):
    """Like ``mcp_client``, wired to ``underreporting_transport`` instead."""
    from hgis.mcp.server import use_client

    instance = hgis.connect("http://stub", transport=underreporting_transport)
    use_client(instance)
    try:
        yield instance
    finally:
        use_client(None)


# --- what the person at the screen sees --------------------------------------


#: layer.json/layers.json: "Gebäude Speicherstadt" ist als visible=false
#: abgelegt -- das ist im echten Datensatz so und genau der Fall, für den
#: die Sichtbarkeits-Anmerkung existiert (siehe _visibility_note).
_HIDDEN_LAYER_NOTE = (
    "'Gebäude Speicherstadt' ist derzeit ausgeblendet -- am Bildschirm zeigt "
    "sich nichts davon, bis update_layer(visible=true) das ändert."
)


def test_select_features_by_layer_name(mcp_client, transport) -> None:
    from hgis.mcp.write_tools import select_features

    result = select_features("Leitungsnetz Nord", [10, 11, 12], layer="Gebäude Speicherstadt")

    assert result.summary == (
        f"3 Objekt(e) in 'Gebäude Speicherstadt' ausgewählt. {_HIDDEN_LAYER_NOTE}"
    )
    put = next(r for r in transport.requests if r.method == "PUT")
    body = _body_of(transport, put)
    assert body["activeLayerId"] == LAYER_ID
    assert body["layers"][LAYER_ID]["selection"] == [10, 11, 12]


def test_select_features_without_layer_uses_the_active_one(mcp_client, transport) -> None:
    """
    Kein Layer angegeben -- die Auswahl landet dort, wo der Nutzer gerade
    ist ("Flurstücke"). select_features muss den Layer dafür auflösen (um
    die fids zu prüfen) und nennt ihn deshalb jetzt beim Namen statt nur
    "aktiver Layer" zu sagen -- genauer als vorher, nicht ungenauer.
    """
    from hgis.mcp.write_tools import select_features

    result = select_features(PROJECT_ID, [7])

    assert result.summary == (
        "1 Objekt(e) in 'Flurstücke' ausgewählt. 'Flurstücke' ist derzeit "
        "ausgeblendet -- am Bildschirm zeigt sich nichts davon, bis "
        "update_layer(visible=true) das ändert."
    )
    put = next(r for r in transport.requests if r.method == "PUT")
    body = _body_of(transport, put)
    assert body["activeLayerId"] == ACTIVE_LAYER_ID
    assert body["layers"][ACTIVE_LAYER_ID]["selection"] == [7]


def test_select_features_empty_list_clears_the_selection(mcp_client, transport) -> None:
    from hgis.mcp.write_tools import select_features

    result = select_features(PROJECT_ID, [], layer=LAYER_ID)

    assert result.summary == (
        f"0 Objekt(e) in 'Gebäude Speicherstadt' ausgewählt. {_HIDDEN_LAYER_NOTE}"
    )
    put = next(r for r in transport.requests if r.method == "PUT")
    assert _body_of(transport, put)["layers"][LAYER_ID]["selection"] == []


def test_select_features_reports_fids_that_do_not_exist(mcp_client, transport) -> None:
    """
    fid 9999 gibt es in keinem Layer dieser Fixture (>1003) -- die summary
    muss das nennen, statt unverändert Erfolg zu melden. Die Auswahl wird
    trotzdem gesetzt (das war so gewollt, nur eben nicht ganz erfüllbar).
    """
    from hgis.mcp.write_tools import select_features

    result = select_features(PROJECT_ID, [1, 2, 9999], layer=LAYER_ID)

    assert result.summary == (
        "3 Objekt(e) in 'Gebäude Speicherstadt' ausgewählt, davon gibt es 2 "
        f"tatsächlich; 1 zeigen am Bildschirm ins Leere. {_HIDDEN_LAYER_NOTE}"
    )
    put = next(r for r in transport.requests if r.method == "PUT")
    # Trotz der fehlenden fid wird genau das gesetzt, was verlangt wurde --
    # das Werkzeug korrigiert die Eingabe nicht heimlich.
    assert _body_of(transport, put)["layers"][LAYER_ID]["selection"] == [1, 2, 9999]


def test_select_features_on_a_visible_layer_has_no_note(mcp_client, transport) -> None:
    """"Straßen" ist visible=true -- keine Anmerkung, keine Überraschung."""
    from hgis.mcp.write_tools import select_features

    result = select_features(PROJECT_ID, [1], layer="Straßen")

    assert result.summary == "1 Objekt(e) in 'Straßen' ausgewählt."


def test_set_view_switches_the_active_layer_and_keeps_its_selection(mcp_client, transport) -> None:
    """
    "Straßen" ist in view-state.json nicht aktiv -- der Test beweist also,
    dass sich activeLayerId wirklich ändert, nicht nur, dass der Aufruf
    durchläuft. Die (leere) Auswahl von "Straßen" bleibt dabei erhalten.

    Ohne center/zoom berechnet set_view beides aus dem Ausschnitt des
    Layers -- die PATCH an die Projekt-Endpunkt-Route muss genau die Zahlen
    tragen, die _view_for_extent aus layers.json' Straßen-extent berechnet
    (nicht geraten, sondern hier direkt mit derselben Funktion vorgerechnet).
    """
    from hgis.mcp.write_tools import _view_for_extent, set_view

    straszen_extent = (
        9.980243980721065,
        53.541128324182445,
        9.996605347565506,
        53.547999683229875,
    )
    expected_center, expected_zoom = _view_for_extent(straszen_extent)

    result = set_view(PROJECT_ID, layer="Straßen")

    assert result.summary.startswith("'Straßen' ist jetzt der aktive Layer in 'Leitungsnetz Nord'.")
    assert "aus seinem Ausschnitt berechnet" in result.summary

    put = next(r for r in transport.requests if r.method == "PUT")
    put_body = _body_of(transport, put)
    assert put_body["activeLayerId"] == OTHER_LAYER_ID
    assert put_body["layers"][OTHER_LAYER_ID]["selection"] == []

    patch = next(r for r in transport.requests if r.method == "PATCH" and "/projects/" in r.path)
    patch_body = _body_of(transport, patch)
    assert patch_body["center"] == pytest.approx(expected_center)
    assert patch_body["zoom"] == pytest.approx(expected_zoom)


def test_set_view_explicit_center_and_zoom_without_a_layer(mcp_client, transport) -> None:
    """Reine Kamerabewegung -- kein Layer genannt, also keine PUT view-state."""
    from hgis.mcp.write_tools import set_view

    result = set_view(PROJECT_ID, center=[10.0, 53.5], zoom=16.5)

    assert "aktive Layer" not in result.summary
    assert "Kartenausschnitt gesetzt: Mitte 10.00000, 53.50000, Zoom 16.5" in result.summary
    assert not any(r.method == "PUT" for r in transport.requests)
    patch = next(r for r in transport.requests if r.method == "PATCH" and "/projects/" in r.path)
    assert _body_of(transport, patch) == {"center": [10.0, 53.5], "zoom": 16.5}


def test_set_view_explicit_values_override_the_computed_ones(mcp_client, transport) -> None:
    """
    layer UND center/zoom zusammen: der Layer wird trotzdem aktiv, aber die
    Kamera folgt den gegebenen Zahlen, nicht dem Layer-Ausschnitt -- die
    summary darf dann nicht "berechnet" behaupten.
    """
    from hgis.mcp.write_tools import set_view

    result = set_view(PROJECT_ID, layer="Straßen", center=[1.0, 2.0], zoom=5.0)

    assert "gesetzt" in result.summary
    assert "berechnet" not in result.summary
    patch = next(r for r in transport.requests if r.method == "PATCH" and "/projects/" in r.path)
    assert _body_of(transport, patch) == {"center": [1.0, 2.0], "zoom": 5.0}


def test_set_view_with_nothing_given_is_refused_before_any_request(mcp_client, transport) -> None:
    from hgis.mcp.shapes import ToolError
    from hgis.mcp.write_tools import set_view

    with pytest.raises(ToolError, match="Mindestens eines von layer, center oder zoom"):
        set_view(PROJECT_ID)

    assert transport.requests == []


def test_set_view_rejects_a_malformed_center(mcp_client, transport) -> None:
    from hgis.mcp.shapes import ToolError
    from hgis.mcp.write_tools import set_view

    with pytest.raises(ToolError, match=r"\[lng, lat\]"):
        set_view(PROJECT_ID, center=[1.0, 2.0, 3.0])

    assert transport.requests == []


def test_set_view_cannot_fly_to_an_empty_layer(mcp_client, transport) -> None:
    """
    Kein Ausschnitt, kein center/zoom angegeben -- und weil das VOR jedem
    Schreiben geprüft wird, darf auch der aktive Layer noch nicht
    gewechselt haben: ein halber Erfolg wäre irreführender als ein klarer
    Fehler.
    """
    from hgis.mcp.shapes import ToolError
    from hgis.mcp.write_tools import set_view

    with pytest.raises(ToolError, match="hat keine Objekte"):
        set_view(PROJECT_ID, layer=EMPTY_LAYER_ID)

    assert not any(r.method in ("PUT", "PATCH") for r in transport.requests)


def test_set_view_notes_an_invisible_layer(mcp_client, transport) -> None:
    """
    "Gebäude Speicherstadt" ist in der Fixture visible=false -- set_view
    wechselt trotzdem zu ihm und berechnet den Ausschnitt, meldet aber auch,
    dass am Bildschirm nichts davon zu sehen ist.
    """
    from hgis.mcp.write_tools import set_view

    result = set_view(PROJECT_ID, layer=LAYER_ID)

    assert "'Gebäude Speicherstadt' ist jetzt der aktive Layer" in result.summary
    assert "aus seinem Ausschnitt berechnet" in result.summary
    assert (
        "'Gebäude Speicherstadt' ist derzeit ausgeblendet -- am Bildschirm "
        "zeigt sich nichts davon, bis update_layer(visible=true) das ändert"
    ) in result.summary


# --- projects: what is stored --------------------------------------------


def test_create_project_reports_name_and_srid(mcp_client, transport) -> None:
    from hgis.mcp.write_tools import create_project

    result = create_project("Agent-Test")

    assert result.summary == f"Projekt 'Agent-Test' angelegt (Id {NEW_PROJECT_ID}), SRID 25832."
    post = next(r for r in transport.requests if r.path == "/api/projects")
    assert _body_of(transport, post) == {"name": "Agent-Test"}


def test_create_project_sends_only_the_given_fields(mcp_client, transport) -> None:
    from hgis.mcp.write_tools import create_project

    create_project("Agent-Test", description="Testlauf", srid=25833, basemap="satellite")

    post = next(r for r in transport.requests if r.path == "/api/projects")
    assert _body_of(transport, post) == {
        "name": "Agent-Test",
        "description": "Testlauf",
        "srid": 25833,
        "basemap": "satellite",
    }


def test_delete_project_reports_the_impact_and_deletes_it(mcp_client, transport) -> None:
    from hgis.mcp.write_tools import delete_project

    result = delete_project(NEW_PROJECT_ID, confirm_name="Agent-Test")

    assert result.deleted == 5
    assert result.summary == (
        f"Projekt 'Agent-Test' (Id {NEW_PROJECT_ID}) endgültig gelöscht "
        "(1 Layer, 5 Objekt(e) mit ihm)."
    )
    assert any(
        r.method == "DELETE" and r.path == f"/api/projects/{NEW_PROJECT_ID}"
        for r in transport.requests
    )


def test_delete_project_refuses_a_wrong_confirm_name_and_deletes_nothing(
    mcp_client, transport
) -> None:
    """
    Die wörtliche Namensbestätigung ist die einzige Sicherung, die dieses
    Werkzeug selbst hat -- delete_project ist der einzige unwiederbringliche
    Weg im gesamten Werkzeugsatz. Mutationsprobe (siehe Sitzungsbericht):
    Wird der Vergleich in write_tools.delete_project ausgebaut, muss dieser
    Test rot werden -- belegt, dass er tatsächlich etwas prüft, nicht nur,
    dass irgendein Test existiert.
    """
    from hgis.mcp.shapes import ToolError
    from hgis.mcp.write_tools import delete_project

    with pytest.raises(ToolError, match="stimmt nicht mit dem Namen"):
        delete_project(NEW_PROJECT_ID, confirm_name="Falscher Name")

    assert not any(r.method == "DELETE" for r in transport.requests)


def test_delete_project_confirm_name_is_exact_not_case_insensitive(mcp_client, transport) -> None:
    """Ein Fast-Treffer ist kein Treffer -- sonst wäre die Bestätigung keine."""
    from hgis.mcp.shapes import ToolError
    from hgis.mcp.write_tools import delete_project

    with pytest.raises(ToolError, match="stimmt nicht mit dem Namen"):
        delete_project(NEW_PROJECT_ID, confirm_name="agent-test")

    assert not any(r.method == "DELETE" for r in transport.requests)


# --- importing: what is stored --------------------------------------------


def test_inspect_import_sends_the_file_and_reports_the_preview(mcp_client, transport) -> None:
    from hgis.mcp.write_tools import inspect_import

    result = inspect_import(PROJECT_ID, file_path=str(BAEUME_GEOJSON))

    assert result.upload_id == NEW_UPLOAD_ID
    assert result.geometry_type == "MULTIPOINT"
    assert [f.name for f in result.fields] == ["gattung"]
    post = next(r for r in transport.requests if r.path.endswith("/imports/inspect"))
    assert transport.files[transport.requests.index(post)][0] == "baeume.geojson"


def test_import_file_waits_and_reports_the_finished_layer(mcp_client, transport) -> None:
    from hgis.mcp.write_tools import import_file

    result = import_file(PROJECT_ID, file_path=str(BAEUME_GEOJSON), name="Bäume", timeout=5)

    assert result.status == "SUCCEEDED"
    assert result.job_id == NEW_JOB_ID
    assert result.layer_id == IMPORT_LAYER_ID
    assert result.layer_name == "Bäume"
    assert result.feature_count == 3
    assert "Bäume" in result.summary and "3 Objekt" in result.summary
    post = next(r for r in transport.requests if r.path == f"/api/projects/{PROJECT_ID}/imports")
    assert transport.files[transport.requests.index(post)][0] == "baeume.geojson"


def test_import_file_with_an_upload_id_sends_no_file(mcp_client, transport) -> None:
    from hgis.mcp.write_tools import import_file

    import_file(PROJECT_ID, upload_id=NEW_UPLOAD_ID, timeout=5)

    post = next(r for r in transport.requests if r.path == f"/api/projects/{PROJECT_ID}/imports")
    assert transport.files[transport.requests.index(post)] is None
    assert post.param("uploadId") == NEW_UPLOAD_ID


def test_import_file_rejects_a_broken_file_before_any_job_exists(mcp_client, transport) -> None:
    """
    A synchronously rejected upload (see ImportController's own class
    docstring) must reach the agent as a plain refusal, not as a job it
    then has to notice failed. See test_jobs.py and the session's own live
    run for the two halves this stub cannot cover: a real broken file, and
    a job that actually fails asynchronously.
    """
    from hgis.mcp.shapes import ToolError
    from hgis.mcp.write_tools import import_file

    with pytest.raises(ToolError, match="kaputt.geojson"):
        import_file(PROJECT_ID, file_path=str(KAPUTT_GEOJSON))

    assert not any(r.path == f"/api/jobs/{NEW_JOB_ID}" for r in transport.requests)


def test_import_geoportal_reports_the_finished_layer(mcp_client, transport) -> None:
    from hgis.mcp.write_tools import import_geoportal

    result = import_geoportal(
        PROJECT_ID, "uesg/uesg_blattschnitte",
        bbox=[9.9, 53.5, 10.1, 53.6], fields=["name"], timeout=5,
    )

    assert result.status == "SUCCEEDED"
    # The report reads the layer itself (client().layer(...)), not the job's
    # own processedCount -- see _import_result -- so this is the stubbed
    # layer's featureCount, the same one the file-import test above reads.
    assert result.feature_count == 3
    post = next(
        r for r in transport.requests if r.path == f"/api/projects/{PROJECT_ID}/geoportal-imports"
    )
    body = transport.bodies[transport.requests.index(post)]
    assert body == {
        "datasetId": "uesg/uesg_blattschnitte",
        "bbox": [9.9, 53.5, 10.1, 53.6],
        "fields": ["name"],
    }


def test_job_wait_reads_the_named_job_and_reports_it(mcp_client, transport) -> None:
    """
    The follow-up tool for when import_file's/import_geoportal's own
    ``timeout`` already elapsed once -- here simulated by asking about a job
    that is already done by the time this reads it, the same fast path
    import_file's own test above uses.
    """
    from hgis.mcp.write_tools import job_wait

    result = job_wait(job_id=NEW_JOB_ID, project=PROJECT_ID, timeout=5)

    assert result.status == "SUCCEEDED"
    assert result.layer_id == IMPORT_LAYER_ID


def test_inspect_import_over_the_protocol_serialises_every_field(mcp_client, transport) -> None:
    """
    inspect_import returns hgis.Inspection directly, the same way get_style
    returns hgis.Style -- geht deshalb wie deren eigener Rundlauf-Test über
    server.call_tool(), um zu belegen, dass eine echte Dataclass sich
    tatsächlich durch pydantic in structured_content aufbaut, mit allen
    Feldern, nicht nur beim direkten Python-Aufruf.
    """
    import asyncio

    from hgis.mcp import write_tools  # noqa: F401 -- registriert das Werkzeug
    from hgis.mcp.server import server

    async def call() -> Any:
        return await server.call_tool(
            "inspect_import", {"project": PROJECT_ID, "file_path": str(BAEUME_GEOJSON)}
        )

    result = asyncio.run(call())

    assert not result.is_error, result
    structured = result.structured_content
    assert structured["upload_id"] == NEW_UPLOAD_ID
    assert structured["geometry_type"] == "MULTIPOINT"
    assert structured["srid"] == 4326
    assert structured["fields"][0]["name"] == "gattung"
    assert structured["fields"][0]["sample_values"] == ["Tilia", "Acer"]


# --- objects: what is stored --------------------------------------------


def test_insert_features_reports_the_fid_range_and_sends_the_batch(mcp_client, transport) -> None:
    from hgis.mcp.write_tools import insert_features

    features = [
        hgis.NewFeature(
            geometry={"type": "Point", "coordinates": [1, 2]}, properties={"strasse": "A"}
        ),
        hgis.NewFeature(
            geometry={"type": "Point", "coordinates": [3, 4]}, properties={"strasse": "B"}
        ),
        hgis.NewFeature(geometry={"type": "Point", "coordinates": [5, 6]}),
    ]

    result = insert_features(LAYER_ID, features)

    assert result.inserted == 3
    assert result.new_fids == [1201, 1202, 1203]
    assert result.summary == "3 Objekt(e) in 'Gebäude Speicherstadt' eingefügt, fids 1201-1203."

    edit_request = next(r for r in transport.requests if r.path.endswith("/edits"))
    body = _body_of(transport, edit_request)
    assert len(body["creates"]) == 3
    assert body["creates"][0]["geometry"] == {"type": "Point", "coordinates": [1, 2]}
    assert body["creates"][0]["properties"] == {"strasse": "A"}
    assert "properties" not in body["creates"][2]  # kein properties gegeben


def test_update_features_reads_row_version_before_writing(mcp_client, transport) -> None:
    """
    Zwei fids, damit sichtbar wird, dass jedes einzeln vor dem Schreiben
    gelesen wird -- die gelesene rowVersion muss unverändert im
    Schreib-Rumpf landen, sonst würde der Server jeden Konflikt übersehen.
    """
    from hgis.mcp.write_tools import FeatureChange, update_features

    updates = [
        FeatureChange(fid=1, properties={"strasse": "Neue Straße"}),
        FeatureChange(fid=2, geometry={"type": "Point", "coordinates": [9, 53]}),
    ]

    result = update_features(LAYER_ID, updates)

    assert result.updated == 2
    assert result.summary == "2 Objekt(e) in 'Gebäude Speicherstadt' geändert, fids 1-2."

    feature_gets = [r for r in transport.requests if r.method == "GET" and "/features/" in r.path]
    assert [r.path.rsplit("/", 1)[-1] for r in feature_gets] == ["1", "2"]

    edit_request = next(r for r in transport.requests if r.path.endswith("/edits"))
    body = _body_of(transport, edit_request)
    assert body["updates"] == [
        {"fid": 1, "rowVersion": "rv-1", "properties": {"strasse": "Neue Straße"}},
        {"fid": 2, "rowVersion": "rv-2", "geometry": {"type": "Point", "coordinates": [9, 53]}},
    ]


def test_delete_features_names_the_deleted_fids(mcp_client, transport) -> None:
    from hgis.mcp.write_tools import delete_features

    result = delete_features(LAYER_ID, [5, 6, 7])

    assert result.deleted == 3
    assert result.summary == "3 Objekt(e) in 'Gebäude Speicherstadt' gelöscht, fids 5-7."
    edit_request = next(r for r in transport.requests if r.path.endswith("/edits"))
    assert _body_of(transport, edit_request)["deletes"] == [5, 6, 7]


def test_update_features_reports_what_the_server_actually_confirmed(
    mcp_client_underreporting, underreporting_transport
) -> None:
    """
    Der Server bestätigt hier absichtlich eines von zwei -- WriteResult.updated
    muss die gemeldete Eins nennen, nicht die angeforderte Zwei. Eine
    Rückmeldung, die stattdessen len(updates) zählt, würde einen Erfolg
    behaupten, den der Server so nie zugesagt hat.
    """
    from hgis.mcp.write_tools import FeatureChange, update_features

    result = update_features(
        LAYER_ID,
        [
            FeatureChange(fid=1, properties={"strasse": "A"}),
            FeatureChange(fid=2, properties={"strasse": "B"}),
        ],
    )

    assert result.updated == 1
    assert result.summary == "1 Objekt(e) in 'Gebäude Speicherstadt' geändert, fids 1-2."


def test_delete_features_reports_what_the_server_actually_confirmed(
    mcp_client_underreporting, underreporting_transport
) -> None:
    """Wie oben, nur für deleted -- derselbe Server bestätigt zwei von drei."""
    from hgis.mcp.write_tools import delete_features

    result = delete_features(LAYER_ID, [5, 6, 7])

    assert result.deleted == 2
    assert result.summary == "2 Objekt(e) in 'Gebäude Speicherstadt' gelöscht, fids 5-7."


def test_insert_features_with_incomplete_created_fids_fails_loudly(
    mcp_client_underreporting, underreporting_transport
) -> None:
    """
    Anders als bei updated/deleted gibt es für inserted keinen entsprechenden
    "der Server meldet weniger, als angefordert"-Fall, den man beobachten
    könnte, ohne vorher abzustürzen: hgis.edits.apply_edits() sucht für
    jeden gesendeten Platzhalter zwingend einen Eintrag in createdFids und
    wirft einen nackten KeyError, sobald einer fehlt (edits.py:295, nicht
    diese Datei) -- lange bevor insert_features seine WriteResult baut. Die
    beiden Zahlen fids/features können im Erfolgsfall also nie auseinander-
    laufen; "inserted=len(fids)" gegen "inserted=len(features)" zu prüfen,
    ist hier kein Testlücken-, sondern ein Äquivalenzproblem. Was bleibt und
    hier gilt, ist trotzdem sehenswert: das Werkzeug reicht diesen Absturz
    unverändert weiter, statt ihn als lesbaren deutschen Satz zu tarnen --
    tool_error() lässt einen KeyError bewusst durch (siehe dessen Docstring:
    "ein Bug in diesem Server ist kein hGIS-Fehler").
    """
    from hgis.mcp.write_tools import insert_features

    with pytest.raises(KeyError):
        insert_features(
            LAYER_ID,
            [
                hgis.NewFeature(geometry={"type": "Point", "coordinates": [0, 0]}),
                hgis.NewFeature(geometry={"type": "Point", "coordinates": [1, 1]}),
            ],
        )


# --- layers: what is stored --------------------------------------------


def test_create_layer_sends_geometry_type_and_fields(mcp_client, transport) -> None:
    from hgis.mcp.write_tools import create_layer

    result = create_layer(
        PROJECT_ID, "Bäume", "MULTIPOINT", fields={"Gattung": "TEXT", "Pflanzjahr": "INTEGER"}
    )

    assert result.summary == (
        f"Layer 'Bäume' angelegt (Id {NEW_LAYER_ID}), MULTIPOINT, Felder: Gattung, Pflanzjahr."
    )
    post = next(r for r in transport.requests if r.path == f"/api/projects/{PROJECT_ID}/layers")
    assert _body_of(transport, post) == {
        "name": "Bäume",
        "geometryType": "MULTIPOINT",
        "fields": [{"name": "Gattung", "type": "TEXT"}, {"name": "Pflanzjahr", "type": "INTEGER"}],
    }


def test_update_layer_sends_only_the_given_fields(mcp_client, transport) -> None:
    from hgis.mcp.write_tools import update_layer

    result = update_layer(LAYER_ID, name="Gebäude neu", visible=False)

    assert result.summary == (
        "Layer 'Gebäude neu': umbenannt in 'Gebäude neu', unsichtbar geschaltet."
    )
    patch = next(r for r in transport.requests if r.method == "PATCH")
    assert _body_of(transport, patch) == {"name": "Gebäude neu", "visible": False}


def test_update_layer_with_nothing_given_still_reports_that(mcp_client, transport) -> None:
    from hgis.mcp.write_tools import update_layer

    result = update_layer(LAYER_ID)

    assert result.summary == "Layer 'Gebäude Speicherstadt': keine Änderung angegeben."
    patch = next(r for r in transport.requests if r.method == "PATCH")
    assert _body_of(transport, patch) == {}


def test_set_basemap_without_a_layer_sets_the_projects_own_basemap(mcp_client, transport) -> None:
    from hgis.mcp.write_tools import set_basemap

    result = set_basemap(PROJECT_ID, "opentopo")

    assert result.summary == "Projekt 'Leitungsnetz Nord': Hintergrundkarte auf 'opentopo' gesetzt."
    patch = next(r for r in transport.requests if r.method == "PATCH")
    assert patch.path == f"/api/projects/{PROJECT_ID}"
    assert _body_of(transport, patch) == {"basemap": "opentopo"}


def test_set_basemap_without_a_layer_sends_opacity_too(mcp_client, transport) -> None:
    from hgis.mcp.write_tools import set_basemap

    result = set_basemap(PROJECT_ID, "opentopo", opacity=0.6)

    assert result.summary == (
        "Projekt 'Leitungsnetz Nord': Hintergrundkarte auf 'opentopo' gesetzt, Deckkraft 0.6."
    )
    patch = next(r for r in transport.requests if r.method == "PATCH")
    assert patch.path == f"/api/projects/{PROJECT_ID}"
    assert _body_of(transport, patch) == {"basemap": "opentopo", "basemapOpacity": 0.6}


def test_set_basemap_with_a_layer_overrides_the_layer_not_the_project(
    mcp_client, transport
) -> None:
    """
    The distinction the surface already knows (VERTRAG.md): given ``layer``,
    this writes the layer's own override, PATCH .../layers/{id} -- never the
    project. Without ``opacity``, only ``basemap`` travels, exactly the "one
    overridden without the other" independence resolveBasemapSettings.ts
    documents on the frontend side.
    """
    from hgis.mcp.write_tools import set_basemap

    result = set_basemap(PROJECT_ID, "opentopo", layer="Gebäude Speicherstadt")

    assert result.summary == (
        "Layer 'Gebäude Speicherstadt': Hintergrundkarte auf 'opentopo' gesetzt."
    )
    patch = next(r for r in transport.requests if r.method == "PATCH")
    assert patch.path == f"/api/layers/{LAYER_ID}"
    assert _body_of(transport, patch) == {"basemap": "opentopo"}
    # The project is only ever read to resolve project/layer -- never PATCHed.
    assert not any(
        r.method == "PATCH" and r.path == f"/api/projects/{PROJECT_ID}" for r in transport.requests
    )


def test_set_basemap_with_a_layer_and_opacity_sends_both_fields(mcp_client, transport) -> None:
    from hgis.mcp.write_tools import set_basemap

    result = set_basemap(PROJECT_ID, "opentopo", layer=LAYER_ID, opacity=0.4)

    assert result.summary == (
        "Layer 'Gebäude Speicherstadt': Hintergrundkarte auf 'opentopo' gesetzt, Deckkraft 0.4."
    )
    patch = next(r for r in transport.requests if r.method == "PATCH")
    assert patch.path == f"/api/layers/{LAYER_ID}"
    assert _body_of(transport, patch) == {"basemap": "opentopo", "basemapOpacity": 0.4}


def test_set_basemap_with_a_layer_and_no_basemap_resets_it_to_follow_the_project(
    mcp_client, transport
) -> None:
    """
    The gap this closes: before ``basemap`` could be left out, a layer that
    had been given its own basemap had no way back through this tool -- an
    agent could set it to ``osm`` but never make it follow the project's
    again. Omitting ``basemap`` for a named ``layer`` is what
    ``Layer.update(basemap=None)`` calls a reset -- see test_writes.py's
    ``test_layer_update_basemap_none_resets_it_to_follow_the_project``, the
    library-level proof this tool now reaches through to.
    """
    from hgis.mcp.write_tools import set_basemap

    result = set_basemap(PROJECT_ID, layer=LAYER_ID)

    assert result.summary == (
        "Layer 'Gebäude Speicherstadt': folgt jetzt wieder der Hintergrundkarte "
        "des Projekts."
    )
    patch = next(r for r in transport.requests if r.method == "PATCH")
    assert patch.path == f"/api/layers/{LAYER_ID}"
    assert _body_of(transport, patch) == {"basemap": None}


def test_set_basemap_with_a_layer_no_basemap_and_opacity_only_changes_opacity(
    mcp_client, transport
) -> None:
    """
    Giving ``opacity`` alongside an omitted ``basemap`` is not the reset
    above -- it is the ordinary "just the slider moved" call, and must not
    silently drop whatever basemap override the layer already had. Only
    ``basemapOpacity`` travels; ``basemap`` is left out of the body
    entirely, which is what leaves it exactly as it stood (see
    ``Layer.update``'s own distinction between "not given" and "given as
    None").
    """
    from hgis.mcp.write_tools import set_basemap

    result = set_basemap(PROJECT_ID, layer=LAYER_ID, opacity=0.3)

    assert result.summary == (
        "Layer 'Gebäude Speicherstadt': Deckkraft auf 0.3 gesetzt, "
        "Hintergrundkarte unverändert."
    )
    patch = next(r for r in transport.requests if r.method == "PATCH")
    assert patch.path == f"/api/layers/{LAYER_ID}"
    assert _body_of(transport, patch) == {"basemapOpacity": 0.3}


def test_set_basemap_without_a_layer_or_a_basemap_is_refused_before_any_request(
    mcp_client, transport
) -> None:
    """
    A project has no parent to fall back to, so omitting ``basemap`` without
    ``layer`` cannot mean "reset" the way it does for a layer -- there is
    nothing to reset to. With ``opacity`` also missing, this call would do
    nothing at all; refused with a message naming why, instead of silently
    succeeding at nothing. See the next test for the case one field over --
    ``opacity`` given, which is allowed.
    """
    from hgis.mcp.shapes import ToolError
    from hgis.mcp.write_tools import set_basemap

    with pytest.raises(ToolError, match="übergeordnete Karte"):
        set_basemap(PROJECT_ID)

    assert transport.requests == []


def test_set_basemap_without_a_layer_or_a_basemap_but_with_opacity_still_works(
    mcp_client, transport
) -> None:
    """
    The gap this closes: changing a project's basemap opacity without also
    naming a basemap is an everyday request -- the frontend has its own
    slider for exactly this, independent of the map picker. Before the
    condition above was narrowed to also check ``opacity``, this call was
    refused just like the truly-empty one above, even though there was
    something real to do: set the project's own opacity, leaving its
    basemap untouched.
    """
    from hgis.mcp.write_tools import set_basemap

    result = set_basemap(PROJECT_ID, opacity=0.7)

    assert result.summary == (
        "Projekt 'Leitungsnetz Nord': Deckkraft auf 0.7 gesetzt, Hintergrundkarte "
        "unverändert."
    )
    patch = next(r for r in transport.requests if r.method == "PATCH")
    assert patch.path == f"/api/projects/{PROJECT_ID}"
    assert _body_of(transport, patch) == {"basemapOpacity": 0.7}


def test_delete_layer_moves_to_trash_and_names_the_id_for_restore(mcp_client, transport) -> None:
    from hgis.mcp.write_tools import delete_layer

    result = delete_layer(LAYER_ID)

    assert result.deleted == 1003
    assert f"restore_layer('{LAYER_ID}')" in result.summary
    assert any(
        r.method == "DELETE" and r.path == f"/api/layers/{LAYER_ID}" for r in transport.requests
    )


def test_restore_layer_needs_only_the_id(mcp_client, transport) -> None:
    from hgis.mcp.write_tools import restore_layer

    result = restore_layer(LAYER_ID)

    assert result.summary == f"Layer 'Gebäude Speicherstadt' (Id {LAYER_ID}) wiederhergestellt."
    assert transport.requests[-1].method == "POST"
    assert transport.requests[-1].path == f"/api/layers/{LAYER_ID}/restore"


def test_purge_layer_is_the_only_final_step(mcp_client, transport) -> None:
    from hgis.mcp.write_tools import purge_layer

    result = purge_layer(LAYER_ID)

    assert result.deleted == 1003
    assert "endgültig gelöscht" in result.summary
    assert transport.requests[-1].method == "DELETE"
    assert transport.requests[-1].path == f"/api/layers/{LAYER_ID}/purge"


def test_restore_layer_rejects_a_name_with_its_own_message(mcp_client, transport) -> None:
    """
    Ein Name statt einer Id darf nicht bis zum RequestGuard durchreichen --
    dessen Meldung spricht über erlaubte Schreibwege, nicht darüber, dass
    hier ein Name statt einer Id ankam. Kein Request geht raus.
    """
    from hgis.mcp.shapes import ToolError
    from hgis.mcp.write_tools import restore_layer

    with pytest.raises(ToolError, match="ist keine Layer-Id"):
        restore_layer("Gebäude Speicherstadt")

    assert transport.requests == []


def test_purge_layer_rejects_a_name_with_its_own_message(mcp_client, transport) -> None:
    from hgis.mcp.shapes import ToolError
    from hgis.mcp.write_tools import purge_layer

    with pytest.raises(ToolError, match="ist keine Layer-Id"):
        purge_layer("Gebäude Speicherstadt")

    assert transport.requests == []


# --- fields: what is stored --------------------------------------------


def test_create_field_reports_name_and_type(mcp_client, transport) -> None:
    from hgis.mcp.write_tools import create_field

    result = create_field(LAYER_ID, "Zustand", "TEXT")

    assert result.summary == "Feld 'Zustand' (text) zu 'Gebäude Speicherstadt' hinzugefügt."
    post = next(r for r in transport.requests if r.path == f"/api/layers/{LAYER_ID}/fields")
    assert _body_of(transport, post) == {"name": "Zustand", "type": "TEXT"}


def test_delete_field_resolves_by_source_name(mcp_client, transport) -> None:
    """
    "Höhe" ist absichtlich NICHT das erste Feld von layer.json (das ist
    "Straße", Index 0) -- ein Werkzeug, das aus Versehen fields()[0] statt
    field(name) löscht, würde bei "Straße" zufällig das richtige Feld
    treffen und bei "Höhe" nicht. Reine Namensauflösung reicht hier, weil
    "Höhe" anders als "Stammumfang" in der Baumkataster-Fixture (siehe
    conftest-Modul-Docstring) nicht mehrdeutig ist.
    """
    from hgis.mcp.write_tools import delete_field

    result = delete_field(LAYER_ID, "Höhe")

    assert result.summary == "Feld 'Höhe' aus 'Gebäude Speicherstadt' gelöscht."
    delete = next(r for r in transport.requests if r.method == "DELETE" and "/fields/" in r.path)
    assert delete.path == f"/api/layers/{LAYER_ID}/fields/{HOEHE_FIELD_ID}"


# --- style: what is stored -----------------------------------------------


def test_set_style_sends_the_canonical_json_and_reports_the_renderer(mcp_client, transport) -> None:
    from hgis.mcp.write_tools import set_style

    style = hgis.Style(
        renderer=hgis.Renderer(
            type=hgis.RENDERER_HEATMAP, field="hoehe", ramp="inferno", radius=25.0
        ),
        opacity=0.9,
    )

    result = set_style(LAYER_ID, style)

    assert result.summary == "Stil von 'Gebäude Speicherstadt' gesetzt: Renderer 'heatmap'."
    patch = next(r for r in transport.requests if r.method == "PATCH")
    assert _body_of(transport, patch) == {
        "style": {
            "version": 1,
            "renderer": {"type": "heatmap", "field": "hoehe", "ramp": "inferno", "radius": 25.0},
            "opacity": 0.9,
        }
    }


def test_set_style_none_resets_to_the_default(mcp_client, transport) -> None:
    from hgis.mcp.write_tools import set_style

    result = set_style(LAYER_ID, None)

    assert result.summary == "Stil von 'Gebäude Speicherstadt' zurückgesetzt (Standarddarstellung)."
    patch = next(r for r in transport.requests if r.method == "PATCH")
    assert _body_of(transport, patch) == {"style": None}


def test_set_style_dict_shaped_like_style_also_works(mcp_client, transport) -> None:
    """set_style nimmt hgis.Style, aber layer.set_style darunter auch ein rohes dict."""
    from hgis.mcp.write_tools import set_style

    result = set_style(LAYER_ID, {"renderer": {"type": "single", "symbol": {"kind": "marker"}}})

    assert "Renderer 'single'" in result.summary
    patch = next(r for r in transport.requests if r.method == "PATCH")
    assert _body_of(transport, patch)["style"]["renderer"]["type"] == "single"


def test_style_round_trip_through_get_style_and_set_style_loses_nothing(
    mcp_client, transport
) -> None:
    """
    Genau der Ablauf, den set_styles Docstring empfiehlt: get_style lesen,
    das Ergebnis unverändert an set_style zurückgeben, erneut lesen,
    vergleichen. Geht bewusst über server.call_tool() statt über einen
    direkten Python-Aufruf -- ein direkter Aufruf hätte den eigentlichen
    Fehler nie ausgelöst: to_style_json() reicht ein rohes dict unverändert
    durch, ohne es gegen hgis.Style zu validieren. Nur der Weg über Pydantic
    (echtes MCP-Protokoll) baut die Struktur wirklich aus dem JSON auf und
    hätte camelCase-Schlüssel aus einem rohen get_style-dict still auf None
    fallen lassen, wenn set_style snake_case erwartet.
    """
    import asyncio

    from hgis.mcp import read_tools, write_tools  # noqa: F401 -- registriert beide Seiten
    from hgis.mcp.server import server

    full_style = {
        "renderer": {
            "type": "single",
            "symbol": {
                "kind": "marker",
                "shape": "circle",
                "size": 8.0,
                "fill_color": "#3a86ff",
                "stroke_color": "#111111",
                "stroke_width": 2.0,
            },
        },
        "opacity": 0.85,
    }

    async def round_trip() -> tuple[Any, Any]:
        first_set = await server.call_tool("set_style", {"layer": LAYER_ID, "style": full_style})
        assert not first_set.is_error, first_set

        first_read = await server.call_tool("get_style", {"layer": LAYER_ID})
        assert not first_read.is_error, first_read
        first_style = first_read.structured_content["style"]

        second_set = await server.call_tool("set_style", {"layer": LAYER_ID, "style": first_style})
        assert not second_set.is_error, second_set

        second_read = await server.call_tool("get_style", {"layer": LAYER_ID})
        assert not second_read.is_error, second_read
        return first_style, second_read.structured_content["style"]

    first_style, second_style = asyncio.run(round_trip())

    assert second_style == first_style
    symbol = second_style["renderer"]["symbol"]
    assert symbol["fill_color"] == "#3a86ff"
    assert symbol["stroke_color"] == "#111111"
    assert symbol["stroke_width"] == 2.0


# --- resolving a layer or project by name -----------------------------------


def test_layer_name_without_project_is_refused_before_any_request(mcp_client, transport) -> None:
    from hgis.mcp.shapes import ToolError
    from hgis.mcp.write_tools import insert_features

    with pytest.raises(ToolError, match="project"):
        insert_features(
            "Gebäude Speicherstadt",
            [hgis.NewFeature(geometry={"type": "Point", "coordinates": [0, 0]})],
        )

    assert transport.requests == []


def test_unknown_project_name_names_the_available_ones(mcp_client, transport) -> None:
    from hgis.mcp.shapes import ToolError
    from hgis.mcp.write_tools import select_features

    with pytest.raises(ToolError, match="Unbekanntes Projekt"):
        select_features("Nicht vorhanden", [1])


def test_layer_id_alone_needs_no_project(mcp_client, transport) -> None:
    """Eine Id ist eindeutig -- project bleibt dann optional."""
    from hgis.mcp.write_tools import update_layer

    result = update_layer(LAYER_ID, visible=True)

    assert "sichtbar geschaltet" in result.summary


# --- split_feature and merge_features (Aufgabe 21, Paket A) -----------------


def _line() -> dict:
    return {"type": "LineString", "coordinates": [[9.98, 53.54], [9.982, 53.542]]}


def test_split_feature_reads_the_row_version_first_then_sends_the_cut(
    mcp_client, transport
) -> None:
    from hgis.mcp.write_tools import split_feature

    result = split_feature(LAYER_ID, 1, _line())

    assert result.inserted == 1
    assert result.updated == 1
    assert result.new_fids == [1301]
    assert "geteilt" in result.summary
    assert "1301" in result.summary

    split_request = next(r for r in transport.requests if r.path.endswith("/split"))
    assert split_request.path == f"/api/layers/{LAYER_ID}/features/1/split"
    body = _body_of(transport, split_request)
    assert body == {"line": _line(), "rowVersion": "rv-1"}


def test_merge_features_reads_every_row_version_and_reports_what_was_kept(
    mcp_client, transport
) -> None:
    from hgis.mcp.write_tools import merge_features

    result = merge_features(LAYER_ID, [1, 2, 3], lead_fid=1)

    assert result.updated == 1
    assert result.deleted == 2
    assert "zu Objekt" in result.summary

    merge_request = next(r for r in transport.requests if r.path.endswith("/features/merge"))
    body = _body_of(transport, merge_request)
    assert body == {
        "fids": [1, 2, 3],
        "leadFid": 1,
        "rowVersions": {"1": "rv-1", "2": "rv-2", "3": "rv-3"},
    }


def test_merge_features_with_a_lead_fid_outside_the_selection_sends_nothing(
    mcp_client, transport
) -> None:
    from hgis.mcp.shapes import ToolError
    from hgis.mcp.write_tools import merge_features

    with pytest.raises(ToolError, match="lead_fid"):
        merge_features(LAYER_ID, [1, 2, 3], lead_fid=99)

    assert not any(r.path.endswith("/features/merge") for r in transport.requests)
