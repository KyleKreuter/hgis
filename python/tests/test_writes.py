"""
The write surface itself: what each call sends, what it hands back, and that
none of it is lazy -- see the module docstrings of :mod:`hgis.edits` and
:mod:`hgis.layer`.

test_guard.py is about whether a request is let through at all; this is about
whether, once let through, it is built and read correctly. Every test here
uses its own :class:`FakeTransport`, matching test_client.py and
test_redirect.py rather than the shared fixtures in conftest.py, which are
shaped for the read-only "Gebäude Speicherstadt" scenario.
"""

from __future__ import annotations

import pytest

import hgis
from conftest import LAYER_ID, PROJECT_ID, FakeTransport
from hgis.transport import Response

OTHER_UUID = "019fecc1-48a2-76b7-8732-019e83d5532a"


def _client(handler, **kwargs) -> tuple[hgis.Client, FakeTransport]:
    transport = FakeTransport(handler)
    client = hgis.connect("http://stub", transport=transport, client_id="agent-a", **kwargs)
    return client, transport


def _project(client: hgis.Client) -> hgis.Project:
    """Built directly from a minimal summary, without a round trip to fetch it."""
    return hgis.Project(
        client,
        {
            "id": PROJECT_ID,
            "name": "P",
            "srid": 25832,
            "layerCount": 1,
            "featureCount": 1003,
        },
    )


def _layer(client: hgis.Client, **overrides) -> hgis.Layer:
    data = {
        "id": LAYER_ID,
        "name": "Gebäude Speicherstadt",
        "kind": "VECTOR",
        "geometryType": "MULTIPOLYGON",
        "srid": 25832,
        "featureCount": 1003,
        "visible": True,
        "fields": [
            {
                "id": OTHER_UUID,
                "sourceName": "Höhe",
                "columnName": "hoehe",
                "dataType": "double precision",
            },
        ],
    }
    data.update(overrides)
    return hgis.Layer(client, data)


# --- projects ------------------------------------------------------------


def test_project_update_sends_only_the_given_fields() -> None:
    """
    The gap this closes: without update(), an agent that found where to move
    the map (project.update's whole point) had no way to send it -- the
    guard refused PATCH /api/projects/{id} outright. See test_guard.py for
    that half; this is about what the request looks like once it is let
    through.
    """

    def handle(request: object) -> Response:
        return Response(
            200,
            f'{{"id":"{PROJECT_ID}","name":"Neu","description":null,"srid":25832,'
            '"basemap":"osm","basemapOpacity":1.0,"center":[9.99,53.55],"zoom":16.0,'
            '"extent":null,"layerCount":1,"featureCount":1003,'
            '"lastOpenedAt":null,"createdAt":"2026-01-01T00:00:00Z",'
            '"updatedAt":"2026-01-01T00:00:00Z"}',
        )

    client, transport = _client(handle)
    project = _project(client)

    result = project.update(name="Neu", center=(9.99, 53.55), zoom=16)

    assert transport.requests[-1].method == "PATCH"
    assert transport.requests[-1].path == f"/api/projects/{PROJECT_ID}"
    assert transport.bodies[-1] == {"name": "Neu", "center": [9.99, 53.55], "zoom": 16}
    assert result is project, "update() gibt sich selbst zurück, zum Verketten."
    assert project.name == "Neu"


def test_project_update_with_nothing_sends_an_empty_body() -> None:
    def handle(request: object) -> Response:
        return Response(
            200,
            f'{{"id":"{PROJECT_ID}","name":"P","description":null,"srid":25832,'
            '"basemap":"osm","basemapOpacity":1.0,"center":null,"zoom":null,'
            '"extent":null,"layerCount":1,"featureCount":1003,'
            '"lastOpenedAt":null,"createdAt":"2026-01-01T00:00:00Z",'
            '"updatedAt":"2026-01-01T00:00:00Z"}',
        )

    client, transport = _client(handle)
    _project(client).update()

    assert transport.bodies[-1] == {}


