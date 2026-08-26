"""
What the tools hand back, and how a failure reaches the agent.

Two rules hold everything here together.

**A tool returns a dataclass, not a dict.** The SDK derives the output schema
from the return annotation, so a dataclass is what tells the agent which keys
it may expect before it calls. A bare ``dict`` types the answer as "an object,
contents unknown" and the agent finds out by trying.

**An identifier travels with every name.** ``describe_layer("Bäume")`` reads
well and breaks the moment two projects hold a layer by that name. Every shape
below carries the id next to the name, so the agent's second call can be exact
even when its first was convenient.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from hgis.errors import (
    ApiError,
    ConflictError,
    GuardError,
    HgisError,
    InvalidArgumentError,
    MissingDependencyError,
    NotFoundError,
    TransportError,
    UnknownNameError,
)


class ToolError(Exception):
    """
    A failure phrased for the agent that caused it.

    Raised instead of letting a library exception through, for one reason: the
    agent sees the message and nothing else, so the message has to be the whole
    explanation. ``NotFoundError: 019fec3a-...`` tells it a request failed;
    "Kein Projekt heißt 'Wandsbeck'. Vorhanden: 'Wandsbek, Zuschnitt-Beispiel'"
    tells it what to do next.
    """


def tool_error(error: Exception, *, doing: str) -> ToolError:
    """
    Turn a library exception into a sentence worth reading.

    The library already phrases its own errors well -- ``UnknownNameError``
    lists the names that would have worked, ``ApiError`` carries the server's
    own words. Those are passed through as they are. What this adds is the
    context the agent lost by calling a tool instead of writing the line
    itself: which operation failed, and for the transport case, that the
    problem is hGIS not answering rather than anything about the request.

    :param error: what was raised
    :param doing: what the tool was doing, as a phrase that fits after "beim":
        ``"Lesen der Projektliste"``
    """
    if isinstance(error, TransportError):
        return ToolError(
            f"hGIS antwortet nicht (beim {doing}): {error}. "
            "Läuft der Server, und stimmt HGIS_URL?"
        )
    if isinstance(
        error,
        (
            UnknownNameError,
            NotFoundError,
            ConflictError,
            InvalidArgumentError,
            GuardError,
            MissingDependencyError,
            ApiError,
        ),
    ):
        # These already name what would have been valid. Repeating the class
        # name in front of them would only add noise.
        return ToolError(str(error))
    if isinstance(error, HgisError):
        return ToolError(f"Fehler beim {doing}: {error}")
    raise error


@dataclass(frozen=True)
class ProjectSummary:
    """One project, as the project list shows it."""

    id: str
    name: str
    layer_count: int
    feature_count: int
    description: str | None = None
    srid: int = 4326
    #: minx, miny, maxx, maxy in EPSG:4326, or None when the project is empty
    extent: list[float] | None = None


@dataclass(frozen=True)
class LayerSummary:
    """One layer, without the field statistics that ``describe_layer`` gathers."""

    id: str
    name: str
    kind: str
    feature_count: int
    geometry_type: str | None = None
    srid: int | None = None
    visible: bool = True
    extent: list[float] | None = None


@dataclass(frozen=True)
class TrashItemSummary:
    """Ein gelöschter Layer im Papierkorb eines Projekts."""

    id: str
    name: str
    feature_count: int
    deleted_at: str | None = None
    deleted_by: str | None = None


@dataclass(frozen=True)
class FeatureRow:
    """
    One object of a layer.

    ``geometry`` is GeoJSON and is left out unless it was asked for: geometries
    are the largest part of an answer by far, and an agent counting buildings
    by district has no use for their outlines.
    """

    fid: int
    properties: dict[str, Any] = field(default_factory=dict)
    geometry: dict[str, Any] | None = None


@dataclass(frozen=True)
class QueryResult:
    """
    What a query returned, and whether it returned all of it.

    ``truncated`` is the field that keeps an agent honest. Without it, a limit
    of 50 on a layer of 4000 looks exactly like a layer of 50, and every
    conclusion drawn from the answer is wrong in the same invisible way.
    """

    layer_id: str
    layer_name: str
    #: how many objects match, over the whole layer, not just this page
    match_count: int
    features: list[FeatureRow]
    truncated: bool = False


@dataclass(frozen=True)
class ValueCount:
    """One distinct value of a field and how often it occurs."""

    value: Any
    count: int


@dataclass(frozen=True)
class WriteResult:
    """
    What a write did, in numbers the agent can check against its intent.

    Every write tool returns this, so "did it work" has the same answer shape
    everywhere. An agent that asked to update 12 objects and reads
    ``updated=3`` learns something a bare success message would have hidden.
    """

    #: what happened, as a sentence for the agent's transcript
    summary: str
    inserted: int = 0
    updated: int = 0
    deleted: int = 0
    #: identifiers of objects created, in the order they were given
    new_fids: list[int] = field(default_factory=list)


def extent_list(extent: tuple[float, float, float, float] | None) -> list[float] | None:
    """A bounding box as a JSON array, or None. The library hands back tuples."""
    return list(extent) if extent is not None else None
