"""
Reading and writing ``layer.style`` -- see :mod:`hgis.style` for the schema
itself, mirrored from the server's ``StyleDtos``.

Uses the same ``FakeTransport`` pattern as test_writes.py rather than the
shared read-only fixtures in conftest.py, for the same reason: every test
here is about a write, or about what a write is built from.
"""

from __future__ import annotations

import pytest

import hgis
from conftest import LAYER_ID, FakeTransport
from hgis.style import to_style_json
from hgis.transport import Response


def _client(handler) -> tuple[hgis.Client, FakeTransport]:
    transport = FakeTransport(handler)
    client = hgis.connect("http://stub", transport=transport, client_id="agent-a")
    return client, transport


def _layer(client: hgis.Client, **overrides) -> hgis.Layer:
    data = {
        "id": LAYER_ID,
        "name": "Gebäude Speicherstadt",
        "kind": "VECTOR",
        "geometryType": "MULTIPOLYGON",
        "srid": 25832,
        "featureCount": 1003,
        "visible": True,
        "fields": [],
    }
    data.update(overrides)
    return hgis.Layer(client, data)


# --- reading -----------------------------------------------------------


def test_a_layer_without_a_style_reads_none() -> None:
    client = hgis.connect("http://stub", transport=FakeTransport(lambda r: Response(204, "")))
    assert _layer(client).style is None


def test_style_is_read_from_data_already_carried_no_extra_request() -> None:
    def handle(request: object) -> Response:  # pragma: no cover - must not run
        raise AssertionError("layer.style hat eine Anfrage ausgelöst.")

    client, transport = _client(handle)
    layer = _layer(
        client,
        style={
            "version": 1,
            "renderer": {"type": "single", "symbol": {"kind": "marker", "fillColor": "#404040"}},
            "opacity": 0.8,
        },
    )

    style = layer.style

    assert transport.count == 0
    assert style.renderer.type == "single"
    assert style.renderer.symbol.fill_color == "#404040"
    assert style.opacity == 0.8


def test_style_round_trips_a_heatmap_including_a_null_category_value() -> None:
    """
    Every corner of the schema in one document: heatmap's own radius and
    intensity, and a categorized value of None -- which has to survive as
    None, not vanish the way every other absent member does.
    """
    data = {
        "version": 1,
        "renderer": {
            "type": "categorized",
            "field": "art",
            "categories": [
                {"value": "Straße", "symbol": {"kind": "fill", "fillColor": "#112233"}},
                {"value": None, "label": "ohne Art"},
            ],
        },
        "labels": {"enabled": True, "field": "name", "minZoom": 14},
        "minZoom": 5,
        "maxZoom": 20,
    }
    style = hgis.Style.from_json(data)

    assert style.renderer.categories[1].value is None
    assert style.renderer.categories[1].label == "ohne Art"
    assert style.labels.enabled is True
    assert style.to_json()["renderer"]["categories"][1] == {"value": None, "label": "ohne Art"}


# --- writing -------------------------------------------------------------


def test_set_style_sends_only_the_style_key() -> None:
    def handle(request: object) -> Response:
        return Response(
            200,
            f'{{"id":"{LAYER_ID}","name":"N","kind":"VECTOR",'
            '"geometryType":"MULTIPOLYGON","srid":25832,"featureCount":1003,'
            '"visible":true,"fields":[],'
            '"style":{"version":1,"renderer":{"type":"heatmap","field":"laut_wert","radius":30,'
            '"intensity":1.0,"ramp":"inferno"}}}',
        )

    client, transport = _client(handle)
    layer = _layer(client)

    renderer = hgis.Renderer(
        hgis.RENDERER_HEATMAP, field="Lautstärke Wert", radius=30, ramp="inferno"
    )
    result = layer.set_style(hgis.Style(renderer))

    assert transport.requests[-1].method == "PATCH"
    assert transport.requests[-1].path == f"/api/layers/{LAYER_ID}"
    assert transport.bodies[-1] == {
        "style": {
            "version": 1,
            "renderer": {
                "type": "heatmap",
                "field": "Lautstärke Wert",
                "radius": 30,
                "ramp": "inferno",
            },
        }
    }
    # What the server actually stored -- canonicalised to the column name,
    # not the source name that was sent -- not the value handed in.
    assert result.renderer.field == "laut_wert"
    assert result.renderer.radius == 30
    assert layer.style == result


