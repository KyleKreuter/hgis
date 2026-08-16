"""describe(), fields, and the selection."""

from __future__ import annotations

import pytest
from conftest import LAYER_ID, OTHER_LAYER_ID, FakeTransport, load, stub_server

import hgis
from hgis.transport import Response

# --- fields ---------------------------------------------------------------


def test_fields_carry_both_spellings(layer) -> None:
    """
    A filter accepts either, and a raw response uses the column.

    "Straße" is what a person reads; "strasse" is what the wire says. Keeping
    both means neither side has to translate.
    """
    fields = layer.fields()
    assert [f.name for f in fields] == ["Straße", "Höhe", "Baujahr"]
    assert [f.column for f in fields] == ["strasse", "hoehe", "baujahr"]
    assert [f.type for f in fields] == ["text", "double precision", "integer"]


def test_numeric_fields_are_recognised(layer) -> None:
    """Which decides whether describe() asks for a range or for frequent values."""
    assert not layer.field("Straße").is_numeric
    assert layer.field("Höhe").is_numeric
    assert layer.field("Baujahr").is_numeric


def test_a_field_is_found_by_either_name(layer) -> None:
    assert layer.field("Höhe").column == "hoehe"
    assert layer.field("hoehe").name == "Höhe"
    assert layer.field("höhe").name == "Höhe"


def test_an_unknown_field_names_the_ones_that_exist(layer) -> None:
    with pytest.raises(hgis.UnknownNameError) as error:
        layer.field("hoehe_ueber_nn")
    assert "Verfügbar: Straße, Höhe, Baujahr" in str(error.value)


def test_fields_are_read_once(layer, transport) -> None:
    """
    A layer's shape does not change while a script runs.

    Re-reading it per page would put one extra request between every thousand
    rows, for an answer that cannot have changed.
    """
    layer.fields()
    layer.fields()
    assert transport.count == 0  # already carried by the layer read


# --- describe -------------------------------------------------------------


def test_describe_answers_everything_in_one_call(layer) -> None:
    """
    An agent that has to ask three times makes three times the mistakes.

    Name, geometry, CRS, count, per-field emptiness and range, sample rows --
    assembled from several endpoints, handed over as one object.
    """
    description = layer.describe()

    assert description.name == "Gebäude Speicherstadt"
    assert description.geometry_type == "MULTIPOLYGON"
    assert description.srid == 25833
    assert description.feature_count == 1003
    assert len(description.fields) == 3
    assert len(description.sample) == 5  # the default sample
    assert description.sample[0]["Straße"] == "Müllerstraße"


def test_describe_reads_a_numeric_range(layer) -> None:
    """From /classify, which answers min, max and the empty count at once."""
    hoehe = next(f for f in layer.describe().fields if f.name == "Höhe")
    assert hoehe.minimum == 3.5
    assert hoehe.maximum == 14.5
    assert hoehe.null_count == 3


def test_describe_reads_frequent_values_and_the_empty_count(layer) -> None:
    """
    /values reports null as a value of its own, so the count comes for free.

    Nulls are excluded from the frequent values -- "no value" is not one of
    the values a field takes.
    """
    strasse = next(f for f in layer.describe().fields if f.name == "Straße")
    assert strasse.null_count == 2
    assert ("Bäckerweg", 250) in strasse.top_values
    assert all(value is not None for value, _ in strasse.top_values)
    assert strasse.truncated


def test_a_truncated_value_list_makes_describe_ask_again(layer, transport) -> None:
    """
    When null did not make the cut, the empty count is asked for outright.

    /values returns the most frequent values and says it was truncated. Nulls
    are rare in a well-filled column, so they fall off the end -- and their
    absence from a truncated list says nothing about how many there are.
    Reporting zero there would be a number that looks measured and is guessed.
    """
    description = layer.describe(top=1)
    strasse = next(f for f in description.fields if f.name == "Straße")

    assert strasse.null_count == 2  # from the extra request, not from the list
    asked = [
        request
        for request in transport.requests
        if request.param("filter") and "IS NULL" in request.param("filter")
    ]
    assert len(asked) == 1
    assert asked[0].param("filter") == '"Straße" IS NULL'
    assert asked[0].param("size") == "1"


def test_a_complete_value_list_needs_no_second_request(layer, transport) -> None:
    """
    Not truncated means every distinct value was seen.

    If null is not among them there are none, and asking again would spend a
    request on an answer already in hand.
    """
    transport.handler = lambda request: (
        Response(200, '{"field":"strasse","values":[{"value":"A","count":3}],"truncated":false}')
        if request.path.endswith("/values")
        else stub_server(request)
    )
    strasse = next(f for f in layer.describe().fields if f.name == "Straße")

    assert strasse.null_count == 0
    assert not any(
        request.param("filter") and "IS NULL" in request.param("filter")
        for request in transport.requests
    )


def test_the_empty_share_is_relative_to_the_layer(layer) -> None:
    description = layer.describe()
    strasse = next(f for f in description.fields if f.name == "Straße")
    assert strasse.null_fraction(description.feature_count) == pytest.approx(2 / 1003)


def test_describe_prints_as_text(layer) -> None:
    """
    The point of the class: this is how the answer reaches an agent's context.

    ``print(...)`` and ``repr(...)`` have to produce the same readable thing,
    because which one an agent triggers depends on where it is written.
    """
    description = layer.describe()
    text = str(description)

    assert text == repr(description)
    assert "Gebäude Speicherstadt" in text
    assert "MULTIPOLYGON" in text
    assert "EPSG:25833" in text
    assert "1.003" in text  # German thousands separator
    assert "Höhe (double precision)" in text
    assert "von 3.5 bis 14.5" in text
    assert "leer 0.2%" in text
    assert "häufig:" in text
    assert "Beispielzeilen" in text


