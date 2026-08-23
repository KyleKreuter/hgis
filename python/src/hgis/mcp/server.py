"""
The server object and the one connection every tool shares.

Thin on purpose. Nothing here decides anything about hGIS; it calls
:mod:`hgis` and hands the answer back. When a tool needs logic, the logic
belongs in the library, where the editor and everyone else gets it too.

**The connection is built on first use, not at startup.** An MCP server is
launched by its host -- Claude Code, an editor, an agent runner -- often long
before hGIS is up, and a server that refuses to start because port 8080 was
quiet is a server the host reports as broken. Instead the first tool call
reaches out, and if nothing answers, that one call fails with a sentence
saying so.
"""

from __future__ import annotations

import os
import threading

from mcp.server.mcpserver import MCPServer

import hgis
from hgis import Client

#: Where hGIS listens, when ``HGIS_URL`` says nothing.
DEFAULT_URL = hgis.DEFAULT_BASE_URL

#: The variable that moves the server to another hGIS.
URL_VARIABLE = "HGIS_URL"

server = MCPServer(
    name="hgis",
    version=hgis.__version__,
    instructions=(
        "hGIS ist ein Geoinformationssystem. Ein Projekt enthält Layer, ein Layer "
        "enthält Objekte mit Geometrie und Attributen.\n\n"
        "Beginnen Sie mit list_projects und describe_layer. describe_layer liefert "
        "Felder, Wertebereiche und Beispielzeilen in einem Aufruf -- lesen Sie das, "
        "bevor Sie filtern, sonst raten Sie Feldnamen.\n\n"
        "Der Server filtert, nicht Sie: geben Sie query_features einen where-Ausdruck "
        "mit, statt alle Objekte zu holen und in Ihrem Kontext zu sieben.\n\n"
        "select_features und set_view verändern, was der Mensch am Bildschirm sieht. "
        "Nutzen Sie sie, um ein Ergebnis zu zeigen, statt es nur zu beschreiben.\n\n"
        "Für alles, was über diese Werkzeuge hinausgeht, gibt es die Python-Bibliothek "
        "`hgis` mit derselben Oberfläche."
    ),
)

_lock = threading.Lock()
_client: Client | None = None


def client() -> Client:
    """
    The one client this process uses, built on first call.

    Safe to call from several threads: the client is built once and every
    caller gets that one.

    :raises RuntimeError: never here -- a server that is not answering shows up
        as a :class:`hgis.errors.TransportError` on the first request, which
        :func:`hgis.mcp.shapes.tool_error` turns into a readable sentence
    """
    global _client
    with _lock:
        if _client is None:
            _client = hgis.connect(os.environ.get(URL_VARIABLE, DEFAULT_URL))
        return _client


def use_client(replacement: Client | None) -> None:
    """
    Substitute the connection, for tests.

    Passing ``None`` puts it back to being built on next use.
    """
    global _client
    with _lock:
        _client = replacement


def log_to_stderr() -> None:
    """
    Point every log record at stderr, because stdout carries the protocol.

    ``force=True`` is the whole point: it replaces handlers an embedding
    program may already have installed, including one on stdout, which is the
    case this exists for.

    A separate function rather than three lines inside :func:`main` so a test
    can assert where the handlers end up without starting a server.
    """
    import logging
    import sys

    logging.basicConfig(stream=sys.stderr, force=True)


def main() -> None:
    """
    Run the server over stdio. This is what ``hgis-mcp`` starts.

    **stdout carries the protocol and nothing else.** One stray ``print`` or a
    log handler pointed at stdout puts a non-JSON line into the stream, and the
    host reads it as a malformed message -- the server then looks broken in a
    way that says nothing about logging. Today the risk is real but unrealised:
    ``httpx`` logs every request through :mod:`logging`, whose default stream
    happens to be stderr. That default is the only thing keeping the protocol
    clean, and it belongs to a library this package does not control, so it is
    made explicit here instead of relied upon.
    """
    import asyncio

    log_to_stderr()

    # Importing the tool modules is what registers them. Done here rather than
    # at module import so that `import hgis.mcp.server` in a test does not
    # depend on the order the modules happen to load in.
    from hgis.mcp import read_tools, write_tools  # noqa: F401

    asyncio.run(server.run_stdio_async())


def registered_tool_names() -> list[str]:
    """
    Every tool name the server offers, sorted.

    Exists so a test can assert the list without starting a server. Uses the
    public :meth:`MCPServer.list_tools`, which is a coroutine -- hence the
    ``asyncio.run`` here rather than at the call site.
    """
    import asyncio

    from hgis.mcp import read_tools, write_tools  # noqa: F401

    return sorted(tool.name for tool in asyncio.run(server.list_tools()))
