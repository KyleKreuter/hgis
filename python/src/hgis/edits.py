"""
One batch of writes to a layer's objects, and what it reports back.

The counterpart to :mod:`hgis.query`: that module builds a restriction and
runs it late; this one builds a batch and sends it now. There is no lazy form
here on purpose -- a write that only takes effect when someone later iterates
it is the one shape this stage refuses to offer. See :meth:`hgis.layer.Layer.edit`.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING, Any, Mapping, Sequence

from .errors import InvalidArgumentError

if TYPE_CHECKING:
    from .client import Client


@dataclass(frozen=True)
class NewFeature:
    """
    One object to create, for :meth:`hgis.layer.Layer.edit`.

    :param geometry: GeoJSON in EPSG:4326, the same shape a read hands back
    :param properties: keyed by column name, like everywhere else in the
        feature API; a field left out simply stays at the column's default
    """

    geometry: Mapping[str, Any]
    properties: Mapping[str, Any] | None = None


@dataclass(frozen=True)
class FeatureUpdate:
    """
    One object to change, for :meth:`hgis.layer.Layer.edit`.

    :param fid: the object to change
    :param row_version: :attr:`hgis.query.Feature.row_version`, as it was read.
        A mismatch means someone else wrote the row since then and raises
        :class:`hgis.errors.ConflictError`, carrying the current row.
    :param geometry: None leaves the geometry untouched -- an attribute-only
        edit
    :param properties: None leaves every attribute untouched. Given, a key
        missing from it leaves that one column alone; a key present with the
        value None sets that column to SQL NULL. That is what lets a caller
        send only the cells that actually changed.
    """

    fid: int
    row_version: str
    geometry: Mapping[str, Any] | None = None
    properties: Mapping[str, Any] | None = None


@dataclass(frozen=True)
class EditResult:
    """
    What one batch of writes did. Returned by :meth:`hgis.layer.Layer.edit`
    and every convenience method built on it -- never a silent ``None``, see
    the module docstring.

    :param created_fids: the fid the server assigned each entry of ``creates``,
        in the same order they were given
    :param updated: how many objects :meth:`hgis.layer.Layer.edit` changed
    :param deleted: how many objects it deleted
    :param data_version: the layer's new tile cache buster -- rebuild the tile
        URL from this to see the change on the map
    :param feature_count: the layer's object count after this batch
    """

    created_fids: list[int]
    updated: int
    deleted: int
    data_version: int
    feature_count: int

    def __repr__(self) -> str:
        return (
            f"<hgis.EditResult erstellt={len(self.created_fids)} geändert={self.updated} "
            f"gelöscht={self.deleted} data_version={self.data_version}>"
        )


#: Iterable types that are actually one value, not a collection of them.
#: Passed where a list of fids or edits was expected, each of these is walked
#: element by element instead of failing outright -- a ``str`` splits into
#: characters (``"123"`` -> ``['1', '2', '3']``); ``bytes``, ``bytearray`` and
#: ``memoryview`` split into byte values (``b"123"`` -> ``[49, 50, 51]``, the
#: ASCII codes of the digits, not the digits). Both land on the server as a
#: batch touching several unrelated objects instead of the one that was meant.
#:
#: An enumeration of types, and known to be incomplete because of it: it
#: catches every value that *is* one of these four types, and nothing that
#: merely *behaves* like one without being one. See
#: :func:`_decomposes_into_single_characters` for the other half of the check
#: -- the one that looks at what a value does rather than what it is.
_SCALAR_ITERABLES = (str, bytes, bytearray, memoryview)


def _decomposes_into_single_characters(value: Any) -> bool:
    """
    True for a value that is not one of :data:`_SCALAR_ITERABLES` by type, but
    still splits the same way they do. ``collections.UserString`` is the
    standard library's own example: it wraps a real ``str`` rather than
    subclassing one, so ``isinstance(value, str)`` misses it, yet
    ``list(UserString("123"))`` is ``['1', '2', '3']``, exactly the break
    this whole check exists to catch.

    Checks the effect, not the type: for ``str`` and everything that behaves
    like it, indexing one element (``value[0]``) and slicing that same one
    element (``value[0:1]``) come back equal, because there is no separate
    "character" type standing between the two. An actual collection never
    has that property -- ``[1, 2, 3][0]`` is ``1``, ``[1, 2, 3][0:1]`` is
    ``[1]``, and ``1 == [1]`` is false for every real collection, not only
    lists. Compared with ``is True`` rather than trusted as a plain bool,
    because a NumPy array answers ``==`` element-wise: a single-element
    ``numpy.ndarray`` would otherwise pass this check by returning an array
    holding one ``True`` instead of the ``bool`` a real match returns, and be
    mistaken for one of these when it is only a collection with one entry.

    A value with no ``__getitem__`` at all -- a generator, a ``set``, a
    ``dict`` -- fails on the first line and is left alone. Indexing rather
    than ``next(iter(value))`` is deliberate: consuming from an iterator to
    peek at it would silently drop that first item for whatever reads
    ``value`` next; a one-shot iterator simply is not subscriptable, so this
    never touches it.
    """
    try:
        first, one_slice = value[0], value[0:1]
    except (TypeError, IndexError, KeyError):
        return False
    return (first == one_slice) is True


def _reject_scalar_iterable(name: str, value: Any) -> None:
    """
    :raises hgis.errors.InvalidArgumentError: ``value`` is one of
        :data:`_SCALAR_ITERABLES`, or anything else that decomposes into its
        own characters the same way those do -- see
        :func:`_decomposes_into_single_characters` for what that adds
    """
    if isinstance(value, _SCALAR_ITERABLES) or _decomposes_into_single_characters(value):
        raise InvalidArgumentError(
            f"{name} erwartet eine Liste, keine einzelne Zeichen- oder Bytefolge: "
            f"{value!r}. Zerlegt in ihre einzelnen Zeichen oder Bytes, würde sie "
            "andere Objekte treffen, als gemeint war."
        )


def apply_edits(
    client: "Client",
    layer_id: str,
    *,
    creates: Sequence[NewFeature] = (),
    updates: Sequence[FeatureUpdate] = (),
    deletes: Sequence[int] = (),
    repair_invalid: bool = False,
) -> EditResult:
    """
    Build the wire body for one batch and send it. What :meth:`hgis.layer.Layer.edit`
    delegates to; kept apart from :class:`hgis.layer.Layer` so that module stays
    about reading a layer's shape, not about the wire format of a write.

    :raises hgis.errors.InvalidArgumentError: ``creates``, ``updates`` or
        ``deletes`` is one of :data:`_SCALAR_ITERABLES`, or decomposes into
        its own characters the same way those do (see
        :func:`_decomposes_into_single_characters`) -- passed for
        ``deletes``, a ``str`` or ``bytes`` given as one fid would otherwise
        quietly reach the server as several
    :raises hgis.errors.ConflictError: a ``row_version`` no longer matches
    :raises hgis.errors.ApiError: 404 when an updated or deleted fid does not
        exist, 400 on an invalid value or geometry
    """
    for name, value in (("creates", creates), ("updates", updates), ("deletes", deletes)):
        _reject_scalar_iterable(name, value)

    # Placeholders private to this one call, negative so they can never collide
    # with a real fid (fids are assigned from 1 up). Not exposed to the caller:
    # the server's clientId is this function's implementation detail, not part
    # of this library's surface.
    client_ids = list(range(-1, -len(creates) - 1, -1))

    body: dict[str, Any] = {}
    if creates:
        body["creates"] = [
            {
                "clientId": client_id,
                "geometry": dict(new.geometry),
                **({"properties": dict(new.properties)} if new.properties is not None else {}),
            }
            for client_id, new in zip(client_ids, creates)
        ]
    if updates:
        body["updates"] = [
            {
                "fid": update.fid,
                "rowVersion": update.row_version,
                **({"geometry": dict(update.geometry)} if update.geometry is not None else {}),
                **(
                    {"properties": dict(update.properties)} if update.properties is not None else {}
                ),
            }
            for update in updates
        ]
    if deletes:
        body["deletes"] = [int(fid) for fid in deletes]
    if repair_invalid:
        body["repairInvalid"] = True

    if not body:
        # Nothing to send, so nothing to ask the server about either. This is
        # the one case where data_version and feature_count in the result are
        # not the real numbers -- there is nothing to report because nothing
        # happened, and finding out the real ones would cost a request this
        # no-op has no reason to make.
        return EditResult([], 0, 0, 0, 0)

    answer = client.apply_edits(layer_id, body)
    created_map: dict[str, int] = answer.get("createdFids") or {}
    created_fids = [created_map[str(client_id)] for client_id in client_ids]

    return EditResult(
        created_fids=created_fids,
        updated=answer.get("updated", 0),
        deleted=answer.get("deleted", 0),
        data_version=answer["dataVersion"],
        feature_count=answer["featureCount"],
    )
