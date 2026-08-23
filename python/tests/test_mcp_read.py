"""
The read tools, end to end against the stored responses.

``mcp_client`` (see conftest.py) wires every tool to the same stub the rest of
this suite uses, so a test here calls a tool exactly the way an agent would --
by name, through the module -- and checks what came back against the fixture
it came from.

Two tests build their own tiny transport instead of using the shared one:
:func:`test_get_selection_reads_an_existing_selection`, because
``view-state-with-selection.json`` is not wired into ``stub_server`` (writing
a selection is a write, and this suite makes none -- see the fixture's own
docstring in conftest.py), and
:func:`test_resolve_layer_by_name_searches_every_project`, because a name
search across several projects needs more than one project to exist, which
``stub_server`` does not model.
"""

from __future__ import annotations

import json

import pytest

import hgis
from conftest import (
    AMBIGUOUS_LAYER_ID,
    LAYER_ID,
    PROJECT_ID,
    FakeTransport,
    Response,
    needs_mcp,
    ok,
)
from hgis.errors import UnknownNameError
from hgis.mcp.shapes import ToolError

pytestmark = [needs_mcp, pytest.mark.mcp]


# --- describe_project --------------------------------------------------


def test_describe_project_by_id_lists_its_layers(mcp_client) -> None:
    from hgis.mcp.read_tools import describe_project

    result = describe_project(PROJECT_ID)
    assert result.id == PROJECT_ID
    assert result.name == "Leitungsnetz Nord"
    assert result.feature_count == 3005
    names = [layer.name for layer in result.layers]
    assert "Gebäude Speicherstadt" in names
    assert len(result.layers) == 4


def test_describe_project_by_name_resolves_like_the_id(mcp_client) -> None:
    from hgis.mcp.read_tools import describe_project

    result = describe_project("Leitungsnetz Nord")
    assert result.id == PROJECT_ID


def test_describe_project_ambiguous_name_names_every_candidate(mcp_client) -> None:
    """
    Three projects in projects.json are named "Test" -- the same collision a
    person typing a name runs into, and the message has to name all three.
    """
    from hgis.mcp.read_tools import describe_project

    with pytest.raises(ToolError) as excinfo:
        describe_project("Test")
    text = str(excinfo.value)
    assert "Mehrere Projekte" in text
    assert text.count("019ff") + text.count("019fec3a-f31f") >= 1  # mindestens eine Id genannt


# --- describe_layer ------------------------------------------------------


def test_describe_layer_reports_fields_sample_and_text(mcp_client) -> None:
    from hgis.mcp.read_tools import describe_layer

    result = describe_layer(LAYER_ID)

    assert result.id == LAYER_ID
    assert result.name == "Gebäude Speicherstadt"
    assert result.feature_count == 1003
    assert [f.name for f in result.fields] == ["Straße", "Höhe", "Baujahr"]
    assert len(result.sample) == 5
    assert all(row.geometry is None for row in result.sample)
    assert result.text.startswith("Layer 'Gebäude Speicherstadt'")

    strasse = next(f for f in result.fields if f.name == "Straße")
    assert strasse.null_count == 2
    assert [v.value for v in strasse.top_values][:1] == ["Bäckerweg"]
    assert strasse.top_values[0].count == 250

    hoehe = next(f for f in result.fields if f.name == "Höhe")
    assert hoehe.minimum == 3.5
    assert hoehe.maximum == 14.5
    assert hoehe.null_count == 3


def test_describe_layer_stats_false_skips_the_per_field_requests(
    mcp_client, transport
) -> None:
    from hgis.mcp.read_tools import describe_layer

    result = describe_layer(LAYER_ID, stats=False)

    assert all(field.null_count is None for field in result.fields)
    assert not any(path.endswith(("/values", "/classify")) for path in transport.paths)


def test_describe_layer_by_name_within_a_project(mcp_client) -> None:
    from hgis.mcp.read_tools import describe_layer

    result = describe_layer("Gebäude Speicherstadt", project=PROJECT_ID)
    assert result.id == LAYER_ID