def test_set_style_accepts_a_plain_dict_too() -> None:
    def handle(request: object) -> Response:
        return Response(
            200,
            f'{{"id":"{LAYER_ID}","name":"N","kind":"VECTOR",'
            '"geometryType":"MULTIPOLYGON","srid":25832,"featureCount":1003,'
            '"visible":true,"fields":[],'
            '"style":{"version":1,"renderer":{"type":"single","symbol":{"kind":"marker"}}}}',
        )

    client, transport = _client(handle)
    layer = _layer(client)

    layer.set_style({"renderer": {"type": "single", "symbol": {"kind": "marker"}}})

    assert transport.bodies[-1] == {
        "style": {"renderer": {"type": "single", "symbol": {"kind": "marker"}}}
    }


def test_set_style_none_resets_and_sends_an_explicit_null() -> None:
    def handle(request: object) -> Response:
        return Response(
            200,
            f'{{"id":"{LAYER_ID}","name":"N","kind":"VECTOR",'
            '"geometryType":"MULTIPOLYGON","srid":25832,"featureCount":1003,'
            '"visible":true,"fields":[]}',
        )

    existing = {"version": 1, "renderer": {"type": "single", "symbol": {"kind": "marker"}}}
    client, transport = _client(handle)
    layer = _layer(client, style=existing)

    result = layer.set_style(None)

    assert transport.bodies[-1] == {"style": None}
    assert result is None
    assert layer.style is None


def test_set_style_carries_the_client_name() -> None:
    def handle(request: object) -> Response:
        return Response(
            200,
            f'{{"id":"{LAYER_ID}","name":"N","kind":"VECTOR",'
            '"geometryType":"MULTIPOLYGON","srid":25832,"featureCount":1003,'
            '"visible":true,"fields":[]}',
        )

    client, transport = _client(handle)
    layer = _layer(client)

    layer.set_style(hgis.Style(hgis.Renderer(hgis.RENDERER_SINGLE, symbol=hgis.Symbol("marker"))))

    assert transport.requests[-1].headers.get(hgis.client.CLIENT_HEADER) == "agent-a"


# --- the server's own ramp catalogue ---------------------------------------


def test_an_unknown_ramp_is_reported_by_the_server_readably() -> None:
    """
    This library keeps no ramp catalogue itself -- see :attr:`hgis.Renderer.ramp`
    for why -- so an unknown ramp name is never caught here, only by the
    server. The body below is not invented: measured against a running
    backend (``LayerStyleService.validateRamp``) with ``ramp="rainbow"`` on a
    heatmap renderer, on 2026-08-17 -- ``"Unbekannter Farbverlauf für ramp:
    rainbow. Erlaubt sind blues, diverging, greens, greys, inferno, reds,
    viridis."``, RFC 7807 body with no ``type`` key. ``validateRamp`` runs
    the same way for a graduated renderer, confirmed at the same time.
    Same 400 shape as every other one this library already handles -- see
    ``_ambiguous_layer_statistics`` in conftest.py for the same pattern with
    an ambiguous field name -- and the point of this test is that nothing
    between the transport and the caller loses or reshapes that ``detail``
    sentence on the way through.
    """

    def handle(request: object) -> Response:
        return Response(
            400,
            '{"title":"Ungültige Anfrage","status":400,'
            '"detail":"Unbekannter Farbverlauf für ramp: rainbow. Erlaubt sind blues, '
            'diverging, greens, greys, inferno, reds, viridis.",'
            f'"instance":"/api/layers/{LAYER_ID}"}}',
        )

    client, transport = _client(handle)
    layer = _layer(client)
    renderer = hgis.Renderer(hgis.RENDERER_HEATMAP, field="Lautstärke Wert", ramp="rainbow")

    with pytest.raises(hgis.ApiError) as error:
        layer.set_style(hgis.Style(renderer))

    assert transport.count == 1  # reached the server -- this library keeps no local list
    message = str(error.value)
    assert "rainbow" in message
    assert "inferno" in message
    assert "viridis" in message
    assert error.value.status == 400


