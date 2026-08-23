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
from typing import Any

import pytest

import hgis
from conftest import LAYER_ID, PROJECT_ID, FakeTransport, Recorded, needs_mcp, ok
from hgis.transport import Response

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


def _json_response(status: int, body: Any) -> Response:
    return Response(status, json.dumps(body))


def _write_stub(request: Recorded, body: Any) -> Response:
    """
    Answers every path a write tool reads or writes.

    Takes the request body too, unlike ``conftest.stub_server`` -- ``POST
    .../edits`` needs it to invent exactly as many fids as were sent, and the
    ``PATCH .../layers/{id}`` branch echoes back what a real server would
    have canonicalised, so a test reading the result back (``set_style``'s
    renderer type, say) is checking something a server actually said, not a
    value this file merely repeated.
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
        if path == f"/api/layers/{LAYER_ID}":
            return ok("layer.json")
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
        raise AssertionError(f"Unerwartete Anfrage: {method} {request.url}")

    if method == "PUT" and path == f"/api/projects/{PROJECT_ID}/view-state":
        return Response(204, "")

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
    """
    box: list[FakeTransport] = []

    def handler(request: Recorded) -> Response:
        return _write_stub(request, box[0].bodies[-1])

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


def test_select_features_by_layer_name(mcp_client, transport) -> None:
    from hgis.mcp.write_tools import select_features

    result = select_features("Leitungsnetz Nord", [10, 11, 12], layer="Gebäude Speicherstadt")

    assert result.summary == "3 Objekt(e) in 'Gebäude Speicherstadt' ausgewählt."
    put = next(r for r in transport.requests if r.method == "PUT")
    body = _body_of(transport, put)
    assert body["activeLayerId"] == LAYER_ID
    assert body["layers"][LAYER_ID]["selection"] == [10, 11, 12]


def test_select_features_without_layer_uses_the_active_one(mcp_client, transport) -> None:
    """Kein Layer angegeben -- die Auswahl landet dort, wo der Nutzer gerade ist."""
    from hgis.mcp.write_tools import select_features

    result = select_features(PROJECT_ID, [7])

    assert result.summary == "1 Objekt(e) im aktiven Layer ausgewählt."
    put = next(r for r in transport.requests if r.method == "PUT")
    body = _body_of(transport, put)
    assert body["activeLayerId"] == ACTIVE_LAYER_ID
    assert body["layers"][ACTIVE_LAYER_ID]["selection"] == [7]


def test_select_features_empty_list_clears_the_selection(mcp_client, transport) -> None:
    from hgis.mcp.write_tools import select_features

    result = select_features(PROJECT_ID, [], layer=LAYER_ID)

    assert result.summary == "0 Objekt(e) in 'Gebäude Speicherstadt' ausgewählt."
    put = next(r for r in transport.requests if r.method == "PUT")
    assert _body_of(transport, put)["layers"][LAYER_ID]["selection"] == []


def test_set_view_switches_the_active_layer_and_keeps_its_selection(mcp_client, transport) -> None:
    """
    "Straßen" ist in view-state.json nicht aktiv -- der Test beweist also,
    dass sich activeLayerId wirklich ändert, nicht nur, dass der Aufruf
    durchläuft. Die (leere) Auswahl von "Straßen" bleibt dabei erhalten.

    Die summary muss den Vorbehalt aus dem Docstring wiederholen -- ein
    Agent, der nur die Erfolgsmeldung in seinem Verlauf behält, sonst denkt,
    der Kartenausschnitt sei mitgewandert.
    """
    from hgis.mcp.write_tools import set_view

    result = set_view(PROJECT_ID, "Straßen")

    assert result.summary.startswith("'Straßen' ist jetzt der aktive Layer in 'Leitungsnetz Nord'.")
    assert "NICHT bewegt" in result.summary
    put = next(r for r in transport.requests if r.method == "PUT")
    body = _body_of(transport, put)
    assert body["activeLayerId"] == OTHER_LAYER_ID
    assert body["layers"][OTHER_LAYER_ID]["selection"] == []


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