def test_describe_layer_marks_only_the_colliding_spelling_as_ambiguous(
    mcp_client,
) -> None:
    """
    "Stammumfang Quelle" (column stammumfang) and "Stammumfang" (column
    stammumfang_z) collide on the bare word "stammumfang" -- but only the
    field whose own name *is* that word is marked, per hgis.Layer.reference.
    """
    from hgis.mcp.read_tools import describe_layer

    result = describe_layer(AMBIGUOUS_LAYER_ID)

    flagged = {f.name for f in result.fields if f.ambiguous}
    assert flagged == {"Kronendurchmesser", "Stammumfang"}
    # Jedes Feld bekam trotzdem seine Statistik -- keins wurde durch die
    # Mehrdeutigkeit vom Server abgewiesen (siehe conftest._ambiguous_layer_statistics).
    assert all(field.note is None for field in result.fields)


# --- query_features --------------------------------------------------------


def test_query_features_reports_the_true_total_when_truncated(mcp_client) -> None:
    from hgis.mcp.read_tools import query_features

    result = query_features(LAYER_ID)

    assert result.layer_id == LAYER_ID
    assert result.match_count == 1003
    assert len(result.features) == 50
    assert result.truncated is True
    assert all(row.geometry is None for row in result.features)


def test_query_features_limit_is_respected(mcp_client) -> None:
    from hgis.mcp.read_tools import query_features

    result = query_features(LAYER_ID, limit=5)
    assert len(result.features) == 5
    assert result.truncated is True


def test_query_features_with_geometry_includes_it(mcp_client) -> None:
    from hgis.mcp.read_tools import query_features

    result = query_features(LAYER_ID, limit=1, geometry=True)
    assert result.features[0].geometry is not None
    assert result.features[0].geometry["type"] == "MultiPolygon"


def test_query_features_passes_filter_bbox_sort_and_search_through(
    mcp_client, transport
) -> None:
    from hgis.mcp.read_tools import query_features

    query_features(
        LAYER_ID,
        where='"Baujahr" > 1990',
        bbox=[9.9, 53.5, 10.1, 53.6],
        bbox_mode="intersects",
        search="Speicher",
        order_by="Höhe",
        desc=True,
        limit=10,
    )

    # Der Request, der die Zeilen holt -- nach dem count()-Request davor.
    request = transport.requests[-1]
    assert request.param("filter") == '"Baujahr" > 1990'
    assert request.param("search") == "Speicher"
    assert request.param("sort") == "Höhe"
    assert request.param("desc") == "true"
    assert request.param("mode") == "intersects"
    assert request.params["bbox"] == ["9.9", "53.5", "10.1", "53.6"]


def test_query_features_rejects_a_bbox_with_the_wrong_length(mcp_client) -> None:
    from hgis.mcp.read_tools import query_features

    with pytest.raises(ToolError, match="vier Zahlen"):
        query_features(LAYER_ID, bbox=[1.0, 2.0, 3.0])


def test_query_features_resolves_layer_id_directly_without_a_project(
    mcp_client, transport
) -> None:
    from hgis.mcp.read_tools import query_features

    query_features(LAYER_ID, limit=1)
    # Erste Anfrage ist der direkte Lookup, kein Scan über alle Projekte.
    assert transport.paths[0] == f"/api/layers/{LAYER_ID}"


# --- count_features ----------------------------------------------------


def test_count_features_asks_for_the_smallest_page(mcp_client, transport) -> None:
    from hgis.mcp.read_tools import count_features

    total = count_features(LAYER_ID)

    assert total == 1003
    request = transport.requests[-1]
    assert request.path == f"/api/layers/{LAYER_ID}/features"
    assert request.param("size") == "1"


def test_count_features_with_a_filter_uses_the_filtered_total(mcp_client) -> None:
    from hgis.mcp.read_tools import count_features

    total = count_features(LAYER_ID, where='"Straße" = \'Rödingsmarkt\'')
    assert total == 415


def test_count_features_rejects_a_bbox_with_the_wrong_length(mcp_client) -> None:
    from hgis.mcp.read_tools import count_features

    with pytest.raises(ToolError, match="vier Zahlen"):
        count_features(LAYER_ID, bbox=[1.0, 2.0])


# --- field_values --------------------------------------------------------


def test_field_values_reports_frequency_and_truncation(mcp_client) -> None:
    from hgis.mcp.read_tools import field_values

    result = field_values(LAYER_ID, "Straße")

    assert result.field_name == "Straße"
    assert result.truncated is True
    assert len(result.values) == 5  # vier Werte plus der Null-Eintrag
    assert any(v.value is None and v.count == 2 for v in result.values)


def test_field_values_limit_cuts_the_answer(mcp_client) -> None:
    from hgis.mcp.read_tools import field_values

    result = field_values(LAYER_ID, "Straße", limit=1)
    assert len(result.values) == 1
    assert result.values[0].value == "Bäckerweg"