def test_create_project_sends_only_the_given_fields_and_returns_a_project() -> None:
    """
    The gap this closes: without this, an agent had nowhere of its own to
    work -- the guard refused POST /api/projects outright. See test_guard.py
    for that half; this is about the request and the object it builds.
    """

    def handle(request: object) -> Response:
        return Response(
            201,
            f'{{"id":"{PROJECT_ID}","name":"agent-scratch","description":null,'
            '"srid":25832,"basemap":"osm","basemapOpacity":1.0,"center":null,'
            '"zoom":null,"extent":null,"layerCount":0,"featureCount":0,'
            '"lastOpenedAt":null,"createdAt":"2026-01-01T00:00:00Z",'
            '"updatedAt":"2026-01-01T00:00:00Z"}',
        )

    client, transport = _client(handle)

    project = client.create_project("agent-scratch")

    assert transport.requests[-1].method == "POST"
    assert transport.requests[-1].path == "/api/projects"
    assert transport.bodies[-1] == {"name": "agent-scratch"}
    assert isinstance(project, hgis.Project)
    assert project.id == PROJECT_ID
    assert project.name == "agent-scratch"


def test_create_project_with_every_argument_sends_all_of_them() -> None:
    def handle(request: object) -> Response:
        return Response(
            201,
            f'{{"id":"{PROJECT_ID}","name":"agent-scratch","description":"Testlauf",'
            '"srid":25833,"basemap":"satellite","basemapOpacity":1.0,"center":null,'
            '"zoom":null,"extent":null,"layerCount":0,"featureCount":0,'
            '"lastOpenedAt":null,"createdAt":"2026-01-01T00:00:00Z",'
            '"updatedAt":"2026-01-01T00:00:00Z"}',
        )

    client, transport = _client(handle)

    client.create_project(
        "agent-scratch", description="Testlauf", srid=25833, basemap="satellite"
    )

    assert transport.bodies[-1] == {
        "name": "agent-scratch",
        "description": "Testlauf",
        "srid": 25833,
        "basemap": "satellite",
    }


def test_delete_project_sends_a_delete_to_the_project_itself() -> None:
    def handle(request: object) -> Response:
        return Response(204, "")

    client, transport = _client(handle)

    result = client.delete_project(PROJECT_ID)

    assert result is None
    assert transport.requests[-1].method == "DELETE"
    assert transport.requests[-1].path == f"/api/projects/{PROJECT_ID}"


def test_deletion_impact_reads_layer_and_feature_count() -> None:
    def handle(request: object) -> Response:
        return Response(200, '{"layerCount":2,"featureCount":150}')

    client, transport = _client(handle)

    impact = client.deletion_impact(PROJECT_ID)

    assert transport.requests[-1].method == "GET"
    assert transport.requests[-1].path == f"/api/projects/{PROJECT_ID}/deletion-impact"
    assert impact == {"layerCount": 2, "featureCount": 150}


# --- layers ------------------------------------------------------------


def test_create_layer_sends_name_type_and_fields() -> None:
    def handle(request: object) -> Response:
        return Response(
            201,
            (
                f'{{"id":"{LAYER_ID}","name":"Bäume","kind":"VECTOR",'
                '"geometryType":"MULTIPOINT","srid":25832,"featureCount":0,"visible":true}'
            ),
        )

    client, transport = _client(handle)
    layer = _project(client).create_layer(
        "Bäume", "MULTIPOINT", fields={"Gattung": "TEXT", "Pflanzjahr": "INTEGER"}
    )

    assert transport.requests[-1].method == "POST"
    assert transport.requests[-1].path == f"/api/projects/{PROJECT_ID}/layers"
    assert transport.bodies[-1] == {
        "name": "Bäume",
        "geometryType": "MULTIPOINT",
        "fields": [{"name": "Gattung", "type": "TEXT"}, {"name": "Pflanzjahr", "type": "INTEGER"}],
    }
    assert isinstance(layer, hgis.Layer)
    assert layer.name == "Bäume"


