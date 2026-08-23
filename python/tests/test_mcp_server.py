"""
The scaffolding: the server object, the shared connection, and error phrasing.

The tools themselves are tested in ``test_mcp_read.py`` and
``test_mcp_write.py``. What is tested here is what those two stand on.
"""

from __future__ import annotations

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
