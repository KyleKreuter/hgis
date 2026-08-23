"""
The tools that only look.

Nothing here changes anything in hGIS, which is why an agent can call them
freely while it works out what it is dealing with.

**A docstring here is not documentation, it is the tool's interface.** It is
the entire text the agent reads before deciding whether to call, so it says
what the tool answers and when to reach for it -- not how it is implemented.
The parameter descriptions travel too, and are where a filter syntax or a unit
gets explained.
"""

from __future__ import annotations

from hgis.mcp.server import client, server
from hgis.mcp.shapes import ProjectSummary, extent_list, tool_error


@server.tool()
def list_projects() -> list[ProjectSummary]:
    """
    Alle Projekte dieses hGIS, mit Anzahl der Layer und Objekte.

    Der übliche erste Aufruf: er liefert die Projekt-Id, die jedes andere
    Werkzeug braucht, und zeigt an den Zahlen, welches Projekt Daten enthält.
    """
    try:
        return [
            ProjectSummary(
                id=project.id,
                name=project.name,
                layer_count=project.layer_count,
                feature_count=project.feature_count,
                description=project.description,
                srid=project.srid,
                extent=extent_list(project.extent),
            )
            for project in client().projects()
        ]
    except Exception as error:
        raise tool_error(error, doing="Lesen der Projektliste") from error