def test_create_layer_without_fields_omits_the_key() -> None:
    def handle(request: object) -> Response:
        return Response(
            201,
            f'{{"id":"{LAYER_ID}","name":"Leer","kind":"VECTOR",'
            '"geometryType":"GEOMETRY","srid":25832,"featureCount":0,"visible":true}',
        )

    client, transport = _client(handle)
    _project(client).create_layer("Leer", "GEOMETRY")

    assert "fields" not in transport.bodies[-1]


def test_layer_update_sends_only_the_given_fields() -> None:
    def handle(request: object) -> Response:
        return Response(
            200,
            f'{{"id":"{LAYER_ID}","name":"Neu","kind":"VECTOR",'
            '"geometryType":"MULTIPOLYGON","srid":25832,"featureCount":1003,'
            '"visible":false,"fields":[]}',
        )

    client, transport = _client(handle)
    layer = _layer(client)

    result = layer.update(name="Neu", visible=False)

    assert transport.requests[-1].method == "PATCH"
    assert transport.requests[-1].path == f"/api/layers/{LAYER_ID}"
    assert transport.bodies[-1] == {"name": "Neu", "visible": False}
    assert result is layer, "update() gibt sich selbst zurück, zum Verketten."
    assert layer.name == "Neu"
    assert layer.visible is False


def test_layer_update_with_nothing_sends_an_empty_body() -> None:
    def handle(request: object) -> Response:
        return Response(
            200,
            f'{{"id":"{LAYER_ID}","name":"Gebäude Speicherstadt","kind":"VECTOR",'
            '"geometryType":"MULTIPOLYGON","srid":25832,"featureCount":1003,'
            '"visible":true,"fields":[]}',
        )

    client, transport = _client(handle)
    _layer(client).update()

    assert transport.bodies[-1] == {}


def test_layer_delete_reports_none_if_the_answer_ever_has_no_body() -> None:
    """
    Not today's backend (see the test below, which is) -- a defensive case:
    204 with no body. There is nothing to build a TrashEntry from then, and
    delete() must not pretend otherwise.
    """

    def handle(request: object) -> Response:
        return Response(204, "")

    client, transport = _client(handle)
    layer = _layer(client)

    result = layer.delete()

    assert transport.requests[-1].method == "DELETE"
    assert transport.requests[-1].path == f"/api/layers/{LAYER_ID}"
    assert result is None


def test_layer_delete_reports_the_trash_entry_when_the_server_provides_one() -> None:
    """
    Today's backend: LayerController.delete answers 200 with a
    LayerDtos.TrashEntry body -- how many objects moved to the trash, when,
    by whom. This is what delete() reports.
    """

    def handle(request: object) -> Response:
        return Response(
            200,
            f'{{"id":"{LAYER_ID}","name":"Gebäude Speicherstadt",'
            '"deletedAt":"2026-08-16T10:00:00Z","featureCount":1003,'
            '"deletedBy":"agent-a"}',
        )

    client, transport = _client(handle)
    layer = _layer(client)

    result = layer.delete()

    assert result == hgis.TrashEntry(
        id=LAYER_ID,
        name="Gebäude Speicherstadt",
        deleted_at="2026-08-16T10:00:00Z",
        feature_count=1003,
        deleted_by="agent-a",
    )


def test_layer_restore_re_reads_it() -> None:
    def handle(request: object) -> Response:
        if request.path.endswith("/restore"):
            return Response(204, "")
        return Response(
            200,
            f'{{"id":"{LAYER_ID}","name":"Wiederhergestellt","kind":"VECTOR",'
            '"geometryType":"MULTIPOLYGON","srid":25832,"featureCount":1003,'
            '"visible":true,"fields":[]}',
        )

    client, transport = _client(handle)
    layer = _layer(client)

    result = layer.restore()

    assert [request.method for request in transport.requests] == ["POST", "GET"]
    assert transport.requests[0].path == f"/api/layers/{LAYER_ID}/restore"
    assert transport.requests[1].path == f"/api/layers/{LAYER_ID}"
    assert result is layer
    assert layer.name == "Wiederhergestellt"


