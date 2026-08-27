"""
The scaffolding: the server object, the shared connection, and error phrasing.

The tools themselves are tested in ``test_mcp_read.py`` and
``test_mcp_write.py``. What is tested here is what those two stand on.
"""

from __future__ import annotations

import os

import pytest

import hgis
from conftest import FakeTransport, needs_mcp
from hgis.errors import NotFoundError, TransportError, UnknownNameError

pytestmark = [needs_mcp, pytest.mark.mcp]


def test_connection_is_built_on_first_use_not_at_import() -> None:
    """
    Importing the server must not reach for hGIS.

    An MCP host starts its servers when it starts, often before hGIS is up. A
    server that connected at import would be reported as broken for a reason
    that has nothing to do with it.
    """
    import hgis.mcp.server as module

    module.use_client(None)
    assert module._client is None

    module.use_client(hgis.connect("http://stub", transport=FakeTransport(lambda r: None)))
    assert module._client is not None
    module.use_client(None)


def test_url_comes_from_the_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    import hgis.mcp.server as module

    module.use_client(None)
    monkeypatch.setenv(module.URL_VARIABLE, "http://anderswo:9000")
    assert module.client().base_url == "http://anderswo:9000"
    module.use_client(None)


def test_every_tool_has_a_description() -> None:
    """
    A tool without a docstring is a tool the agent has to guess at.

    The SDK takes the description straight from the docstring, so an empty one
    reaches the agent as an empty description -- and it will still call the
    tool, just wrongly.
    """
    import asyncio

    from hgis.mcp import read_tools, write_tools  # noqa: F401
    from hgis.mcp.server import server

    tools = asyncio.run(server.list_tools())
    assert tools, "kein einziges Werkzeug registriert"
    missing = [tool.name for tool in tools if not (tool.description or "").strip()]
    assert not missing, f"Werkzeuge ohne Beschreibung: {missing}"


def test_set_style_names_fallback_symbol_for_categorized_and_graduated() -> None:
    """
    The bug found on the running system: an agent following ``set_style``'s
    own description built a ``categorized``/``graduated`` renderer without
    ``fallback_symbol``, because the description only named ``field`` and
    ``categories``/``classes`` as required. The server now refuses that
    document with an HTTP 400 (``LayerStyleService.validateRenderer``), but a
    document that never leaves this library because the description already
    named the missing member is the better failure -- one round trip earlier,
    with a docstring instead of a stack trace.
    """
    import asyncio

    from hgis.mcp import read_tools, write_tools  # noqa: F401
    from hgis.mcp.server import server

    tools = asyncio.run(server.list_tools())
    set_style = next(tool for tool in tools if tool.name == "set_style")
    description = set_style.input_schema["properties"]["style"]["description"]

    for renderer_type in ("categorized", "graduated"):
        assert f'"{renderer_type}"' in description, (
            f"set_style nennt den Renderer-Typ {renderer_type} nicht: {description}"
        )
        marker = description.index(f'"{renderer_type}"')
        clause_end = description.index(")", marker)
        clause = description[marker:clause_end]
        assert "fallback_symbol" in clause, (
            f"set_style nennt fallback_symbol nicht als Pflichtfeld für {renderer_type}: {clause}"
        )


def test_set_basemap_names_the_freetext_url_case_in_its_description() -> None:
    """
    ``basemap`` accepts two shapes: a catalog id, or an own tile URL template
    for a service the catalog does not list. An agent who only reads "eine
    Katalog-Id" would never think to try a URL of its own -- the same failure
    mode ``test_set_style_names_fallback_symbol_...`` above guards against for
    ``set_style``, one round trip earlier than a 400 from the server.

    The own URL template itself has two forms (VERTRAG.md, "Zwei Formen von
    urlTemplate", nachgetragen 27.08.): {z}/{x}/{y} for XYZ/WMTS, or
    {bbox-epsg-3857} for a WMS GetMap URL -- most German state surveying
    services (e.g. the Hamburg aerial imagery) only offer the latter. An
    agent who only reads about {z}/{x}/{y} would conclude, wrongly, that a
    WMS-only service cannot be used at all.
    """
    import asyncio

    from hgis.mcp import read_tools, write_tools  # noqa: F401
    from hgis.mcp.server import server

    tools = asyncio.run(server.list_tools())
    set_basemap = next(tool for tool in tools if tool.name == "set_basemap")
    description = set_basemap.input_schema["properties"]["basemap"]["description"]

    assert "https://" in description, (
        f"set_basemap nennt den Freitext-Fall (eigene URL) nicht: {description}"
    )
    for placeholder in ("{z}", "{x}", "{y}", "{bbox-epsg-3857}"):
        assert placeholder in description, (
            f"set_basemap nennt den Platzhalter {placeholder} nicht: {description}"
        )


def test_tool_names_are_unique() -> None:
    from hgis.mcp.server import registered_tool_names

    names = registered_tool_names()
    assert len(names) == len(set(names))


