"""
Read and write hGIS from Python.

    import hgis

    client = hgis.connect()
    project = client.project("Wandsbek, Zuschnitt-Beispiel")
    layer = project.layer("Straßenbaumkataster Hamburg")

    print(layer.describe())

    alt = layer.where("pflanzjahr < 1950").bbox(9.9, 53.5, 10.1, 53.6)
    print(alt.count())
    project.select(alt.fids())

The one rule worth knowing for reading: the server does the work. ``where``,
``bbox``, ``search`` and ``order_by`` describe a restriction and send nothing;
``count``, ``fids``, ``to_dataframe`` and iterating are what run it. Reading a
whole layer into Python and filtering it there works for a thousand objects
and fails for a million.

Writing is the opposite: nothing here is lazy. ``layer.insert(...)``,
``layer.edit(...)`` and the rest of :mod:`hgis.edits` act immediately, and
:class:`RequestGuard` refuses anything else that would change data before it
reaches the server -- see :class:`hgis.errors.GuardError`.

``pandas`` and ``shapely`` are optional. Everything except ``to_dataframe``
works without them.
"""

from .channel import (
    PROJECT_CATALOG_EVENT,
    PROJECT_VIEW_STATE_EVENT,
    Change,
    ChannelItem,
    Connected,
    for_project,
)
from .client import (
    DEFAULT_BASE_URL,
    Client,
    RequestGuard,
    connect,
    default_client_id,
)
from .edits import EditResult, FeatureUpdate, NewFeature
from .errors import (
    ApiError,
    ConflictError,
    GuardError,
    HgisError,
    InvalidArgumentError,
    InvalidClientIdError,
    MissingDependencyError,
    NotFoundError,
    TransportError,
    UnknownNameError,
    UnsafeTransportError,
)
from .jobs import Job
from .layer import (
    Classification,
    Field,
    FieldSummary,
    Layer,
    LayerDescription,
    LayerSource,
    TrashEntry,
    ValueCounts,
)
from .project import Inspection, InspectionField, Project, Selection, View
from .query import Feature, Page, Query
from .style import (
    RENDERER_CATEGORIZED,
    RENDERER_GRADUATED,
    RENDERER_HEATMAP,
    RENDERER_SINGLE,
    SYMBOL_FILL,
    SYMBOL_LINE,
    SYMBOL_MARKER,
    Category,
    ClassBreak,
    Labels,
    Renderer,
    Style,
    Symbol,
)
from .transport import (
    Event,
    HttpxTransport,
    PyodideTransport,
    Response,
    Transport,
    TransportTimeout,
)

__version__ = "0.1.0"

__all__ = [
    # starting point
    "connect",
    "Client",
    "DEFAULT_BASE_URL",
    "default_client_id",
    # what you get back
    "Project",
    "Layer",
    "Query",
    "Feature",
    "Page",
    "Field",
    "Selection",
    "View",
    "LayerDescription",
    "FieldSummary",
    "TrashEntry",
    "ValueCounts",
    "Classification",
    "LayerSource",
    # importing, see Project.inspect_import/.import_file/.import_geoportal
    "Job",
    "Inspection",
    "InspectionField",
    # the live channel, see Client.watch and Client.wait_for
    "Change",
    "Connected",
    "ChannelItem",
    "for_project",
    "PROJECT_VIEW_STATE_EVENT",
    "PROJECT_CATALOG_EVENT",
    # writing
    "NewFeature",
    "FeatureUpdate",
    "EditResult",
    # style, see hgis.layer.Layer.style and .set_style
    "Style",
    "Renderer",
    "Symbol",
    "Category",
    "ClassBreak",
    "Labels",
    "RENDERER_SINGLE",
    "RENDERER_CATEGORIZED",
    "RENDERER_GRADUATED",
    "RENDERER_HEATMAP",
    "SYMBOL_MARKER",
    "SYMBOL_LINE",
    "SYMBOL_FILL",
    # errors
    "HgisError",
    "ApiError",
    "NotFoundError",
    "ConflictError",
    "TransportError",
    "TransportTimeout",
    "UnknownNameError",
    "GuardError",
    "InvalidClientIdError",
    "InvalidArgumentError",
    "UnsafeTransportError",
    "MissingDependencyError",
    # transport, for substituting the HTTP floor
    "Transport",
    "HttpxTransport",
    "PyodideTransport",
    "RequestGuard",
    "Response",
    "Event",
    "__version__",
]