def test_layer_purge_calls_the_right_endpoint_and_does_not_reread() -> None:
    """A defensive case, not today's backend -- see delete()'s equivalent pair of tests."""

    def handle(request: object) -> Response:
        return Response(204, "")

    client, transport = _client(handle)
    layer = _layer(client)

    result = layer.purge()

    assert len(transport.requests) == 1
    assert transport.requests[-1].method == "DELETE"
    assert transport.requests[-1].path == f"/api/layers/{LAYER_ID}/purge"
    assert result is None


def test_layer_purge_reports_the_trash_entry_when_the_server_provides_one() -> None:
    def handle(request: object) -> Response:
        return Response(
            200,
            f'{{"id":"{LAYER_ID}","name":"Gebäude Speicherstadt",'
            '"deletedAt":"2026-08-16T10:00:00Z","featureCount":1003,'
            '"deletedBy":"agent-a"}',
        )

    client, transport = _client(handle)
    layer = _layer(client)

    result = layer.purge()

    assert result == hgis.TrashEntry(
        id=LAYER_ID,
        name="Gebäude Speicherstadt",
        deleted_at="2026-08-16T10:00:00Z",
        feature_count=1003,
        deleted_by="agent-a",
    )


# --- fields --------------------------------------------------------------


def test_create_field_widens_the_cached_fields() -> None:
    new_id = "019fecc2-0000-7000-8000-000000000001"

    def handle(request: object) -> Response:
        return Response(
            201,
            f'{{"id":"{new_id}","sourceName":"Baujahr",'
            '"columnName":"baujahr","dataType":"integer"}',
        )

    client, transport = _client(handle)
    layer = _layer(client)
    before = layer.fields()

    created = layer.create_field("Baujahr", "INTEGER")

    assert transport.requests[-1].method == "POST"
    assert transport.requests[-1].path == f"/api/layers/{LAYER_ID}/fields"
    assert transport.bodies[-1] == {"name": "Baujahr", "type": "INTEGER"}
    assert created.name == "Baujahr"
    assert created.column == "baujahr"
    assert layer.fields() == [*before, created], "Das neue Feld fehlt im Zwischenspeicher."


def test_delete_field_narrows_the_cached_fields() -> None:
    def handle(request: object) -> Response:
        return Response(204, "")

    client, transport = _client(handle)
    layer = _layer(client)
    field = layer.fields()[0]

    layer.delete_field(field)

    assert transport.requests[-1].method == "DELETE"
    assert transport.requests[-1].path == f"/api/layers/{LAYER_ID}/fields/{field.id}"
    assert layer.fields() == []


def test_delete_field_resolves_a_name_first() -> None:
    def handle(request: object) -> Response:
        return Response(204, "")

    client, transport = _client(handle)
    layer = _layer(client)

    layer.delete_field("Höhe")

    assert transport.requests[-1].path == f"/api/layers/{LAYER_ID}/fields/{OTHER_UUID}"


# --- objects ---------------------------------------------------------------


def test_insert_sends_a_single_placeholder_create_and_returns_its_fid() -> None:
    def handle(request: object) -> Response:
        return Response(
            200,
            '{"createdFids":{"-1":42},"updated":0,"deleted":0,"dataVersion":5,"featureCount":1004}',
        )

    client, transport = _client(handle)
    layer = _layer(client)

    fid = layer.insert({"type": "Point", "coordinates": [9.9, 53.5]}, {"Name": "X"})

    assert fid == 42
    assert transport.requests[-1].path == f"/api/layers/{LAYER_ID}/edits"
    assert transport.bodies[-1] == {
        "creates": [
            {
                "clientId": -1,
                "geometry": {"type": "Point", "coordinates": [9.9, 53.5]},
                "properties": {"Name": "X"},
            },
        ]
    }
    assert layer.feature_count == 1004, "feature_count zieht nicht mit dem Ergebnis mit."


def test_insert_without_properties_omits_the_key() -> None:
    def handle(request: object) -> Response:
        return Response(
            200, '{"createdFids":{"-1":1},"updated":0,"deleted":0,"dataVersion":1,"featureCount":1}'
        )

    client, transport = _client(handle)
    layer = _layer(client)

    layer.insert({"type": "Point", "coordinates": [0, 0]})

    assert "properties" not in transport.bodies[-1]["creates"][0]


