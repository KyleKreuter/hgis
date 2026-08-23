"""
The tools that change something.

Two kinds live here, and the difference matters to whoever reads a docstring
in a hurry:

* **What the person at the screen sees** -- the selection and the map view.
  Nothing is lost when these are wrong; the next call puts them right. They are
  how an agent shows a result instead of describing it.
* **What is stored** -- objects, layers, fields, styles. A wrong call here
  costs data. Deleting a whole layer goes to the trash and comes back;
  deleting single objects is recoverable only through the change log.

Every docstring for the second kind says plainly what it destroys. That is the
only safeguard this server has, since it offers the full surface without a
switch -- the operator's decision, made deliberately.
"""

from __future__ import annotations

from hgis.mcp.server import client, server  # noqa: F401
from hgis.mcp.shapes import WriteResult, tool_error  # noqa: F401
