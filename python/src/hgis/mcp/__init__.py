"""
hGIS as tools an agent can call.

    hgis-mcp

Speaks MCP over stdio and expects hGIS at ``HGIS_URL``, or at
``http://localhost:8080`` when that says nothing.

For Claude Code::

    claude mcp add hgis -- hgis-mcp

This package holds no logic of its own. Every tool calls :mod:`hgis` and hands
the answer back, which is the point: what an agent finds awkward here is
awkward in the library, and belongs fixed there.

**MCP and the library do different jobs.** A tool call answers "which layers
does this project have" in one step. Anything that computes -- joining a layer
against a spreadsheet, walking geometries -- is Python, and the library is
right there for it. Tools for the frequent small questions, Python for the
work.

Needs the ``mcp`` extra::

    pip install "hgis[http,mcp]"
"""

from hgis.mcp.server import DEFAULT_URL, URL_VARIABLE, main

#: The server object itself is deliberately **not** re-exported here. Binding
#: the name ``server`` in this package would shadow the submodule of the same
#: name, so that ``from hgis.mcp import server`` -- the obvious way to reach
#: :mod:`hgis.mcp.server` -- would hand back the ``MCPServer`` instance
#: instead, and ``server.use_client(...)`` would fail with an ``AttributeError``
#: naming ``MCPServer``. That happened while this package was being built.
#: Import the instance from where it lives: ``from hgis.mcp.server import server``.
__all__ = ["main", "DEFAULT_URL", "URL_VARIABLE"]