def test_insert_many_maps_fids_back_by_placeholder_not_by_order() -> None:
    """
    The server's ``createdFids`` is a map, and this test deliberately answers
    with its keys in an order different from how the placeholders were sent --
    a wrong implementation that zipped the response in arrival order rather
    than looking each placeholder up would silently mix up which fid belongs
    to which of the three points.
    """

    def handle(request: object) -> Response:
        return Response(
            200,
            '{"createdFids":{"-3":103,"-1":101,"-2":102},'
            '"updated":0,"deleted":0,"dataVersion":1,"featureCount":3}',
        )

    client, transport = _client(handle)
    layer = _layer(client)

    fids = layer.insert_many(
        [
            hgis.NewFeature({"type": "Point", "coordinates": [0, 0]}),
            hgis.NewFeature({"type": "Point", "coordinates": [1, 1]}),
            hgis.NewFeature({"type": "Point", "coordinates": [2, 2]}),
        ]
    )

    assert fids == [101, 102, 103]
    sent = transport.bodies[-1]["creates"]
    assert [entry["clientId"] for entry in sent] == [-1, -2, -3]


def test_update_feature_sends_row_version_and_only_the_given_parts() -> None:
    def handle(request: object) -> Response:
        return Response(
            200, '{"createdFids":{},"updated":1,"deleted":0,"dataVersion":9,"featureCount":1003}'
        )

    client, transport = _client(handle)
    layer = _layer(client)

    result = layer.update_feature(42, "8241", properties={"Höhe": 12.5, "Notiz": None})

    assert transport.bodies[-1] == {
        "updates": [
            {"fid": 42, "rowVersion": "8241", "properties": {"Höhe": 12.5, "Notiz": None}},
        ]
    }
    assert result.updated == 1
    assert result.data_version == 9


def test_delete_features_names_every_fid_explicitly() -> None:
    def handle(request: object) -> Response:
        return Response(
            200, '{"createdFids":{},"updated":0,"deleted":3,"dataVersion":2,"featureCount":1000}'
        )

    client, transport = _client(handle)
    layer = _layer(client)

    result = layer.delete_features([10, 11, 12])

    assert transport.bodies[-1] == {"deletes": [10, 11, 12]}
    assert result.deleted == 3
    assert layer.feature_count == 1000


def test_delete_features_with_nothing_sends_no_request() -> None:
    """
    There is no "delete everything" shortcut: an empty list is a no-op, not
    the whole layer -- see the docstring of Layer.delete_features.
    """

    def handle(request: object) -> Response:  # pragma: no cover - must not run
        raise AssertionError("Eine leere Löschliste hätte nichts senden dürfen.")

    client, transport = _client(handle)
    layer = _layer(client)

    result = layer.delete_features([])

    assert transport.count == 0
    assert result.deleted == 0


@pytest.mark.parametrize("fids", ["123", b"123", bytearray(b"123"), memoryview(b"123")])
def test_delete_features_rejects_a_scalar_iterable_instead_of_a_list(fids) -> None:
    """
    The reported break, as a test, for all four shapes that are iterable but
    are really one value: without this check, ``delete_features("123")`` --
    almost certainly meant as the one fid 123 -- would walk it character by
    character and delete objects 1, 2 and 3 instead; the three bytes-like
    shapes would walk it byte by byte and delete 49, 50 and 51. No request
    may leave for any of the four.
    """

    def handle(request: object) -> Response:  # pragma: no cover - must not run
        raise AssertionError("Das haette nichts senden duerfen.")

    client, transport = _client(handle)
    layer = _layer(client)

    with pytest.raises(hgis.InvalidArgumentError):
        layer.delete_features(fids)

    assert transport.count == 0


