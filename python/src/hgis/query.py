"""
A restriction on a layer, and the four ways to run it.

The rule this module exists for: the server filters, Python receives the
result. Not this --

    df = layer.to_dataframe()          # a million rows into memory
    big = df[df.flaeche > 500]         # of which 240 were wanted

but this --

    big = layer.where("flaeche > 500").to_dataframe()

which is the difference between a library that works on a thousand objects and
one that works on a million.

So :meth:`Query.where`, :meth:`Query.bbox`, :meth:`Query.search` and
:meth:`Query.order_by` build and send nothing. :meth:`Query.count`,
:meth:`Query.fids`, :meth:`Query.to_dataframe` and iterating are what run.

Every builder returns a new query. ``narrow = wide.where(...)`` leaves ``wide``
alone, so a query can be handed around without anyone having to know who else
holds it.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING, Any, Iterator, Sequence

from .errors import MissingDependencyError

if TYPE_CHECKING:
    from .layer import Layer

#: Rows per page while iterating, and the largest ``size`` this library ever
#: sends. The server's ceiling is 1000. Asking for more is refused with a 400
#: -- and on an older server it was clamped silently instead, which looked
#: exactly like a layer that ends early. Neither is worth risking, so nothing
#: here ever asks for more.
PAGE_SIZE = 1000


@dataclass(frozen=True)
class Feature:
    """
    One object of a layer.

    Attributes are keyed by field name, the same spelling a filter uses, so
    ``feature["Gattung"]`` and ``where("Gattung = ...")`` agree.

    :param fid: the object id, unique within its layer
    :param properties: the attributes, by field name
    :param geometry: GeoJSON in EPSG:4326, or None when it was not asked for
    :param row_version: PostgreSQL's xmin, for optimistic locking later
    """

    fid: int
    properties: dict[str, Any]
    geometry: dict[str, Any] | None = None
    row_version: str | None = None

    def __getitem__(self, name: str) -> Any:
        return self.properties[name]

    def get(self, name: str, default: Any = None) -> Any:
        return self.properties.get(name, default)

    def __contains__(self, name: object) -> bool:
        return name in self.properties

    def __repr__(self) -> str:
        shown = ", ".join(f"{key}={value!r}" for key, value in list(self.properties.items())[:3])
        more = ", ..." if len(self.properties) > 3 else ""
        return f"<hgis.Feature fid={self.fid} {shown}{more}>"


@dataclass(frozen=True)
class Page:
    """
    One page of a :class:`Query`, exactly as large as :meth:`Query.page` asked
    for and no larger.

    :param features: at most the requested ``size`` of them, geometry included
        only when it was asked for
    :param total_count: how many objects the whole restriction matches, not
        just this page -- present only when this page started at the
        beginning (``cursor`` was None on the request that produced it), since
        that is the only request the server counts on. None on every later
        page: without it, a caller who saw 50 of a page and stopped there has
        no way to tell 50 of 50 from 50 of 4000
    :param next_cursor: pass back as ``cursor`` to read the next page. None
        when this was the last one
    """

    features: list[Feature]
    total_count: int | None
    next_cursor: str | None

    def __iter__(self) -> Iterator[Feature]:
        return iter(self.features)

    def __len__(self) -> int:
        return len(self.features)

    def __repr__(self) -> str:
        total = "unbekannt" if self.total_count is None else str(self.total_count)
        more = ", weitere folgen" if self.next_cursor else ""
        return f"<hgis.Page {len(self.features)} von {total} Treffern{more}>"


class Query:
    """
    A restriction on one layer, not yet run.

    Build it with :meth:`where`, :meth:`bbox`, :meth:`search` and
    :meth:`order_by`; run it with :meth:`count`, :meth:`fids`,
    :meth:`to_dataframe` or by iterating.
    """

    def __init__(
        self,
        layer: "Layer",
        *,
        filter: str | None = None,
        search: str | None = None,
        bbox: tuple[float, float, float, float] | None = None,
        mode: str | None = None,
        sort: str | None = None,
        desc: bool = False,
    ) -> None:
        self._layer = layer
        self._filter = filter
        self._search = search
        self._bbox = bbox
        self._mode = mode
        self._sort = sort
        self._desc = desc

    # --- building ----------------------------------------------------------

    def where(self, expression: str) -> "Query":
        """
        Add a filter expression. Sends nothing.

        Called twice, the two are combined with AND -- each call narrows what
        the previous one left, which is what chaining reads as:

        >>> layer.where("pflanzjahr > 1990").where("bezirk = 'Wandsbek'")

        The expression is checked by the server against this layer's fields. An
        unknown field comes back naming the ones that exist.
        """
        combined = expression
        if self._filter:
            combined = f"({self._filter}) AND ({expression})"
        return self._with(filter=combined)

    def search(self, text: str) -> "Query":
        """
        Add a free-text term, matched against every text field. Sends nothing.

        Combined with :meth:`where` by AND. A second call replaces the first --
        the server takes one term, and silently dropping one of two would be
        worse than replacing it visibly.
        """
        return self._with(search=text)

    def bbox(
        self,
        min_lng: float,
        min_lat: float,
        max_lng: float,
        max_lat: float,
        *,
        mode: str | None = None,
    ) -> "Query":
        """
        Restrict to a rectangle in EPSG:4326, regardless of the layer's own CRS.
        Sends nothing.

        >>> q = layer.bbox(9.9, 53.5, 10.1, 53.6)

        :param mode: ``"intersects"`` keeps objects that touch the rectangle,
            ``"contains"`` only those entirely inside it. Without it the server
            compares bounding boxes, which is cheaper and includes objects whose
            box overlaps while the shape itself does not.
        """
        return self._with(bbox=(min_lng, min_lat, max_lng, max_lat), mode=mode)

    def order_by(self, field: str, *, desc: bool = False) -> "Query":
        """
        Order by one field, by source name or column name. Sends nothing.

        Objects without a value come last in both directions.
        """
        return self._with(sort=field, desc=desc)

    def _with(self, **changes: Any) -> "Query":
        """A copy with some parts replaced -- no builder ever mutates."""
        current = {
            "filter": self._filter,
            "search": self._search,
            "bbox": self._bbox,
            "mode": self._mode,
            "sort": self._sort,
            "desc": self._desc,
        }
        current.update(changes)
        return Query(self._layer, **current)

    def __repr__(self) -> str:
        parts = [f"layer={self._layer.name!r}"]
        if self._filter:
            parts.append(f"where={self._filter!r}")
        if self._search:
            parts.append(f"search={self._search!r}")
        if self._bbox:
            parts.append("bbox=" + ",".join(f"{value:g}" for value in self._bbox))
        if self._sort:
            parts.append(f"order_by={self._sort!r}" + (" desc" if self._desc else ""))
        return "<hgis.Query " + " ".join(parts) + ">"

    # --- running -----------------------------------------------------------

    def count(self) -> int:
        """
        How many objects match. Exactly one request.

        The count rides along with the first page of a query, so this asks for
        the smallest page there is and reads the total off it.
        """
        page = self._client.get(
            self._features_path, **self._params(), size=1, geometry=False
        )
        total = page.get("totalCount")
        # totalCount only travels on a first page, and this always is one.
        return int(total) if total is not None else len(page.get("features", []))

    def page(self, size: int, *, geometry: bool = False, cursor: str | None = None) -> Page:
        """
        Exactly one page, sized and shaped by the caller. Exactly one request.

        Where iterating always asks for :data:`PAGE_SIZE` rows with every
        geometry, this asks for exactly ``size`` and, by default, none of the
        geometries -- the server filters either way, so a caller who wants
        fifty rows to look at should not be handed a thousand geometries only
        to throw them away in Python. That is what iterating and breaking
        out early still does; reach for this instead when a bounded read is
        the actual goal, not a shortcut through the whole restriction.

        >>> first = layer.where("baujahr > 1990").page(50)
        >>> first.total_count       # every match, not just this page
        4128
        >>> len(first.features)     # 50 -- not 4128, and not 1000
        50

        :param size: rows for this page, 1 to :data:`PAGE_SIZE`. Above the
            ceiling the server refuses with a 400, the same rule
            :data:`PAGE_SIZE`'s own docstring explains for iterating
        :param geometry: include each object's geometry. Off by default --
            the opposite of iterating -- because a bounded read is usually
            for a count, a sample or a table, none of which needs a shape
        :param cursor: :attr:`Page.next_cursor` from an earlier call, to
            continue past it. There is no numeric offset to jump to an
            arbitrary position -- the server's own cursor is the only handle
            it hands out, and it is opaque on purpose. None reads from the
            beginning, and is the only request that carries
            :attr:`Page.total_count`
        """
        raw = self._client.get(
            self._features_path, **self._params(), size=size, geometry=geometry, cursor=cursor
        )
        names = _column_to_name(self._layer.fields())
        total = raw.get("totalCount")
        return Page(
            features=[_to_feature(row, names) for row in raw.get("features", [])],
            total_count=int(total) if total is not None else None,
            next_cursor=raw.get("nextCursor"),
        )

    def fids(self) -> list[int]:
        """
        The ids of every matching object.

        One request when the restriction is a filter or a search: the server has
        an endpoint that answers exactly this. A bbox or an ordering is not part
        of that endpoint, so those page through the features instead and cost
        one request per thousand objects.

        The server refuses more than 100.000 ids at once and says so.

        :return: ascending by fid, or in the requested order when one was given
        """
        if self._bbox is None and self._sort is None:
            answer = self._client.get(
                f"{self._features_path}/fids",
                filter=self._filter,
                search=self._search,
            )
            return list(answer.get("fids") or [])
        return [row["fid"] for row in self._raw_rows(geometry=False)]

    def __iter__(self) -> Iterator[Feature]:
        """
        Every matching object, geometry included, paging on its own.

        Pages are fetched as they are consumed, so breaking out of the loop
        early stops the requests too.
        """
        names = _column_to_name(self._layer.fields())
        for row in self._raw_rows(geometry=True):
            yield _to_feature(row, names)

    def to_dataframe(self, *, geometry: bool = True) -> Any:
        """
        Every matching object as a ``pandas.DataFrame``.

        One column per field, named the way a filter names it, plus ``fid``.
        With ``geometry`` a ``geometry`` column holds ``shapely`` objects when
        shapely is installed and GeoJSON dictionaries when it is not.

        Restrict first. This holds every matching row in memory at once, which
        is the one thing the rest of this library is built to avoid.

        :raises MissingDependencyError: when pandas is not installed, naming the
            command that installs it
        """
        pandas = _require("pandas", "dataframe")
        to_shape = _shapely_reader()

        names = _column_to_name(self._layer.fields())
        columns = ["fid"] + list(names.values())
        rows: list[dict[str, Any]] = []
        for row in self._raw_rows(geometry=geometry):
            record: dict[str, Any] = {"fid": row["fid"]}
            for column, value in (row.get("properties") or {}).items():
                record[names.get(column, column)] = value
            if geometry:
                shape = row.get("geometry")
                record["geometry"] = to_shape(shape) if shape and to_shape else shape
            rows.append(record)

        if geometry:
            columns.append("geometry")
        # Columns are named even for an empty result, so a caller can look at
        # df.columns after a query that matched nothing.
        return pandas.DataFrame(rows, columns=columns)

    # --- paging ------------------------------------------------------------

    def _raw_rows(self, *, geometry: bool) -> Iterator[dict[str, Any]]:
        """
        Every matching row, walking the server's keyset cursor.

        A layer of 229.876 objects arrives as 230 pages, never as one response.
        """
        cursor: str | None = None
        while True:
            page = self._client.get(
                self._features_path,
                **self._params(),
                size=PAGE_SIZE,
                geometry=geometry,
                cursor=cursor,
            )
            yield from page.get("features", [])
            cursor = page.get("nextCursor")
            if not cursor:
                return

    def _params(self) -> dict[str, Any]:
        """The restriction as query parameters. None values never travel."""
        params: dict[str, Any] = {
            "filter": self._filter,
            "search": self._search,
            "sort": self._sort,
            "mode": self._mode,
        }
        if self._bbox is not None:
            params["bbox"] = list(self._bbox)
        if self._sort is not None and self._desc:
            params["desc"] = True
        return params

    @property
    def _client(self) -> Any:
        return self._layer._client

    @property
    def _features_path(self) -> str:
        return f"/api/layers/{self._layer.id}/features"


def _column_to_name(fields: Sequence[Any]) -> dict[str, str]:
    """
    Column name to the name a caller should see.

    Source names are not unique -- a DBF truncates them to ten characters, so
    two attributes can arrive under one name. The second claimant keeps its
    column name in brackets, which is unique by definition, instead of
    overwriting the first.
    """
    mapping: dict[str, str] = {}
    taken: set[str] = set()
    for item in fields:
        name = item.name if item.name not in taken else f"{item.name} ({item.column})"
        taken.add(name)
        mapping[item.column] = name
    return mapping


def _to_feature(row: dict[str, Any], names: dict[str, str]) -> Feature:
    """One wire row as a Feature, keyed by name instead of by column."""
    properties = {
        names.get(column, column): value
        for column, value in (row.get("properties") or {}).items()
    }
    return Feature(
        fid=row["fid"],
        properties=properties,
        geometry=row.get("geometry"),
        row_version=row.get("rowVersion"),
    )


def _require(module: str, extra: str) -> Any:
    """
    Import an optional package, or say what to install.

    The message names the package and a command, because "No module named
    pandas" from three frames deep does not tell a reader that this library
    works fine without it.
    """
    try:
        return __import__(module)
    except ImportError as error:
        raise MissingDependencyError(
            f"{module} ist nicht installiert, wird aber für diesen Aufruf gebraucht. "
            f"Installieren Sie es mit: pip install 'hgis[{extra}]' oder pip install {module}"
        ) from error


def _shapely_reader() -> Any:
    """
    ``shapely.geometry.shape``, or None when shapely is not installed.

    Optional on purpose: without it geometries stay GeoJSON dictionaries and
    everything else works unchanged.
    """
    try:
        from shapely.geometry import shape
    except ImportError:
        return None
    return shape
