"""
A server made of files.

Every response under ``responses/`` was taken from a running hGIS with real
data -- 1003 buildings in "Gebäude Speicherstadt", whose fields are called
"Straße", "Höhe" and "Baujahr". Umlauts in field names and a layer that needs
two pages are both properties of the real thing, and both are what the tests
here would otherwise have to invent.

The one file written by hand is ``view-state-with-selection.json``: setting a
selection means writing to the server, and these tests do not write anywhere.
Its shape is copied from a real view state.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable
from urllib.parse import parse_qs, urlsplit

import pytest

import hgis
from hgis.transport import Response, Transport

RESPONSES = Path(__file__).parent / "responses"

PROJECT_ID = "019fec3a-ef0c-775c-a14f-7535e8a676eb"
LAYER_ID = "019fecb8-6f1d-7f11-abbf-beeeb5953247"
OTHER_LAYER_ID = "019fecc1-48a2-76b7-8732-019e83d5532a"


def load(name: str) -> str:
    """One stored response, as it came off the wire."""
    return (RESPONSES / name).read_text(encoding="utf-8")


def ok(name: str) -> Response:
    return Response(200, load(name))


@dataclass
class Recorded:
    """One request the library made."""

    method: str
    url: str

    @property
    def path(self) -> str:
        return urlsplit(self.url).path

    @property
    def params(self) -> dict[str, list[str]]:
        return parse_qs(urlsplit(self.url).query)

    def param(self, name: str) -> str | None:
        """One parameter, or None when it did not travel."""
        values = self.params.get(name)
        return values[0] if values else None


@dataclass
class FakeTransport(Transport):
    """
    Records every request and answers from a handler.

    Replaces the HTTP floor entirely, which is what lets the whole suite run
    without a backend -- and what proves the seam in :mod:`hgis.transport` is
    the only way out of this library.
    """

    handler: Callable[[Recorded], Response]
    requests: list[Recorded] = field(default_factory=list)
    bodies: list[Any] = field(default_factory=list)

    def request(
        self, method: str, url: str, json: Any = None, timeout: float = 30.0
    ) -> Response:
        recorded = Recorded(method, url)
        self.requests.append(recorded)
        self.bodies.append(json)
        return self.handler(recorded)

    @property
    def count(self) -> int:
        return len(self.requests)

    @property
    def paths(self) -> list[str]:
        return [request.path for request in self.requests]


def stub_server(request: Recorded) -> Response:
    """
    The stored server: answers the paths the library actually calls.

    An unexpected path fails loudly. A test that silently got a wrong-but-
    parseable answer would pass for the wrong reason.
    """
    path = request.path

    if path == "/api/projects":
        return ok("projects.json")
    if path == f"/api/projects/{PROJECT_ID}":
        return ok("project.json")
    if path == f"/api/projects/{PROJECT_ID}/view-state":
        if request.method == "PUT":
            return Response(204, "")
        return ok("view-state.json")
    if path == f"/api/projects/{PROJECT_ID}/layers":
        return ok("layers.json")

    if path == f"/api/layers/{LAYER_ID}":
        return ok("layer.json")
    if path == f"/api/layers/{OTHER_LAYER_ID}":
        return ok("layer.json")
    if path == f"/api/layers/{LAYER_ID}/features/fids":
        return ok("fids-filtered.json" if request.param("filter") else "fids.json")
    if path == f"/api/layers/{LAYER_ID}/features/1":
        return ok("feature-1.json")
    if path == f"/api/layers/{LAYER_ID}/features":
        return _features(request)
    if path == f"/api/layers/{LAYER_ID}/values":
        # limit=1 cuts the answer above the null entry, which is what forces
        # describe() to ask for the empty count separately.
        return ok(
            "values-strasse-top1.json"
            if request.param("limit") == "1"
            else "values-strasse.json"
        )
    if path == f"/api/layers/{LAYER_ID}/classify":
        field_name = request.param("field")
        if field_name == "Höhe":
            return ok("classify-hoehe.json")
        if field_name == "Baujahr":
            return ok("classify-baujahr.json")
        return Response(400, load("error-unknown-field.json"))

    raise AssertionError(f"Unerwartete Anfrage: {request.method} {request.url}")


def _features(request: Recorded) -> Response:
    """
    The feature endpoint, including its cursor.

    Geometry decides which pair of pages answers, because the real endpoint
    returns different bodies for the two -- a stub that served geometry-less
    rows to a request asking for geometry would be a server that lies, and the
    library would look correct while returning None for every shape.
    """
    with_geometry = request.param("geometry") == "true"

    if request.param("cursor"):
        return ok("features-geometry-page2.json" if with_geometry else "features-page2.json")
    if request.param("size") == "1":
        filter_text = request.param("filter")
        if filter_text and "IS NULL" in filter_text:
            return ok("features-null-strasse.json")
        return ok("features-filtered-size1.json" if filter_text else "features-size1.json")

    name = "features-geometry-page1.json" if with_geometry else "features-page1.json"
    return _cut_to_size(name, int(request.param("size") or 200))


def _cut_to_size(name: str, size: int) -> Response:
    """
    A stored page, shortened to what was asked for.

    The real endpoint never returns more rows than ``size``. A stub that
    ignored it would hand describe(), which asks for five, a thousand -- and
    the test would then confirm a sample size the server would never send.
    """
    body = json.loads(load(name))
    if len(body["features"]) <= size:
        return Response(200, json.dumps(body))

    body["features"] = body["features"][:size]
    # There are more rows behind this one, and a real server says so.
    body["nextCursor"] = "gekuerzt"
    return Response(200, json.dumps(body))


@pytest.fixture
def transport() -> FakeTransport:
    return FakeTransport(stub_server)


@pytest.fixture
def client(transport: FakeTransport) -> hgis.Client:
    return hgis.connect("http://stub", transport=transport)


@pytest.fixture
def layer(client: hgis.Client, transport: FakeTransport) -> hgis.Layer:
    """
    The 1003-object layer, already read.

    Handed over with the request log cleared, so a test that counts requests
    counts only its own.
    """
    result = client.layer(LAYER_ID)
    transport.requests.clear()
    transport.bodies.clear()
    return result


@pytest.fixture
def project(client: hgis.Client, transport: FakeTransport) -> hgis.Project:
    result = client.project(PROJECT_ID)
    transport.requests.clear()
    transport.bodies.clear()
    return result


def stored(name: str) -> Any:
    """A stored response as parsed JSON, for asserting against the source."""
    return json.loads(load(name))