def test_delete_features_rejects_a_string_that_is_not_a_str() -> None:
    """
    The same break, once more, for a value that is not one of the four
    enumerated types at all: ``collections.UserString`` wraps a real ``str``
    instead of subclassing one, so it slips past ``isinstance(value, str)``
    -- but iterating it still walks it one character at a time. Caught by
    the effect check in :func:`hgis.edits._decomposes_into_single_characters`,
    not by widening the type list, which would only ever cover the next
    named type and never the one after that.
    """
    import collections

    def handle(request: object) -> Response:  # pragma: no cover - must not run
        raise AssertionError("Das haette nichts senden duerfen.")

    client, transport = _client(handle)
    layer = _layer(client)

    with pytest.raises(hgis.InvalidArgumentError):
        layer.delete_features(collections.UserString("123"))

    assert transport.count == 0


def test_delete_features_rejects_an_iterable_with_no_getitem_at_all() -> None:
    """
    A third route to the same break: a custom class defining only
    ``__iter__`` -- the more modern, more common way to write an iterable,
    and one with no ``__getitem__`` to compare an index against a slice
    with. ``UserString`` has one; this does not.
    """

    class CharsOnlyIterable:
        def __iter__(self):
            return iter("123")

    def handle(request: object) -> Response:  # pragma: no cover - must not run
        raise AssertionError("Das haette nichts senden duerfen.")

    client, transport = _client(handle)
    layer = _layer(client)

    with pytest.raises(hgis.InvalidArgumentError):
        layer.delete_features(CharsOnlyIterable())

    assert transport.count == 0


def test_delete_features_leaves_a_broken_getitem_alone_rather_than_crashing() -> None:
    """
    The check has to inspect a value it does not control to tell a scalar
    from a collection -- and a foreign ``__getitem__`` can raise anything,
    not only the ``TypeError``/``IndexError``/``KeyError`` a well-behaved one
    would. This must not turn into an exception from deep inside the check
    itself; an inconclusive value is treated the same as one confirmed not
    to be this bug, and the caller sees whatever ``int(fid)`` -- the actual
    place a fid is used -- says about it instead.
    """

    class BrokenGetitem:
        def __getitem__(self, item):
            raise ValueError("weder TypeError noch IndexError noch KeyError")

        def __iter__(self):
            return iter([7])

    def handle(request: object) -> Response:
        return Response(
            200, '{"createdFids":{},"updated":0,"deleted":1,"dataVersion":1,"featureCount":1}'
        )

    client, transport = _client(handle)
    layer = _layer(client)

    layer.delete_features(BrokenGetitem())

    assert transport.count == 1
    assert transport.bodies[-1]["deletes"] == [7]


@pytest.mark.parametrize("fids", [{"5"}, frozenset({"5"})])
def test_delete_features_accepts_a_single_character_string_in_a_real_collection(fids) -> None:
    """
    The reported false positive: neither ``set`` nor ``frozenset`` has
    ``__getitem__``, so a one-element collection like ``{"5"}`` -- one valid
    fid, wrapped the ordinary way -- reached the same fallback a decomposing
    ``__iter__``-only value does, and with only its first element checked,
    the two looked identical: both hand back one single-character string.
    Must not be rejected: there is only one element here, and the dangerous
    case -- several wrong values instead of the one right one -- needs at
    least two.
    """

    def handle(request: object) -> Response:
        return Response(
            200, '{"createdFids":{},"updated":0,"deleted":1,"dataVersion":1,"featureCount":1}'
        )

    client, transport = _client(handle)
    layer = _layer(client)

    layer.delete_features(fids)

    assert transport.count == 1


@pytest.mark.parametrize("fids", [{"5": "x"}.keys(), {"x": "5"}.values()])
def test_delete_features_accepts_a_dict_view_with_one_single_character_entry(fids) -> None:
    """
    The same false positive, for the two other no-``__getitem__`` types the
    review found: ``dict.keys()`` and ``dict.values()``. A ``dict`` itself
    was already safe (a lookup for key ``0`` raises ``KeyError``, caught
    directly); its views are a different type, without the ``dict``'s own
    ``__getitem__``, and reach the same fallback ``{"5"}`` does.
    """

    def handle(request: object) -> Response:
        return Response(
            200, '{"createdFids":{},"updated":0,"deleted":1,"dataVersion":1,"featureCount":1}'
        )

    client, transport = _client(handle)
    layer = _layer(client)

    layer.delete_features(fids)

    assert transport.count == 1