def test_field_values_ambiguous_name_names_both_candidates(mcp_client) -> None:
    """
    "stammumfang" alone fits both "Stammumfang Quelle" (Spalte stammumfang)
    und "Stammumfang" (Name stammumfang) -- siehe hgis.Layer.field. Die
    Meldung muss beide nennen, nicht irgendeine davon wählen.
    """
    from hgis.mcp.read_tools import field_values

    with pytest.raises(ToolError, match="Mehrdeutiges Feld") as excinfo:
        field_values(AMBIGUOUS_LAYER_ID, "stammumfang")
    text = str(excinfo.value)
    assert "Stammumfang Quelle" in text
    assert "Stammumfang" in text


def test_field_values_unknown_name_names_the_available_fields(mcp_client) -> None:
    from hgis.mcp.read_tools import field_values

    with pytest.raises(ToolError, match="Unbekanntes Feld"):
        field_values(LAYER_ID, "osm_id")


# --- get_style -------------------------------------------------------------


def test_get_style_returns_the_stored_renderer(mcp_client) -> None:
    from hgis.mcp.read_tools import get_style

    result = get_style(LAYER_ID)

    assert result.layer_id == LAYER_ID
    assert result.style is not None
    assert result.style["renderer"]["type"] == "categorized"
    assert result.style["renderer"]["field"] == "baujahr"


def test_get_style_none_means_the_default_rendering(mcp_client) -> None:
    from hgis.mcp.read_tools import get_style

    # "Straßen" trägt in layers.json keinen style-Schlüssel.
    result = get_style("Straßen", project=PROJECT_ID)
    assert result.style is None


# --- get_view ----------------------------------------------------------


def test_get_view_reports_centre_zoom_and_active_layer(mcp_client) -> None:
    from hgis.mcp.read_tools import get_view

    result = get_view(PROJECT_ID)

    assert result.project_id == PROJECT_ID
    assert result.center == [10.006136398575336, 53.54585472926746]
    assert result.basemap == "osm"
    # view-state.json nennt OTHER_LAYER_ID als aktiv; /api/layers/{OTHER_LAYER_ID}
    # liefert im Stub denselben Body wie layer.json, dessen "id" LAYER_ID ist --
    # das Werkzeug gibt zurecht wieder, was der Server tatsächlich sagte.
    assert result.active_layer_id == LAYER_ID
    assert result.active_layer_name == "Gebäude Speicherstadt"


# --- get_selection -----------------------------------------------------


@pytest.fixture
def selection_client():
    """
    Ein eigener Client, der die von Hand geschriebene
    view-state-with-selection.json ausliefert -- stub_server tut das nicht,
    siehe das Modul-Docstring dieser Datei.
    """

    def handle(request):
        if request.path == f"/api/projects/{PROJECT_ID}":
            return ok("project.json")
        if request.path == f"/api/projects/{PROJECT_ID}/view-state":
            return ok("view-state-with-selection.json")
        if request.path == f"/api/layers/{LAYER_ID}":
            return ok("layer.json")
        raise AssertionError(f"Unerwartete Anfrage: {request.method} {request.url}")

    fake = FakeTransport(handle)
    stub_client = hgis.connect("http://stub", transport=fake)

    from hgis.mcp.server import use_client

    use_client(stub_client)
    try:
        yield stub_client
    finally:
        use_client(None)


def test_get_selection_reads_an_existing_selection(selection_client) -> None:
    from hgis.mcp.read_tools import get_selection

    result = get_selection(PROJECT_ID, layer=LAYER_ID)

    assert result.layer_id == LAYER_ID
    assert result.fids == [8, 9, 10]


def test_get_selection_without_an_active_layer_is_empty_not_an_error() -> None:
    """
    hgis.Project.selection() gibt für ein Projekt ohne aktiven Layer eine
    leere Auswahl zurück statt zu werfen -- get_selection muss das erhalten,
    nicht in einen Fehler verwandeln.
    """

    def handle(request):
        if request.path == f"/api/projects/{PROJECT_ID}":
            return ok("project.json")
        if request.path == f"/api/projects/{PROJECT_ID}/view-state":
            return Response(200, '{"version":1,"activeLayerId":null,"layers":{}}')
        raise AssertionError(f"Unerwartete Anfrage: {request.method} {request.url}")

    from hgis.mcp.read_tools import get_selection
    from hgis.mcp.server import use_client

    stub_client = hgis.connect("http://stub", transport=FakeTransport(handle))
    use_client(stub_client)
    try:
        result = get_selection(PROJECT_ID)
        assert result.layer_id is None
        assert result.layer_name is None
        assert result.fids == []
    finally:
        use_client(None)