# --- the server's own weight-range rule (weightMin/weightMax) -------------


def test_set_style_sends_the_optional_weight_range_only_when_given() -> None:
    """
    ``weight_min``/``weight_max`` travel through ``to_json`` like every other
    optional member -- present only when given, via the same ``_drop_none``
    mechanism :meth:`test_set_style_sends_only_the_style_key` already proves
    for ``intensity``. What the server sends back is what ``result`` and
    ``layer.style`` read -- the column name the field resolved to, and the
    range echoed back unchanged.
    """

    def handle_with_range(request: object) -> Response:
        return Response(
            200,
            f'{{"id":"{LAYER_ID}","name":"N","kind":"VECTOR",'
            '"geometryType":"MULTIPOLYGON","srid":25832,"featureCount":1003,'
            '"visible":true,"fields":[],'
            '"style":{"version":1,"renderer":{"type":"heatmap","field":"waermebedarf_unsaniert",'
            '"ramp":"inferno","weightMin":0.0,"weightMax":1225563.0}}}',
        )

    client, transport = _client(handle_with_range)
    layer = _layer(client)

    renderer = hgis.Renderer(
        hgis.RENDERER_HEATMAP,
        field="waermebedarf_unsaniert",
        ramp="inferno",
        weight_min=0,
        weight_max=1_225_563,
    )
    result = layer.set_style(hgis.Style(renderer))

    sent_renderer = transport.bodies[-1]["style"]["renderer"]
    assert sent_renderer["weightMin"] == 0
    assert sent_renderer["weightMax"] == 1_225_563
    assert result.renderer.weight_min == 0.0
    assert result.renderer.weight_max == 1225563.0

    def handle_without_range(request: object) -> Response:
        return Response(
            200,
            f'{{"id":"{LAYER_ID}","name":"N","kind":"VECTOR",'
            '"geometryType":"MULTIPOLYGON","srid":25832,"featureCount":1003,'
            '"visible":true,"fields":[],'
            '"style":{"version":1,"renderer":{"type":"heatmap","field":"waermebedarf_unsaniert",'
            '"ramp":"inferno"}}}',
        )

    client, transport = _client(handle_without_range)
    layer = _layer(client)
    renderer = hgis.Renderer(
        hgis.RENDERER_HEATMAP, field="waermebedarf_unsaniert", ramp="inferno"
    )
    result = layer.set_style(hgis.Style(renderer))

    sent_renderer = transport.bodies[-1]["style"]["renderer"]
    assert "weightMin" not in sent_renderer
    assert "weightMax" not in sent_renderer
    assert result.renderer.weight_min is None
    assert result.renderer.weight_max is None