@pytest.mark.parametrize("fids", [[123], (123,), {123}, frozenset({123}), range(120, 124)])
def test_delete_features_accepts_real_collections(fids) -> None:
    """The check above must not be so tight that an ordinary collection fails."""

    def handle(request: object) -> Response:
        return Response(
            200, '{"createdFids":{},"updated":0,"deleted":1,"dataVersion":1,"featureCount":1}'
        )

    client, transport = _client(handle)
    layer = _layer(client)

    layer.delete_features(fids)

    assert transport.count == 1


def test_delete_features_accepts_array_array() -> None:
    """
    A real collection that, like the bytes family, iterates into plain
    integers rather than into its own elements -- must stay accepted, not
    be swept up by widening the check for the bytes family.
    """
    import array

    def handle(request: object) -> Response:
        return Response(
            200, '{"createdFids":{},"updated":0,"deleted":1,"dataVersion":1,"featureCount":1}'
        )

    client, transport = _client(handle)
    layer = _layer(client)

    layer.delete_features(array.array("i", [123]))

    assert transport.count == 1


def test_delete_features_accepts_a_single_element_numpy_array() -> None:
    """
    The one shape the effect check in
    :func:`hgis.edits._decomposes_into_single_characters` has to be careful
    with: NumPy's ``==`` compares element-wise, so
    ``arr[0] == arr[0:1]`` for a one-element array returns an array holding
    one ``True``, not the ``bool`` a real match returns. Trusting that as
    truthy would reject exactly the single-fid case this check must accept.
    """
    numpy = pytest.importorskip("numpy")

    def handle(request: object) -> Response:
        return Response(
            200, '{"createdFids":{},"updated":0,"deleted":1,"dataVersion":1,"featureCount":1}'
        )

    client, transport = _client(handle)
    layer = _layer(client)

    layer.delete_features(numpy.array([123]))

    assert transport.count == 1


def test_delete_features_rejects_a_string_kept_in_a_generator_untouched() -> None:
    """
    The effect check indexes rather than calling ``next(iter(value))`` to
    peek at a value -- so a generator, which the check must leave alone
    entirely (it has no ``__getitem__``), is not silently drained of its
    first item by the very check that is supposed to be read-only.
    """

    def one_and_two():
        yield 1
        yield 2

    generator = one_and_two()

    def handle(request: object) -> Response:
        return Response(
            200, '{"createdFids":{},"updated":0,"deleted":2,"dataVersion":1,"featureCount":1}'
        )

    client, transport = _client(handle)
    layer = _layer(client)

    layer.delete_features(generator)

    assert transport.count == 1
    assert transport.bodies[-1]["deletes"] == [1, 2]


def test_edit_rejects_a_string_for_deletes_too() -> None:
    """
    The same guard one level down: delete_features() converts its argument
    with list(...) before edit() ever sees it, which would already turn a
    string into single-character entries -- so edit(deletes=...) needs its
    own check, not a borrowed one.
    """

    def handle(request: object) -> Response:  # pragma: no cover - must not run
        raise AssertionError("Eine Zeichenkette haette nichts senden duerfen.")

    client, transport = _client(handle)
    layer = _layer(client)

    with pytest.raises(hgis.InvalidArgumentError):
        layer.edit(deletes="123")

    assert transport.count == 0


def test_edit_rejects_a_string_for_creates_and_updates_too() -> None:
    def handle(request: object) -> Response:  # pragma: no cover - must not run
        raise AssertionError("Eine Zeichenkette haette nichts senden duerfen.")

    client, transport = _client(handle)
    layer = _layer(client)

    with pytest.raises(hgis.InvalidArgumentError):
        layer.edit(creates="abc")
    with pytest.raises(hgis.InvalidArgumentError):
        layer.edit(updates="abc")

    assert transport.count == 0


