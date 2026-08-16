"""A layer: its fields, one printable description, and the start of every query."""

from __future__ import annotations

from dataclasses import dataclass
from dataclasses import field as dataclass_field
from typing import TYPE_CHECKING, Any, Iterator

from .errors import ApiError
from .query import PAGE_SIZE, Feature, Query, _column_to_name, _to_feature

if TYPE_CHECKING:
    from .client import Client
    from .project import Project

#: Column types the backend counts as numeric -- what /classify accepts, and what
#: gets a min/max rather than a list of frequent values in describe().
_NUMERIC_TYPES = frozenset(
    {"integer", "bigint", "smallint", "double precision", "numeric", "real"}
)


@dataclass(frozen=True)
class Field:
    """
    One attribute of a layer.

    Three of these name the same field, and only one of them always names it
    alone. Neither the source name nor the column name is guaranteed unique:
    an import can produce "Stammumfang Quelle" with the column ``stammumfang``
    beside "Stammumfang" with the column ``stammumfang_z``, and then the word
    "stammumfang" matches both. The id is the identifier that cannot collide.

    :param id: this field's own identifier, unique by construction
    :param name: what the UI shows -- the source name, as it came out of the
        imported file. Usually what a filter should use
    :param column: the SQL column, which is how it is keyed in a raw feature
        response. Filters accept this spelling too
    :param type: the PostgreSQL type, e.g. ``text``, ``bigint``
    """

    id: str
    name: str
    column: str
    type: str

    @property
    def is_numeric(self) -> bool:
        return _base_type(self.type) in _NUMERIC_TYPES