def test_a_one_sided_weight_range_is_reported_by_the_server_readably() -> None:
    """
    This library keeps no check of its own on ``weight_min``/``weight_max`` --
    same reasoning as :func:`test_an_unknown_ramp_is_reported_by_the_server_readably`:
    the server is the sole authority on the rule (here: both or neither, and
    ``weightMax`` strictly greater than ``weightMin``, see
    ``LayerStyleService.requireWeightRange``), so a caller setting only one of
    the two reaches the server and gets its message back unchanged.
    """

    def handle(request: object) -> Response:
        return Response(
            400,
            '{"title":"Ungültige Anfrage","status":400,'
            '"detail":"weightMin und weightMax müssen entweder beide gesetzt sein oder '
            'beide fehlen",'
            f'"instance":"/api/layers/{LAYER_ID}"}}',
        )

    client, transport = _client(handle)
    layer = _layer(client)
    renderer = hgis.Renderer(hgis.RENDERER_HEATMAP, field="waermebedarf_unsaniert", weight_min=0)

    with pytest.raises(hgis.ApiError) as error:
        layer.set_style(hgis.Style(renderer))

    assert transport.count == 1  # reached the server -- this library keeps no local rule
    assert "weightMin" in str(error.value)
    assert "weightMax" in str(error.value)
    assert error.value.status == 400


# --- the local check, before anything is sent -----------------------------


def test_an_unknown_renderer_type_is_refused_before_it_reaches_the_server() -> None:
    def handle(request: object) -> Response:  # pragma: no cover - must not run
        raise AssertionError("Ein Tippfehler haette nichts senden duerfen.")

    client, transport = _client(handle)
    layer = _layer(client)

    with pytest.raises(hgis.InvalidArgumentError) as error:
        layer.set_style({"renderer": {"type": "heatmp"}})

    assert transport.count == 0
    message = str(error.value)
    assert "heatmp" in message
    assert "single" in message
    assert "categorized" in message
    assert "graduated" in message
    assert "heatmap" in message


def test_a_style_without_a_renderer_is_refused_before_it_reaches_the_server() -> None:
    def handle(request: object) -> Response:  # pragma: no cover - must not run
        raise AssertionError("Ein Style ohne renderer haette nichts senden duerfen.")

    client, transport = _client(handle)
    layer = _layer(client)

    with pytest.raises(hgis.InvalidArgumentError):
        layer.set_style({"opacity": 0.5})

    assert transport.count == 0


def test_a_non_style_non_dict_is_refused() -> None:
    with pytest.raises(hgis.InvalidArgumentError):
        to_style_json("heatmap")


def test_none_needs_no_renderer_it_means_reset() -> None:
    assert to_style_json(None) is None


def test_an_unsupported_version_is_refused_before_it_reaches_the_server() -> None:
    """
    Structurally the same case as the renderer-type typo above: a small,
    fixed set the server checks by equality, not by a range -- so it is
    caught here too, rather than reaching the server and coming back as an
    HTTP 400 that only repeats what this function already knows.
    """

    def handle(request: object) -> Response:  # pragma: no cover - must not run
        raise AssertionError("Eine unbekannte Version haette nichts senden duerfen.")

    client, transport = _client(handle)
    layer = _layer(client)
    renderer = hgis.Renderer(hgis.RENDERER_SINGLE, symbol=hgis.Symbol("marker"))

    with pytest.raises(hgis.InvalidArgumentError) as error:
        layer.set_style(hgis.Style(renderer, version=2))

    assert transport.count == 0
    assert "2" in str(error.value)
    assert str(hgis.style.SUPPORTED_VERSION) in str(error.value)


def test_a_style_with_no_version_at_all_is_not_refused() -> None:
    """A dict built by hand, without a version key, means the same as version=None on the server."""
    assert to_style_json({"renderer": {"type": "single", "symbol": {"kind": "marker"}}}) is not None


# --- the guard: still just the one already-open PATCH path -----------------


def test_style_travels_through_the_layer_patch_the_guard_already_allows() -> None:
    """
    3.1/3.3 of the contract: PATCH /api/layers/{id} was already open before
    this package; writing a style must not need, and must not have opened,
    anything new at the guard.
    """
    transport = FakeTransport(lambda request: Response(204, ""))
    client = hgis.connect("http://stub", transport=transport)

    client._send("PATCH", f"/api/layers/{LAYER_ID}", json={"style": None})

    assert transport.count == 1