def test_describe_without_statistics_asks_far_less(layer, transport) -> None:
    """
    One request per field is fine for three and wrong for two hundred.

    The escape hatch keeps names and types, which is what a first look needs.
    """
    layer.describe(stats=False)
    cheap = transport.count
    transport.requests.clear()

    layer.describe()
    assert transport.count > cheap
    assert cheap == 1  # one page of features; the layer was already read


def test_one_unreadable_field_does_not_lose_the_others() -> None:
    """
    A column the server will not summarise keeps its name and type.

    Twenty-three good fields must not be lost to one odd one, and the reason
    is kept rather than swallowed.
    """

    def refuse_statistics(request):
        if request.path.endswith("/classify") or request.path.endswith("/values"):
            return Response(400, load("error-unknown-field.json"))
        return stub_server(request)

    transport = FakeTransport(refuse_statistics)
    client = hgis.connect("http://stub", transport=transport)
    description = client.layer(LAYER_ID).describe()

    assert [f.name for f in description.fields] == ["Straße", "Höhe", "Baujahr"]
    assert all(f.note for f in description.fields)
    assert "Unbekanntes Feld" in description.fields[0].note
    assert "ohne Statistik" in str(description)


# --- the layer itself -----------------------------------------------------


def test_layer_repr_says_what_it_is(layer) -> None:
    text = repr(layer)
    assert "Gebäude Speicherstadt" in text
    assert "MULTIPOLYGON" in text
    assert "1003" in text


def test_the_extent_is_a_box(layer) -> None:
    extent = layer.extent
    assert len(extent) == 4
    assert extent[0] < extent[2] and extent[1] < extent[3]


def test_values_reads_a_distribution(layer) -> None:
    values = layer.values("Straße", limit=5)
    assert ("Bäckerweg", 250) in values
    assert (None, 2) in values  # null is a value like any other here


# --- selection ------------------------------------------------------------


def test_the_selection_is_read_per_layer() -> None:
    """
    The server keeps one selection per layer, and without an argument this
    reads the active one -- the layer the user is working in.
    """

    def with_selection(request):
        if request.path.endswith("/view-state"):
            return Response(200, load("view-state-with-selection.json"))
        return stub_server(request)

    transport = FakeTransport(with_selection)
    client = hgis.connect("http://stub", transport=transport)
    selection = client.project("019fec3a-ef0c-775c-a14f-7535e8a676eb").selection()

    assert list(selection) == [8, 9, 10]
    assert len(selection) == 3
    assert 9 in selection
    assert selection.layer.id == LAYER_ID


def test_selecting_writes_the_whole_state_back() -> None:
    """
    The endpoint replaces the view state wholesale, so everything else has to
    be read and written back: the other layer's selection, the sort, the saved
    query. Sending only the new selection would erase them.
    """

    def with_selection(request):
        if request.path.endswith("/view-state") and request.method == "GET":
            return Response(200, load("view-state-with-selection.json"))
        return stub_server(request)

    transport = FakeTransport(with_selection)
    client = hgis.connect("http://stub", transport=transport)
    project = client.project("019fec3a-ef0c-775c-a14f-7535e8a676eb")

    project.select([1, 2, 3])

    written = transport.bodies[-1]
    assert transport.requests[-1].method == "PUT"  # nothing follows the write
    assert written["layers"][LAYER_ID]["selection"] == [1, 2, 3]
    # untouched:
    assert written["layers"][LAYER_ID]["sort"] == {"field": "hoehe", "desc": True}
    assert written["layers"][LAYER_ID]["query"]["text"] == '"Höhe" > 10'
    assert written["layers"][OTHER_LAYER_ID]["selection"] == [42]


def test_selecting_makes_the_layer_active() -> None:
    """
    A selection in a layer the user is not looking at changes nothing they can
    see, which is the opposite of what selecting is for.
    """

    def with_selection(request):
        if request.path.endswith("/view-state") and request.method == "GET":
            return Response(200, load("view-state-with-selection.json"))
        return stub_server(request)

    transport = FakeTransport(with_selection)
    client = hgis.connect("http://stub", transport=transport)
    project = client.project("019fec3a-ef0c-775c-a14f-7535e8a676eb")

    project.select([7], layer=OTHER_LAYER_ID)

    written = transport.bodies[-1]
    assert written["activeLayerId"] == OTHER_LAYER_ID
    assert written["layers"][OTHER_LAYER_ID]["selection"] == [7]


def test_selecting_without_a_layer_or_an_active_one_says_so() -> None:
    """
    A project that was never opened has no active layer.

    Picking one anyway would put the selection somewhere the caller did not
    ask for, so this says what is missing instead.
    """

    def never_opened(request):
        if request.path.endswith("/view-state"):
            return Response(200, '{"version":1,"activeLayerId":null,"layers":{}}')
        return stub_server(request)

    transport = FakeTransport(never_opened)
    client = hgis.connect("http://stub", transport=transport)
    project = client.project("019fec3a-ef0c-775c-a14f-7535e8a676eb")

    with pytest.raises(hgis.UnknownNameError) as error:
        project.select([1])
    assert "Nennen Sie den Layer" in str(error.value)

    # And reading a selection there is empty rather than an error: nothing is
    # selected, which is a fact, not a failure.
    assert list(project.selection()) == []


def test_the_view_reports_where_the_map_stands(project) -> None:
    view = project.view()
    assert view.zoom == pytest.approx(15.108141516564979)
    assert view.center[0] == pytest.approx(10.006136398575336)
    assert view.basemap == "osm"
    assert "Zoom=15.11" in repr(view)
