"""Lazy building, one request per run, and paging that does not stop early."""

from __future__ import annotations

import pytest
from conftest import LAYER_ID, load, stored

import hgis
from hgis.transport import Response

# --- nothing travels while building ---------------------------------------


def test_building_sends_nothing(layer, transport) -> None:
    """
    where, bbox, search and order_by describe; they do not ask.

    The whole design rests on this: a chain that asked as it was built would
    make every intermediate step a round trip, and the last one would still
    have to fetch everything.
    """
    query = (
        layer.where('"Höhe" > 10')
        .bbox(9.9, 53.5, 10.1, 53.6)
        .search("Burstah")
        .order_by("Baujahr", desc=True)
    )

    assert transport.count == 0
    assert isinstance(query, hgis.Query)


def test_where_bbox_count_makes_exactly_one_request(layer, transport) -> None:
    """The claim from the contract, measured rather than asserted in prose."""
    layer.where('"Höhe" > 10').bbox(9.9, 53.5, 10.1, 53.6).count()

    assert transport.count == 1
    request = transport.requests[0]
    assert request.method == "GET"
    assert request.path == f"/api/layers/{LAYER_ID}/features"
    assert request.param("filter") == '"Höhe" > 10'
    assert request.params["bbox"] == ["9.9", "53.5", "10.1", "53.6"]
    # The smallest page there is: the count rides along with it.
    assert request.param("size") == "1"
    assert request.param("geometry") == "false"


def test_count_reads_the_servers_total(layer) -> None:
    """Not the number of rows in the page -- the total the server computed."""
    assert layer.where('"Höhe" > 10').count() == 415
    assert layer.count() == stored("features-size1.json")["totalCount"]


# --- the query is a value, not a state ------------------------------------


def test_narrowing_leaves_the_original_alone(layer, transport) -> None:
    """
    ``narrow = wide.where(...)`` must not change ``wide``.

    A builder that mutated in place would make a query impossible to hand
    around: whoever held it would find it changed underneath them.
    """
    wide = layer.where('"Höhe" > 10')
    narrow = wide.where("Baujahr > 1950")

    wide.count()
    assert transport.requests[-1].param("filter") == '"Höhe" > 10'

    narrow.count()
    assert transport.requests[-1].param("filter") == '("Höhe" > 10) AND (Baujahr > 1950)'


def test_two_filters_combine_with_and(layer, transport) -> None:
    """Each call narrows what the previous one left. Parentheses keep OR safe."""
    layer.where("a = 1 OR b = 2").where("c = 3").count()
    assert transport.requests[0].param("filter") == "(a = 1 OR b = 2) AND (c = 3)"


def test_order_direction_travels_only_with_a_field(layer, transport) -> None:
    """desc without sort would ask the server to reverse the fid order silently."""
    layer.count()
    assert transport.requests[-1].param("desc") is None

    layer.order_by("Baujahr", desc=True).count()
    assert transport.requests[-1].param("sort") == "Baujahr"
    assert transport.requests[-1].param("desc") == "true"


def test_the_selection_mode_travels(layer, transport) -> None:
    """"contains" and "intersects" mean different rectangles; both reach the server."""
    layer.bbox(9.9, 53.5, 10.1, 53.6, mode="contains").count()
    assert transport.requests[0].param("mode") == "contains"


# --- paging ---------------------------------------------------------------


def test_iterating_pages_past_the_thousand_row_ceiling(layer, transport) -> None:
    """
    1003 objects arrive as 1000 plus 3, not as 1000.

    The server caps a page at 1000, so a library that asked once and stopped
    would report a layer that ends early -- and would look right while doing
    it.
    """
    features = list(layer)

    assert len(features) == 1003
    assert transport.count == 2
    assert transport.requests[0].param("cursor") is None
    expected_cursor = stored("features-geometry-page1.json")["nextCursor"]
    assert transport.requests[1].param("cursor") == expected_cursor
    # No duplicates across the page boundary: a keyset that repeats a row is
    # the classic paging bug, and it would still produce 1003 of something.
    assert len({feature.fid for feature in features}) == 1003