def test_repair_invalid_only_travels_when_set() -> None:
    def handle(request: object) -> Response:
        return Response(
            200, '{"createdFids":{},"updated":1,"deleted":0,"dataVersion":1,"featureCount":1}'
        )

    client, transport = _client(handle)
    layer = _layer(client)

    layer.edit(updates=[hgis.FeatureUpdate(1, "1", properties={"a": 1})], repair_invalid=True)

    assert transport.bodies[-1]["repairInvalid"] is True


def test_a_row_version_conflict_carries_the_current_row() -> None:
    def handle(request: object) -> Response:
        return Response(
            409,
            (
                '{"detail":"Die Zeile wurde inzwischen geändert.","status":409,'
                '"title":"Konflikt","current":{"fid":42,"properties":{"Höhe":9.0},'
                '"geometry":null,"rowVersion":"999"}}'
            ),
        )

    client, transport = _client(handle)
    layer = _layer(client)

    with pytest.raises(hgis.ConflictError) as error:
        layer.update_feature(42, "8241", properties={"Höhe": 12.5})

    assert error.value.status == 409
    assert isinstance(error.value, hgis.ApiError)
    assert error.value.current == {
        "fid": 42,
        "properties": {"Höhe": 9.0},
        "geometry": None,
        "rowVersion": "999",
    }


# --- the client name travels on every one of these --------------------------


@pytest.mark.parametrize(
    "act",
    [
        lambda layer, project: project.create_layer("N", "MULTIPOINT"),
        lambda layer, project: layer.update(name="N"),
        lambda layer, project: layer.delete(),
        lambda layer, project: layer.restore(),
        lambda layer, project: layer.purge(),
        lambda layer, project: layer.insert({"type": "Point", "coordinates": [0, 0]}),
        lambda layer, project: layer.delete_features([1]),
        lambda layer, project: layer.create_field("Neu", "TEXT"),
        lambda layer, project: layer.delete_field(layer.fields()[0]),
    ],
    ids=[
        "create_layer",
        "update",
        "delete",
        "restore",
        "purge",
        "insert",
        "delete_features",
        "create_field",
        "delete_field",
    ],
)
def test_every_write_carries_the_client_name(act) -> None:
    def handle(request: object) -> Response:
        path = request.path
        if path.endswith("/edits"):
            return Response(
                200,
                '{"createdFids":{"-1":1},"updated":0,"deleted":1,"dataVersion":1,"featureCount":1}',
            )
        if path.endswith("/layers") and request.method == "POST":
            return Response(
                201,
                f'{{"id":"{LAYER_ID}","name":"N","kind":"VECTOR",'
                '"geometryType":"MULTIPOINT","srid":25832,'
                '"featureCount":0,"visible":true}',
            )
        if path.endswith("/fields") and request.method == "POST":
            return Response(
                201,
                f'{{"id":"{OTHER_UUID}","sourceName":"Neu","columnName":"neu","dataType":"text"}}',
            )
        if request.method == "PATCH":
            return Response(
                200,
                f'{{"id":"{LAYER_ID}","name":"N","kind":"VECTOR",'
                '"geometryType":"MULTIPOLYGON","srid":25832,'
                '"featureCount":1003,"visible":true,"fields":[]}',
            )
        if path.endswith("/restore"):
            return Response(204, "")
        if request.method == "GET":
            return Response(
                200,
                f'{{"id":"{LAYER_ID}","name":"N","kind":"VECTOR",'
                '"geometryType":"MULTIPOLYGON","srid":25832,'
                '"featureCount":1003,"visible":true,"fields":[]}',
            )
        return Response(204, "")

    client, transport = _client(handle)
    layer = _layer(client)
    project = _project(client)

    act(layer, project)

    writes = [request for request in transport.requests if request.method != "GET"]
    assert writes, "Der Testfall hat keinen Schreibvorgang ausgelöst."
    for request in writes:
        assert request.headers.get(hgis.client.CLIENT_HEADER) == "agent-a"
