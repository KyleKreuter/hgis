"""The two floors, and the proof that nothing walks past them."""

from __future__ import annotations

import ast
import sys
from pathlib import Path

import pytest

import hgis
from hgis.transport import (
    HttpxTransport,
    PyodideTransport,
    Response,
    build_url,
    default_transport,
    in_pyodide,
)

SOURCE = Path(hgis.__file__).parent

#: Reaching the network from anywhere but transport.py would make the second
#: floor a lie: the Pyodide build would import a module that cannot work there,
#: and the failure would arrive as an ImportError in the browser rather than as
#: a failing test here. ``urllib.parse`` is absent on purpose -- it is string
#: work, it runs in Pyodide, and transport.py uses it to encode a query.
FORBIDDEN = {
    "httpx",
    "requests",
    "socket",
    "aiohttp",
    "urllib.request",
    "urllib.error",
    "http.client",
    "js",
    "pyodide",
    "pyodide.http",
}


def _imported_names(path: Path) -> set[str]:
    """Every module name imported anywhere in one file, top level or not."""
    names: set[str] = set()
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            names.update(alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module and node.level == 0:
            names.add(node.module)
    return names


@pytest.mark.parametrize(
    "path",
    sorted(p for p in SOURCE.glob("*.py") if p.name != "transport.py"),
    ids=lambda path: path.name,
)
def test_only_transport_speaks_http(path: Path) -> None:
    """
    No module but transport.py may reach the network.

    This is what makes "two floors under one synchronous interface" a
    structural fact rather than an intention. Walking the syntax tree catches
    an import inside a function too, which is where such a shortcut would
    plausibly be hidden.
    """
    leaked = _imported_names(path) & FORBIDDEN
    assert not leaked, (
        f"{path.name} importiert {sorted(leaked)}. "
        "Nur transport.py darf HTTP sprechen."
    )


def test_every_request_goes_through_the_transport(client, transport) -> None:
    """
    The public surface reaches the network only through the injected floor.

    The stub raises on any path it does not know, so an unrecorded request
    would fail rather than pass unnoticed; and a call that bypassed the
    transport entirely would leave the request log short.
    """
    project = client.project("Leitungsnetz Nord")
    layer = project.layer("Gebäude Speicherstadt")
    layer.count()
    layer.where('"Höhe" > 10').fids()
    layer.feature(1)
    project.view()

    assert transport.count > 0
    assert all(request.url.startswith("http://stub/") for request in transport.requests)


# --- URL building ---------------------------------------------------------


def test_none_parameters_do_not_travel() -> None:
    """An unset filter or cursor disappears instead of arriving as "None"."""
    url = build_url("http://x", "/api/f", {"filter": None, "size": 10, "cursor": None})
    assert url == "http://x/api/f?size=10"


def test_booleans_travel_as_spring_reads_them() -> None:
    """Python prints True; the server binds true."""
    assert build_url("http://x", "/a", {"geometry": True, "desc": False}) == (
        "http://x/a?geometry=true&desc=false"
    )


def test_bbox_travels_as_four_repeated_values() -> None:
    """Which is what Spring binds to a double[]; verified against the server."""
    url = build_url("http://x", "/a", {"bbox": [9.9, 53.5, 10.1, 53.6]})
    assert url == "http://x/a?bbox=9.9&bbox=53.5&bbox=10.1&bbox=53.6"


def test_umlauts_in_a_filter_are_encoded() -> None:
    """
    Real field names carry them: "Straße", "Höhe".

    Both floors call this one function, so the encoding cannot differ between
    CPython and the browser.
    """
    url = build_url("http://x", "/a", {"filter": '"Höhe" > 10'})
    assert url == "http://x/a?filter=%22H%C3%B6he%22+%3E+10"


def test_no_query_leaves_a_bare_url() -> None:
    assert build_url("http://x/", "/a", {}) == "http://x/a"
    assert build_url("http://x", "a", None) == "http://x/a"


# --- responses ------------------------------------------------------------


def test_a_body_that_is_not_json_says_so() -> None:
    """
    A proxy or a login page answering instead of the API.

    The message carries the status and the beginning of the body, because
    "Expecting value: line 1 column 1" says nothing about who answered.
    """
    response = Response(200, "<html>Anmeldung</html>")
    with pytest.raises(hgis.TransportError) as error:
        response.json()
    assert "kein JSON" in str(error.value)
    assert "Anmeldung" in str(error.value)


# --- choosing a floor -----------------------------------------------------


def test_pyodide_is_recognised_by_platform(monkeypatch) -> None:
    """Pyodide compiles to WebAssembly, which sys.platform reports."""
    monkeypatch.setattr(sys, "platform", "emscripten")
    assert in_pyodide()
    assert isinstance(default_transport(), PyodideTransport)


def test_cpython_gets_the_httpx_floor(monkeypatch) -> None:
    monkeypatch.setattr(sys, "platform", "darwin")
    assert not in_pyodide()
    assert isinstance(default_transport(), HttpxTransport)


def test_the_browser_floor_says_where_it_runs() -> None:
    """
    Outside a browser there is no ``js`` module, and the message says so
    instead of leaving an ImportError from a name nobody recognises.
    """
    with pytest.raises(hgis.TransportError) as error:
        PyodideTransport().request("GET", "http://x/a")
    assert "Browser" in str(error.value)
