"""to_dataframe(), and what it says when its optional packages are missing."""

from __future__ import annotations

import sys

import pytest

import hgis

pandas = pytest.importorskip("pandas")


def test_columns_are_named_the_way_a_filter_names_them(layer) -> None:
    """
    So ``df["Höhe"]`` and ``where('"Höhe" > 10')`` agree.

    fid comes first because it is what select() and feature() take.
    """
    frame = layer.where('"Höhe" > 10').to_dataframe(geometry=False)
    assert list(frame.columns) == ["fid", "Straße", "Höhe", "Baujahr"]


def test_every_matching_row_arrives(layer) -> None:
    """Paged in, so a result larger than one page is not silently cut."""
    frame = layer.to_dataframe(geometry=False)
    assert len(frame) == 1003
    assert frame["fid"].is_unique


def test_values_keep_their_types(layer) -> None:
    frame = layer.to_dataframe(geometry=False)
    assert frame["Höhe"].dtype.kind == "f"
    assert frame["Baujahr"].dtype.kind in "if"
    assert frame["Straße"].iloc[0] == "Müllerstraße"


def test_the_server_did_the_filtering(layer, transport) -> None:
    """
    The rule the whole library rests on: restrict, then fetch.

    The filter has to be on the wire. A library that fetched everything and
    filtered in pandas would return the same rows here and fall over on a
    layer of a million.
    """
    layer.where('"Höhe" > 10').to_dataframe(geometry=False)
    assert transport.requests[0].param("filter") == '"Höhe" > 10'


def test_geometry_becomes_shapely_when_it_is_installed(layer) -> None:
    shapely = pytest.importorskip("shapely")
    frame = layer.to_dataframe()
    assert "geometry" in frame.columns
    assert isinstance(frame["geometry"].iloc[0], shapely.geometry.base.BaseGeometry)


def test_geometry_stays_geojson_without_shapely(layer, monkeypatch) -> None:
    """
    shapely is optional, and without it the geometry is still there.

    A GeoJSON dictionary is less convenient than a shapely object and it is
    not nothing, which is what dropping the column would leave.
    """
    monkeypatch.setitem(sys.modules, "shapely", None)
    monkeypatch.setitem(sys.modules, "shapely.geometry", None)

    frame = layer.to_dataframe()
    assert frame["geometry"].iloc[0]["type"] == "MultiPolygon"


def test_an_empty_result_still_has_its_columns(layer, transport) -> None:
    """
    So ``df.columns`` can be read after a query that matched nothing.

    A bare DataFrame() would make an empty result look like a layer without
    fields.
    """
    transport.handler = lambda request: hgis.transport.Response(
        200, '{"features":[],"totalCount":0}'
    )
    frame = layer.where("Baujahr > 3000").to_dataframe(geometry=False)

    assert len(frame) == 0
    assert list(frame.columns) == ["fid", "Straße", "Höhe", "Baujahr"]


def test_without_pandas_the_message_says_what_to_install(layer, monkeypatch) -> None:
    """
    Everything else in this library works without pandas, so the failure has
    to say that installing it is the fix -- not "No module named pandas" from
    three frames deep.
    """
    monkeypatch.setitem(sys.modules, "pandas", None)

    with pytest.raises(hgis.MissingDependencyError) as error:
        layer.to_dataframe()

    message = str(error.value)
    assert "pandas ist nicht installiert" in message
    assert "pip install 'hgis[dataframe]'" in message
    assert "pip install pandas" in message


def test_the_missing_package_error_is_also_an_import_error(layer, monkeypatch) -> None:
    """``except ImportError`` around an optional feature is the usual shape."""
    monkeypatch.setitem(sys.modules, "pandas", None)
    with pytest.raises(ImportError):
        layer.to_dataframe()


def test_everything_else_works_without_pandas(layer, monkeypatch) -> None:
    """The claim from the README, checked rather than asserted."""
    monkeypatch.setitem(sys.modules, "pandas", None)

    assert layer.count() == 1003
    assert len(layer.where('"Höhe" > 10').fids()) == 415
    assert layer.describe().name == "Gebäude Speicherstadt"
    assert next(iter(layer)).fid == 1
