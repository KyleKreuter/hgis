"""Finding things by name, and what happens when the name is wrong."""

from __future__ import annotations

import pytest
from conftest import LAYER_ID, PROJECT_ID, FakeTransport, load, stub_server

import hgis
from hgis.transport import Response


def test_projects_are_listed(client) -> None:
    projects = client.projects()
    assert [p.name for p in projects][:2] == ["Flurstücke", "Wandsbek, Zuschnitt-Beispiel"]
    assert all(isinstance(p, hgis.Project) for p in projects)


def test_a_project_is_found_by_name(client) -> None:
    project = client.project("Leitungsnetz Nord")
    assert project.id == PROJECT_ID
    assert project.layer_count == 4


def test_a_name_matches_regardless_of_case(client) -> None:
    """
    The name is shown as it was typed once and read back by someone else.

    Reproducing its capitalisation is a trap the backend's own filter parser
    already refuses to set.
    """
    assert client.project("leitungsnetz nord").id == PROJECT_ID


def test_an_id_is_read_directly(client, transport) -> None:
    """
    A UUID goes straight to the single-project endpoint.

    Recognised by shape rather than by asking: a name that parses as a UUID is
    not a case that occurs, while a round trip per lookup always is.
    """
    client.project(PROJECT_ID)
    assert transport.paths == [f"/api/projects/{PROJECT_ID}"]


def test_an_unknown_project_names_the_ones_that_exist(client) -> None:
    """The rule the backend's filter errors set: say what would have worked."""
    with pytest.raises(hgis.UnknownNameError) as error:
        client.project("Gibtesnicht")

    message = str(error.value)
    assert "Unbekanntes Projekt: Gibtesnicht" in message
    assert "Leitungsnetz Nord" in message
    assert "Hamburg Speicherstadt" in message


def test_an_unknown_project_is_also_a_lookup_error(client) -> None:
    """``except LookupError`` is what a failed lookup by name should answer to."""
    with pytest.raises(LookupError):
        client.project("Gibtesnicht")


def test_layers_of_a_project(project) -> None:
    layers = project.layers()
    assert [item.name for item in layers] == [
        "Gebäude Speicherstadt",
        "Straßen",
        "hydranten",
        "Flurstücke",
    ]


def test_a_layer_is_found_by_name(project) -> None:
    layer = project.layer("Gebäude Speicherstadt")
    assert layer.id == LAYER_ID
    assert layer.geometry_type == "MULTIPOLYGON"
    assert layer.feature_count == 1003


def test_an_unknown_layer_names_the_ones_that_exist(project) -> None:
    with pytest.raises(hgis.UnknownNameError) as error:
        project.layer("Baeume")

    message = str(error.value)
    assert "Unbekannter Layer: Baeume" in message
    assert "Gebäude Speicherstadt" in message
    assert "hydranten" in message


def test_a_server_error_arrives_unchanged() -> None:
    """
    The server's sentence is the error. Nothing is wrapped around it.

    "Unbekanntes Feld: osm_id. Verfügbar: ..." tells a reader what to write
    next; "Anfrage fehlgeschlagen (400)" tells them nothing.
    """
    transport = FakeTransport(lambda request: Response(400, load("error-unknown-field.json")))
    client = hgis.connect("http://stub", transport=transport)

    with pytest.raises(hgis.ApiError) as error:
        client.get("/api/layers/x/values", field="osm_id")

    assert str(error.value) == "Unbekanntes Feld: osm_id. Verfügbar: Straße, Höhe, Baujahr."
    assert error.value.status == 400
    assert error.value.title == "Ungültige Anfrage"


def test_a_404_is_its_own_error() -> None:
    """So a caller can tell "does not exist" from "you asked wrongly"."""
    body = '{"detail":"Layer x existiert nicht","status":404,"title":"Nicht gefunden"}'
    transport = FakeTransport(lambda request: Response(404, body))
    client = hgis.connect("http://stub", transport=transport)

    with pytest.raises(hgis.NotFoundError) as error:
        client.layer("x")
    assert str(error.value) == "Layer x existiert nicht"
    assert isinstance(error.value, hgis.ApiError)


def test_an_error_without_a_problem_document_still_says_something() -> None:
    """
    A gateway answering 502 in HTML has no ``detail`` to pass on.

    Only then does this library write a message of its own -- and it carries
    the status and the path, which is what a reader needs to find the gateway.
    """
    transport = FakeTransport(lambda request: Response(502, "<html>Bad Gateway</html>"))
    client = hgis.connect("http://stub", transport=transport)

    with pytest.raises(hgis.ApiError) as error:
        client.get("/api/projects")
    assert "502" in str(error.value)
    assert "/api/projects" in str(error.value)


def test_every_project_page_is_walked() -> None:
    """
    The browser caps a page at 100, so a server with more projects needs the
    cursor followed -- otherwise projects() quietly returns the first 100.
    """
    pages = [
        Response(200, '{"items":[{"id":"a","name":"A","layerCount":0,"featureCount":0,'
                      '"srid":25832},{"id":"b","name":"B","layerCount":0,"featureCount":0,'
                      '"srid":25832}],"nextCursor":"weiter"}'),
        Response(200, '{"items":[{"id":"c","name":"C","layerCount":0,"featureCount":0,'
                      '"srid":25832}],"nextCursor":null}'),
    ]
    transport = FakeTransport(lambda request: pages[1] if request.param("cursor") else pages[0])
    client = hgis.connect("http://stub", transport=transport)

    assert [p.name for p in client.projects()] == ["A", "B", "C"]
    assert transport.count == 2


def test_connect_sends_nothing() -> None:
    """Connecting is cheap; the first request happens when something is asked."""
    transport = FakeTransport(stub_server)
    hgis.connect("http://stub", transport=transport)
    assert transport.count == 0


def test_the_default_address_is_the_local_one() -> None:
    assert hgis.DEFAULT_BASE_URL == "http://localhost:8080"