class TestErrorPhrasing:
    """
    What the agent reads when something fails.

    The agent sees the message and nothing else -- no traceback, no exception
    class. So the message has to carry the whole explanation.
    """

    def test_transport_failure_names_the_server_not_the_request(self) -> None:
        from hgis.mcp.shapes import tool_error

        error = tool_error(
            TransportError("http://localhost:8080/api/projects ist nicht erreichbar"),
            doing="Lesen der Projektliste",
        )
        text = str(error)
        assert "antwortet nicht" in text
        assert "Lesen der Projektliste" in text
        assert "HGIS_URL" in text, "der Agent muss erfahren, woran er drehen kann"

    def test_library_errors_are_passed_through_unchanged(self) -> None:
        """
        The library already names what would have been valid.

        Wrapping "Kein Layer heißt 'Baume'. Vorhanden: 'Bäume', 'Wege'" in
        another sentence would only push the useful part further from the eye.
        """
        from hgis.mcp.shapes import tool_error

        original = "Kein Layer heißt 'Baume'. Vorhanden: 'Bäume', 'Wege'."
        assert str(tool_error(UnknownNameError(original), doing="Suchen")) == original
        # ApiError and its subclasses carry the status alongside the server's
        # own sentence; str() is the sentence alone, and that is what travels.
        assert str(tool_error(NotFoundError(404, "Layer 7 gibt es nicht."), doing="Lesen")) == (
            "Layer 7 gibt es nicht."
        )

    def test_an_unrelated_exception_is_not_swallowed(self) -> None:
        """
        A bug in this server is not an hGIS error and must not be dressed as one.

        ``tool_error`` re-raises anything that is not an
        :class:`hgis.errors.HgisError`, so a ``KeyError`` in a tool shows up as
        a ``KeyError`` rather than as a plausible-sounding sentence about hGIS.
        """
        from hgis.mcp.shapes import tool_error

        with pytest.raises(KeyError):
            tool_error(KeyError("tippfehler"), doing="irgendwas")


def test_list_projects_reads_the_stored_projects(mcp_client) -> None:
    """The one tool the scaffolding ships with, end to end against the stub."""
    from hgis.mcp.read_tools import list_projects

    projects = list_projects()
    assert projects, "die abgelegte Antwort enthält Projekte"
    first = projects[0]
    assert first.id and first.name
    assert first.layer_count >= 0
    assert first.extent is None or len(first.extent) == 4


class TestStdoutCarriesOnlyTheProtocol:
    """
    A stray line on stdout breaks the session, and blames the wrong thing.

    The host parses every stdout line as a JSON-RPC message. One log line, one
    ``print``, and it reads a malformed message -- reported as a protocol
    error, which is the last place anyone would look for a logging setting.

    Tested by running the real thing as a subprocess rather than by reading
    ``main()``: the failure mode is about what lands in a pipe, and only a
    process has one.
    """

    def _run(self, extra_env: dict[str, str]) -> tuple[str, str]:
        """Start the server, speak one request, hand back stdout and stderr."""
        import json
        import subprocess
        import sys

        conversation = "\n".join(
            json.dumps(message)
            for message in (
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "initialize",
                    "params": {
                        "protocolVersion": "2024-11-05",
                        "capabilities": {},
                        "clientInfo": {"name": "test", "version": "1"},
                    },
                },
                {"jsonrpc": "2.0", "method": "notifications/initialized"},
                {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
            )
        )
        finished = subprocess.run(
            [sys.executable, "-m", "hgis.mcp"],
            input=conversation,
            capture_output=True,
            text=True,
            timeout=60,
            env={**os.environ, **extra_env},
        )
        return finished.stdout, finished.stderr

    def _non_json_lines(self, stream: str) -> list[str]:
        import json

        offenders = []
        for line in stream.splitlines():
            if not line.strip():
                continue
            try:
                json.loads(line)
            except json.JSONDecodeError:
                offenders.append(line)
        return offenders

    def test_stdout_stays_pure_json(self) -> None:
        out, _ = self._run({})
        assert out.strip(), "der Server hat gar nicht geantwortet"
        assert self._non_json_lines(out) == []

    def test_log_handlers_are_moved_off_stdout(self) -> None:
        """
        The case ``log_to_stderr()`` exists for, measured directly.

        An embedding program installs a handler on stdout; the server has to
        take it away, or httpx's request log -- one line per request it makes
        -- lands in the protocol stream.

        **What this does not cover, and cannot:** whatever writes to stdout
        *before* ``main()`` is reached is already in the pipe. Measured while
        writing this test -- a ``sitecustomize`` that logs at import time puts
        its line there no matter what happens later. Nothing in this package
        can close that; it belongs to whoever configures the interpreter.
        """
        import logging
        import sys

        from hgis.mcp.server import log_to_stderr

        before = logging.root.handlers[:]
        try:
            logging.basicConfig(stream=sys.stdout, force=True)
            aimed_at_stdout = [
                handler
                for handler in logging.root.handlers
                if getattr(handler, "stream", None) is sys.stdout
            ]
            assert aimed_at_stdout, "die Falle wurde gar nicht gestellt, der Test misst nichts"

            log_to_stderr()

            leftover = [
                handler
                for handler in logging.root.handlers
                if getattr(handler, "stream", None) is sys.stdout
            ]
            assert leftover == [], "ein Handler zeigt weiterhin auf den Protokollstrom"
            assert logging.root.handlers, "es blieb gar kein Handler uebrig"
        finally:
            logging.root.handlers[:] = before