def test_iteration_never_asks_for_more_than_the_server_allows(layer, transport) -> None:
    """
    The ceiling is 1000. Above it the server answers 400, and an older one
    clamped silently instead -- a page that came back quietly shortened reads
    exactly like a short page and would end the walk early. Neither failure is
    worth risking, so nothing here ever asks for more.
    """
    list(layer)
    for request in transport.requests:
        assert int(request.param("size")) <= 1000


def test_breaking_out_early_stops_the_requests(layer, transport) -> None:
    """Pages are fetched as they are consumed, not all at once up front."""
    for _ in layer:
        break
    assert transport.count == 1


def test_restrictions_travel_on_every_page(layer, transport) -> None:
    """A filter dropped after page one would quietly widen the result."""
    list(layer.where('"Höhe" > 10').order_by("Baujahr"))
    assert len(transport.requests) == 2
    for request in transport.requests:
        assert request.param("filter") == '"Höhe" > 10'
        assert request.param("sort") == "Baujahr"


# --- fids -----------------------------------------------------------------


def test_fids_uses_the_dedicated_endpoint(layer, transport) -> None:
    """A filter alone is exactly what the fids endpoint answers -- one request."""
    fids = layer.where('"Höhe" > 10').fids()

    assert transport.count == 1
    assert transport.requests[0].path == f"/api/layers/{LAYER_ID}/features/fids"
    assert fids == stored("fids-filtered.json")["fids"]


def test_fids_pages_when_the_endpoint_cannot_answer(layer, transport) -> None:
    """
    The fids endpoint takes filter and search, nothing else.

    A bbox or an ordering is not part of it, so those walk the features
    instead -- slower, and right, rather than fast and quietly unrestricted.
    """
    fids = layer.bbox(9.9, 53.5, 10.1, 53.6).fids()

    assert len(fids) == 1003
    assert all(request.path.endswith("/features") for request in transport.requests)
    assert transport.requests[0].params["bbox"] == ["9.9", "53.5", "10.1", "53.6"]


def test_ordering_forces_paging_for_fids(layer, transport) -> None:
    """The fids endpoint always sorts by fid; an ordered request must not use it."""
    layer.order_by("Baujahr", desc=True).fids()
    assert all(request.path.endswith("/features") for request in transport.requests)


# --- features -------------------------------------------------------------


def test_attributes_are_keyed_the_way_a_filter_names_them(layer) -> None:
    """
    The wire keys by column ("hoehe"); a caller reads by field name ("Höhe").

    Same spelling in both directions, so ``feature["Höhe"]`` and
    ``where('"Höhe" > 10')`` agree without anyone translating.
    """
    feature = next(iter(layer))

    assert "Höhe" in feature
    assert "Straße" in feature.properties
    assert feature["Höhe"] == feature.get("Höhe")
    assert feature.get("gibtesnicht") is None


def test_geometry_arrives_as_geojson(layer) -> None:
    feature = next(iter(layer))
    assert feature.geometry["type"] == "MultiPolygon"
    assert feature.geometry["coordinates"]


def test_a_single_feature_reads_the_same_way(layer) -> None:
    feature = layer.feature(1)
    assert feature.fid == 1
    assert "Höhe" in feature.properties
    assert feature.row_version is not None


def test_repr_shows_what_the_query_would_do(layer) -> None:
    """Printed into an agent's context, so it has to say the restriction."""
    text = repr(layer.where('"Höhe" > 10').bbox(9.9, 53.5, 10.1, 53.6).order_by("Baujahr"))
    assert '"Höhe" > 10' in text
    assert "9.9" in text
    assert "Baujahr" in text


def test_an_unknown_field_keeps_the_servers_message(layer, transport) -> None:
    """
    The server names the fields that exist. That sentence is the whole value
    of the error, so it arrives unchanged.
    """
    transport.handler = lambda request: Response(400, load("error-unknown-field.json"))

    with pytest.raises(hgis.ApiError) as error:
        layer.where("hoehe > 10").count()

    assert str(error.value) == (
        "Unbekanntes Feld: osm_id. Verfügbar: Straße, Höhe, Baujahr."
    )
    assert error.value.status == 400