class Layer:
    """
    One layer of a project.

    Also the start of every query: :meth:`where`, :meth:`bbox` and
    :meth:`order_by` build a :class:`~hgis.query.Query` and send nothing, while
    :meth:`count`, :meth:`fids`, :meth:`to_dataframe` and iterating run it.
    """

    def __init__(
        self,
        client: "Client",
        data: dict[str, Any],
        project: "Project | None" = None,
    ) -> None:
        self._client = client
        self._data = data
        self._project = project
        self._fields: list[Field] | None = None
        if "fields" in data:
            # Came from a single read, which already carries them.
            self._fields = [_to_field(item) for item in data["fields"]]

    # --- what the server already told us -----------------------------------

    @property
    def id(self) -> str:
        return self._data["id"]

    @property
    def name(self) -> str:
        return self._data["name"]

    @property
    def kind(self) -> str:
        """``VECTOR`` for a layer with objects, ``WMS`` for a map image."""
        return self._data.get("kind") or "VECTOR"

    @property
    def geometry_type(self) -> str | None:
        """MULTIPOINT, MULTILINESTRING, MULTIPOLYGON, GEOMETRY, or None for WMS."""
        return self._data.get("geometryType")

    @property
    def srid(self) -> int | None:
        """The CRS the geometries are stored in. None for a map image."""
        return self._data.get("srid")

    @property
    def feature_count(self) -> int:
        """
        Objects in this layer, as of when this object was read.

        :meth:`count` asks the server again; this does not.
        """
        return self._data.get("featureCount", 0)

    @property
    def visible(self) -> bool:
        return self._data.get("visible", True)

    @property
    def extent(self) -> tuple[float, float, float, float] | None:
        """(minLng, minLat, maxLng, maxLat) in EPSG:4326, or None if empty."""
        value = self._data.get("extent")
        if not value or len(value) != 4:
            return None
        return (value[0], value[1], value[2], value[3])

    def __repr__(self) -> str:
        return (
            f"<hgis.Layer {self.name!r} {self.geometry_type} "
            f"features={self.feature_count}>"
        )

    # --- fields ------------------------------------------------------------

    def fields(self) -> list[Field]:
        """
        The attributes of this layer, in their stored order.

        Read once and kept: a layer's shape does not change while a script runs,
        and every page of features would otherwise pay for the same lookup.
        Call :meth:`refresh` after changing the layer elsewhere.
        """
        if self._fields is None:
            self._data = self._client.get(f"/api/layers/{self.id}")
            self._fields = [_to_field(item) for item in self._data["fields"]]
        return self._fields

    def field(self, name: str) -> Field:
        """
        One field by id, source name or column name, matched case-insensitively.

        A name that fits two fields is refused rather than resolved. An import
        can produce "Stammumfang Quelle" with the column ``stammumfang`` beside
        "Stammumfang" with the column ``stammumfang_z``, and then "stammumfang"
        names both. Picking one would be a guess that looks like an answer --
        and the two would give different results.

        :raises UnknownNameError: naming the fields that do exist, or -- for an
            ambiguous name -- both candidates and their ids
        """
        from .errors import UnknownNameError

        wanted = name.casefold()
        for item in self.fields():
            if wanted == item.id:
                return item

        matches = [
            item
            for item in self.fields()
            if wanted in (item.name.casefold(), item.column.casefold())
        ]
        if len(matches) == 1:
            return matches[0]
        if len(matches) > 1:
            candidates = ", ".join(f"{item.name} ({item.id})" for item in matches)
            raise UnknownNameError(
                f"Mehrdeutiges Feld: {name}. Gemeint sein kann: {candidates}. "
                "Verwenden Sie die Feld-Id."
            )
        available = ", ".join(item.name for item in self.fields())
        raise UnknownNameError(f"Unbekanntes Feld: {name}. Verfügbar: {available}.")

    def ambiguous_names(self) -> set[str]:
        """
        The lowercased spellings that fit more than one field of this layer.

        Neither source names nor column names are unique on their own, and a
        name matching both kinds across two fields is the case that bites: the
        server resolves it one way for a filter and another for a sort. Ask
        this before building a filter from a name a person typed, or read the
        answer out of :meth:`describe`, which marks such fields with their id.
        """
        seen: dict[str, int] = {}
        for item in self.fields():
            for spelling in {item.name.casefold(), item.column.casefold()}:
                seen[spelling] = seen.get(spelling, 0) + 1
        return {spelling for spelling, count in seen.items() if count > 1}

    def refresh(self) -> "Layer":
        """Read this layer again, discarding the cached fields."""
        self._data = self._client.get(f"/api/layers/{self.id}")
        self._fields = [_to_field(item) for item in self._data["fields"]]
        return self

    # --- the whole picture, in one call ------------------------------------

    def describe(self, *, stats: bool = True, sample: int = 5, top: int = 5) -> "LayerDescription":
        """
        Everything worth knowing about this layer, printable.

        Name, geometry type, CRS and object count; per field the type, how much
        of it is empty and what range it covers; and a few rows to see what the
        values look like.

        The server has no single endpoint for this, so it is assembled from
        several: the layer, one page of features, and -- with ``stats`` -- one
        request per field. On a layer with many fields that is many requests;
        ``describe(stats=False)`` skips them and returns names and types alone.

        A field whose statistics the server refuses (an exotic type, say) keeps
        its name and type and leaves the rest empty, so one odd column cannot
        cost the whole description.

        :param stats: gather per-field emptiness and range
        :param sample: how many example rows to read
        :param top: how many frequent values to keep per non-numeric field
        """
        if "fields" not in self._data:
            self.refresh()

        # Clamped here, not at the server: a size above the ceiling or below 1
        # is refused with a 400, and a sample size is not worth an error.
        page = self._client.get(
            f"/api/layers/{self.id}/features",
            size=max(1, min(sample, PAGE_SIZE)),
            geometry=False,
        )
        total = page.get("totalCount")
        names = _column_to_name(self.fields())
        rows = [_to_feature(row, names) for row in page.get("features", [])]

        ambiguous = self.ambiguous_names()
        summaries = [
            self._describe_field(item, stats=stats, top=top, ambiguous=ambiguous)
            for item in self.fields()
        ]

        return LayerDescription(
            name=self.name,
            kind=self.kind,
            geometry_type=self.geometry_type,
            srid=self.srid,
            feature_count=total if total is not None else self.feature_count,
            extent=self.extent,
            fields=summaries,
            sample=rows,
        )

    def reference(self, item: Field, ambiguous: set[str] | None = None) -> str:
        """
        The spelling that names exactly this field and no other.

        The source name where it is unique, otherwise the column name, and the
        id when neither is. Use it whenever a field name is put into a filter,
        a sort or an endpoint's ``field`` parameter -- a name that fits two
        fields resolves one way for a filter and another for a sort, which is
        how a query silently answers about the wrong column.

        :param ambiguous: a precomputed :meth:`ambiguous_names`, to avoid
            recomputing it once per field
        """
        collisions = self.ambiguous_names() if ambiguous is None else ambiguous
        if item.name.casefold() not in collisions:
            return item.name
        if item.column.casefold() not in collisions:
            return item.column
        return item.id

    def _describe_field(
        self, item: Field, *, stats: bool, top: int, ambiguous: set[str]
    ) -> "FieldSummary":
        """One field's statistics, or just its name and type when stats are off."""
        unique = item.name.casefold() not in ambiguous
        base = dict(
            id=item.id, name=item.name, column=item.column, type=item.type,
            ambiguous=not unique,
        )
        if not stats:
            return FieldSummary(**base)

        reference = self.reference(item, ambiguous)
        try:
            if item.is_numeric:
                return self._numeric_summary(item, base, reference)
            return self._value_summary(item, base, reference, top)
        except ApiError as error:
            # One column the server will not summarise must not lose the other
            # twenty-three. The reason is kept, not swallowed.
            return FieldSummary(**base, note=str(error))

    def _numeric_summary(
        self, item: Field, base: dict[str, Any], reference: str
    ) -> "FieldSummary":
        """min, max and the empty count in one request, from /classify."""
        breaks = self._client.get(
            f"/api/layers/{self.id}/classify", field=reference, classes=2
        )
        return FieldSummary(
            **base,
            null_count=breaks.get("nullCount"),
            minimum=breaks.get("min"),
            maximum=breaks.get("max"),
        )

    def _value_summary(
        self, item: Field, base: dict[str, Any], reference: str, top: int
    ) -> "FieldSummary":
        """
        The frequent values, and the empty count.

        /values reports null as a value of its own, so usually the count comes
        along for free. Only when the answer was truncated *and* null did not
        make the cut is a second request needed -- the alternative would be
        reporting a number that is merely likely.
        """
        answer = self._client.get(
            f"/api/layers/{self.id}/values", field=reference, limit=max(1, top)
        )
        values = answer.get("values") or []
        truncated = bool(answer.get("truncated"))

        null_count = None
        for entry in values:
            if entry.get("value") is None:
                null_count = entry.get("count")
                break
        if null_count is None and not truncated:
            null_count = 0
        if null_count is None:
            null_count = self.where(f'"{reference}" IS NULL').count()

        return FieldSummary(
            **base,
            null_count=null_count,
            top_values=[
                (entry.get("value"), entry.get("count"))
                for entry in values
                if entry.get("value") is not None
            ][:top],
            truncated=truncated,
        )

    # --- queries -----------------------------------------------------------

    def query(self) -> Query:
        """This layer with no restriction -- the start of a chain."""
        return Query(self)

    def where(self, expression: str) -> Query:
        """
        Restrict by a filter expression. Builds only; sends nothing.

        >>> layer.where("pflanzjahr > 1990 AND gattung LIKE 'Quercus%'")

        Field names with spaces or umlauts go in double quotes, values in single
        quotes. Comparisons, LIKE/ILIKE, IS [NOT] NULL, IN, AND, OR, NOT and
        parentheses are understood. An unknown field is refused by the server
        with a message naming the ones that exist.
        """
        return Query(self).where(expression)

    def search(self, text: str) -> Query:
        """
        Restrict by free text over every text field of this layer.

        For when the field is not known yet. Combined with :meth:`where` by AND.
        """
        return Query(self).search(text)

    def bbox(
        self,
        min_lng: float,
        min_lat: float,
        max_lng: float,
        max_lat: float,
        *,
        mode: str | None = None,
    ) -> Query:
        """
        Restrict to a rectangle in EPSG:4326. Builds only; sends nothing.

        :param mode: ``"intersects"`` for objects touching the rectangle,
            ``"contains"`` for objects entirely inside it. Without it the server
            compares bounding boxes only, which is faster and coarser.
        """
        return Query(self).bbox(min_lng, min_lat, max_lng, max_lat, mode=mode)

    def order_by(self, field: str, *, desc: bool = False) -> Query:
        """Order by one field. Builds only; sends nothing."""
        return Query(self).order_by(field, desc=desc)

    # --- running a query over the whole layer ------------------------------

    def count(self) -> int:
        """How many objects this layer holds, asked now. One request."""
        return Query(self).count()

    def fids(self) -> list[int]:
        """Every object id in this layer, ascending."""
        return Query(self).fids()

    def to_dataframe(self, *, geometry: bool = True) -> Any:
        """Every object as a ``pandas.DataFrame``. See :meth:`Query.to_dataframe`."""
        return Query(self).to_dataframe(geometry=geometry)

    def __iter__(self) -> Iterator[Feature]:
        """Every object, paging on its own."""
        return iter(Query(self))

    def feature(self, fid: int) -> Feature:
        """
        One object with all its attributes and its geometry.

        :raises NotFoundError: when this layer has no such object
        """
        row = self._client.get(f"/api/layers/{self.id}/features/{fid}")
        return _to_feature(row, _column_to_name(self.fields()))

    def values(self, field: str, *, limit: int = 20) -> list[tuple[Any, int]]:
        """
        The values of one field with their counts, most frequent first.

        Null appears as a value of its own. The list is cut at ``limit``; ask
        :meth:`describe` if you want to know whether it was.
        """
        answer = self._client.get(
            f"/api/layers/{self.id}/values", field=field, limit=limit
        )
        return [(entry.get("value"), entry.get("count")) for entry in answer.get("values") or []]


