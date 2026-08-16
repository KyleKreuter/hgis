"""
Read hGIS from Python.

    import hgis

    client = hgis.connect()
    project = client.project("Wandsbek, Zuschnitt-Beispiel")
    layer = project.layer("Straßenbaumkataster Hamburg")

    print(layer.describe())

    big = layer.where("stammumfang > 300").bbox(9.9, 53.5, 10.1, 53.6)
    print(big.count())
    project.select(big.fids())

The one rule worth knowing: the server does the work. ``where``, ``bbox``,
``search`` and ``order_by`` describe a restriction and send nothing; ``count``,
``fids``, ``to_dataframe`` and iterating are what run it. Reading a whole layer
into Python and filtering it there works for a thousand objects and fails for a
million.

``pandas`` and ``shapely`` are optional. Everything except ``to_dataframe``
works without them.
"""

from .client import DEFAULT_BASE_URL, Client, connect
from .errors import (
    ApiError,
    HgisError,
    MissingDependencyError,
    NotFoundError,
    TransportError,
    UnknownNameError,
)
from .layer import Field, FieldSummary, Layer, LayerDescription
from .project import Project, Selection, View
from .query import Feature, Query
from .transport import HttpxTransport, PyodideTransport, Response, Transport

__version__ = "0.1.0"

__all__ = [
    # starting point
    "connect",
    "Client",
    "DEFAULT_BASE_URL",
    # what you get back
    "Project",
    "Layer",
    "Query",
    "Feature",
    "Field",
    "Selection",
    "View",
    "LayerDescription",
    "FieldSummary",
    # errors
    "HgisError",
    "ApiError",
    "NotFoundError",
    "TransportError",
    "UnknownNameError",
    "MissingDependencyError",
    # transport, for substituting the HTTP floor
    "Transport",
    "HttpxTransport",
    "PyodideTransport",
    "Response",
    "__version__",
]
