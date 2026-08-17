"""
One batch of writes to a layer's objects, and what it reports back.

The counterpart to :mod:`hgis.query`: that module builds a restriction and
runs it late; this one builds a batch and sends it now. There is no lazy form
here on purpose -- a write that only takes effect when someone later iterates
it is the one shape this stage refuses to offer. See :meth:`hgis.layer.Layer.edit`.
"""

from __future__ import annotations

from collections.abc import Iterable, Iterator
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
    this whole check exists to catch. A custom class with only ``__iter__``
    defined, no ``__getitem__`` at all, is the same break by a third route --
    see :func:`_first_character_if_it_looks_like_one` for how that is caught
    too.

    Never raises: ``value`` is foreign code, its ``__getitem__``, ``__iter__``,
    ``__next__`` or ``__eq__`` can raise anything at all, and this function's
    job is to catch the classic scalar-passed-as-a-list mixup, not to referee
    whatever else foreign code does wrong. An exception from any of those
    says nothing reliable either way, so it is treated like every other
    inconclusive case here: not flagged.
    """
    try:
        return _first_character_if_it_looks_like_one(value)
    except Exception:
        return False


def _first_character_if_it_looks_like_one(value: Any) -> bool:
    """
    The check :func:`_decomposes_into_single_characters` guards against
    exceptions from; see there for why nothing here needs its own
    ``try``/``except``.

    Checks the effect, not the type. For ``str`` and everything that behaves
    like it, indexing one element (``value[0]``) and slicing that same one
    element (``value[0:1]``) come back equal, because there is no separate
    "character" type standing between the two. An actual collection never
    has that property -- ``[1, 2, 3][0]`` is ``1``, ``[1, 2, 3][0:1]`` is
    ``[1]``, and ``1 == [1]`` is false for every real collection, not only
    lists. Compared with ``is True`` rather than trusted as a plain bool,
    because a NumPy array (and a pandas ``Series``) answers ``==``
    element-wise: a single-element ``numpy.ndarray`` would otherwise pass
    this check by returning an array holding one ``True`` instead of the
    ``bool`` a real match returns, and be mistaken for one of these when it
    is only a collection with one entry.

    A ``TypeError`` here means ``value`` has no ``__getitem__`` at all --
    that alone does not mean it is safe: a custom class defining only
    ``__iter__`` decomposes exactly the same way ``str`` does, just without
    the index/slice pair above to compare. ``IndexError`` and ``KeyError``
    are different: they mean ``value`` *does* support ``__getitem__`` --
    empty sequences, and a ``dict`` (which has one, keyed rather than
    positional), both land here -- and neither is this bug, so both return
    False directly rather than falling through to the check below.

    The fallback distinguishes an **iterator** from an **iterable that is
    not one**. An iterator (a generator, or anything with ``__next__``)
    consumes itself as it is read, so peeking at its first element would
    silently drop that element for whatever reads ``value`` next -- left
    alone entirely, the same protection the index-based check above already
    gave a plain generator. An iterable that is *not* an iterator hands back
    a fresh iterator every time ``__iter__`` is called, so looking at its
    first element here does not touch ``value`` itself; anything else --
    not iterable at all -- is left alone the same way.

    Only ``str`` is checked for on this path, not the byte-value family
    :data:`_SCALAR_ITERABLES` already covers by type: there is no
    ``__iter__``-only stand-in for ``bytes`` in the standard library the way
    ``UserString`` stands in for ``str``, and reusing this shape for
    "iterates into plain ints" would also catch a real ``__iter__``-only
    collection of small integers, which must stay accepted.
    """
    try:
        first, one_slice = value[0], value[0:1]
    except TypeError:
        pass  # no __getitem__ at all -- try the Iterable fallback below
    except (IndexError, KeyError):
        return False  # __getitem__ exists; this particular lookup does not
    else:
        return (first == one_slice) is True

    if isinstance(value, Iterator) or not isinstance(value, Iterable):
        return False
    first = next(iter(value), None)
    return isinstance(first, str) and len(first) == 1


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