@dataclass(frozen=True)
class FieldSummary:
    """
    One field as :meth:`Layer.describe` found it.

    :param id: the field's identifier, the one reference that never collides
    :param ambiguous: this field's name also fits another field of the layer.
        Then only the id, or the column name, names it alone
    :param null_count: objects with no value here, or None when not gathered
    :param minimum: smallest value, numeric fields only
    :param maximum: largest value, numeric fields only
    :param top_values: (value, count) pairs, most frequent first, non-numeric only
    :param truncated: more distinct values exist than were read
    :param note: why the statistics are missing, when the server refused them
    """

    id: str
    name: str
    column: str
    type: str
    ambiguous: bool = False
    null_count: int | None = None
    minimum: float | None = None
    maximum: float | None = None
    top_values: list[tuple[Any, int]] = dataclass_field(default_factory=list)
    truncated: bool = False
    note: str | None = None

    def null_fraction(self, total: int) -> float | None:
        """The share of objects with no value here, between 0 and 1."""
        if self.null_count is None or total <= 0:
            return None
        return self.null_count / total


@dataclass(frozen=True)
class LayerDescription:
    """
    What :meth:`Layer.describe` found, written to be read.

    ``print(layer.describe())`` is the point of this class: it is how the answer
    reaches a person, and how it reaches an agent's context.
    """

    name: str
    kind: str
    geometry_type: str | None
    srid: int | None
    feature_count: int
    extent: tuple[float, float, float, float] | None
    fields: list[FieldSummary]
    sample: list[Feature]

    def __repr__(self) -> str:
        return self.to_text()

    def __str__(self) -> str:
        return self.to_text()

    def to_text(self) -> str:
        """The description as printable text."""
        lines = [
            f"Layer {self.name!r}",
            f"  Geometrie: {self.geometry_type or '-'}   CRS: EPSG:{self.srid}"
            f"   Objekte: {_grouped(self.feature_count)}",
        ]
        if self.extent:
            box = ", ".join(f"{value:.5f}" for value in self.extent)
            lines.append(f"  Ausschnitt (EPSG:4326): {box}")

        lines.append("")
        lines.append(f"  Felder ({len(self.fields)}):")
        for item in self.fields:
            lines.append("    " + self._field_line(item))

        if self.sample:
            lines.append("")
            lines.append(f"  Beispielzeilen ({len(self.sample)}):")
            for row in self.sample:
                lines.append(f"    fid {row.fid}: {_short(row.properties)}")
        return "\n".join(lines)

    def _field_line(self, item: FieldSummary) -> str:
        """One field on one line: name, type, emptiness, range."""
        parts = [f"{item.name} ({item.type})"]
        if item.ambiguous:
            # The name alone would reach two fields, so the line that an agent
            # reads has to carry the reference that reaches one.
            parts.append(f"mehrdeutig, Id {item.id}")
        share = item.null_fraction(self.feature_count)
        if share is not None:
            parts.append(f"leer {share * 100:.1f}%")
        if item.minimum is not None or item.maximum is not None:
            parts.append(f"von {_number(item.minimum)} bis {_number(item.maximum)}")
        if item.top_values:
            shown = ", ".join(f"{value!r} ({count})" for value, count in item.top_values[:3])
            parts.append(f"häufig: {shown}" + (", ..." if item.truncated else ""))
        if item.note:
            parts.append(f"ohne Statistik: {item.note}")
        return "  ".join(parts)


def _to_field(item: dict[str, Any]) -> Field:
    return Field(item["id"], item["sourceName"], item["columnName"], item["dataType"])


def _base_type(type_name: str) -> str:
    """Strip a length or precision suffix: "numeric(10,2)" -> "numeric"."""
    lowered = type_name.lower()
    parenthesis = lowered.find("(")
    return lowered if parenthesis < 0 else lowered[:parenthesis]


def _grouped(count: int) -> str:
    """A count with German thousands separators: 229876 -> 229.876."""
    return f"{count:,}".replace(",", ".")


def _number(value: float | None) -> str:
    if value is None:
        return "?"
    if float(value).is_integer():
        return str(int(value))
    return f"{value:.4g}"


def _short(properties: dict[str, Any], limit: int = 4) -> str:
    """The first few attributes of a row, for a sample line."""
    items = list(properties.items())[:limit]
    text = ", ".join(f"{key}={value!r}" for key, value in items)
    return text + (", ..." if len(properties) > limit else "")