# --- Namensauflösung, gemeinsam für alle Werkzeuge oben --------------------


def test_resolve_layer_by_name_searches_every_project() -> None:
    """
    Ohne project sucht die Namensauflösung in jedem Projekt -- eindeutig, wenn
    nur eins einen Layer mit diesem Namen hat.
    """
    from hgis.mcp.read_tools import _find_layer_by_name
    from hgis.mcp.server import use_client

    project_a = "00000000-0000-0000-0000-00000000000a"
    project_b = "00000000-0000-0000-0000-00000000000b"
    layer_a = "10000000-0000-0000-0000-000000000001"

    projects_page = {
        "items": [
            {
                "id": project_a, "name": "Erstes", "description": None, "srid": 4326,
                "layerCount": 1, "featureCount": 0, "extent": None,
            },
            {
                "id": project_b, "name": "Zweites", "description": None, "srid": 4326,
                "layerCount": 1, "featureCount": 0, "extent": None,
            },
        ],
        "nextCursor": None,
    }
    layers_a = [{
        "id": layer_a, "name": "Bäume", "kind": "VECTOR", "geometryType": "MULTIPOINT",
        "srid": 4326, "featureCount": 5, "visible": True, "zIndex": 0,
        "minZoom": 0, "maxZoom": 22, "extent": None,
    }]
    layers_b: list = []

    def handle(request):
        if request.path == "/api/projects":
            return Response(200, json.dumps(projects_page))
        if request.path == f"/api/projects/{project_a}/layers":
            return Response(200, json.dumps(layers_a))
        if request.path == f"/api/projects/{project_b}/layers":
            return Response(200, json.dumps(layers_b))
        raise AssertionError(f"Unerwartete Anfrage: {request.method} {request.url}")

    stub_client = hgis.connect("http://stub", transport=FakeTransport(handle))
    use_client(stub_client)
    try:
        found = _find_layer_by_name("Bäume")
        assert found.id == layer_a

        with pytest.raises(UnknownNameError, match="Kein Layer heißt"):
            _find_layer_by_name("Wege")
    finally:
        use_client(None)


def test_resolve_layer_by_name_reports_every_project_that_matches() -> None:
    from hgis.mcp.read_tools import _find_layer_by_name
    from hgis.mcp.server import use_client

    project_a = "00000000-0000-0000-0000-00000000000a"
    project_b = "00000000-0000-0000-0000-00000000000b"
    layer_a = "10000000-0000-0000-0000-000000000001"
    layer_b = "20000000-0000-0000-0000-000000000002"

    projects_page = {
        "items": [
            {
                "id": project_a, "name": "Erstes", "description": None, "srid": 4326,
                "layerCount": 1, "featureCount": 0, "extent": None,
            },
            {
                "id": project_b, "name": "Zweites", "description": None, "srid": 4326,
                "layerCount": 1, "featureCount": 0, "extent": None,
            },
        ],
        "nextCursor": None,
    }

    def make_layer(layer_id: str) -> list:
        return [{
            "id": layer_id, "name": "Bäume", "kind": "VECTOR", "geometryType": "MULTIPOINT",
            "srid": 4326, "featureCount": 5, "visible": True, "zIndex": 0,
            "minZoom": 0, "maxZoom": 22, "extent": None,
        }]

    def handle(request):
        if request.path == "/api/projects":
            return Response(200, json.dumps(projects_page))
        if request.path == f"/api/projects/{project_a}/layers":
            return Response(200, json.dumps(make_layer(layer_a)))
        if request.path == f"/api/projects/{project_b}/layers":
            return Response(200, json.dumps(make_layer(layer_b)))
        raise AssertionError(f"Unerwartete Anfrage: {request.method} {request.url}")

    stub_client = hgis.connect("http://stub", transport=FakeTransport(handle))
    use_client(stub_client)
    try:
        with pytest.raises(UnknownNameError) as excinfo:
            _find_layer_by_name("Bäume")
        text = str(excinfo.value)
        assert "Erstes" in text
        assert "Zweites" in text
        assert layer_a in text
        assert layer_b in text
    finally:
        use_client(None)
