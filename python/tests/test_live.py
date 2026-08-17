"""
The one test that needs a running server.

Everything else runs against stored responses, which proves the library reads
them correctly and proves nothing about whether the server still sends that
shape. This one closes that gap: it checks the paths, the parameter names and
the response fields against the real thing.

It skips itself when no server answers, so a checkout without a backend still
runs green.

    HGIS_URL=http://localhost:8080 python -m pytest        # with a server
    python -m pytest -m "not live"                         # without

**Read-only, structurally, not by convention.** ``HGIS_URL`` defaults to
``hgis.DEFAULT_BASE_URL`` -- typically the developer's own running app, real
project data, no throwaway database behind it. Before this stage, that was
harmless: the library had nothing but reads to offer, so nobody writing a
test here could reach for a write by accident. That stopped being true the
moment ``layer.delete()``, ``layer.edit()`` and the rest of the new surface
existed to reach for. :class:`_ReadOnlyFloor` below is what keeps that from
mattering: the ``live`` fixture hands out a client built on it, and every
non-GET call through that client fails loudly before it reaches the network
-- whether or not whoever adds the next test here knows any of this history.
"""

from __future__ import annotations

import os

import pytest

import hgis
from hgis.transport import DEFAULT_TIMEOUT, Response, Transport, default_transport

URL = os.environ.get("HGIS_URL", hgis.DEFAULT_BASE_URL)

pytestmark = pytest.mark.live


class _ReadOnlyFloor(Transport):
    """
    Wraps the real floor and refuses anything but GET -- unconditionally, not
    only for the paths :class:`hgis.client.RequestGuard` would refuse anyway.

    That distinction is the point: ``RequestGuard`` now *allows* real writes,
    so relying on it here would make this floor exactly as permissive as the
    library itself, which is precisely what must not be true for a client
    built against ``HGIS_URL``. See the module docstring.
    """

    def __init__(self, inner: Transport) -> None:
        self.inner = inner

    def request(
        self,
        method: str,
        url: str,
        json: object = None,
        timeout: float = DEFAULT_TIMEOUT,
        headers: dict[str, str] | None = None,
    ) -> Response:
        if method.upper() != "GET":
            raise AssertionError(
                f"test_live.py hat versucht zu schreiben: {method} {url}. Diese "
                "Testreihe läuft gegen einen echten Server und muss lesend "
                "bleiben -- siehe _ReadOnlyFloor im Modul-Docstring."
            )
        return self.inner.request(method, url, json=json, timeout=timeout, headers=headers)

    def events(self, url: str, *, headers=None, timeout=None):
        # A stream is always a GET (see hgis.client.Client.events), so there
        # is nothing here for this floor to refuse.
        return self.inner.events(url, headers=headers, timeout=timeout)


def test_the_read_only_floor_refuses_a_write() -> None:
    """
    Not conditional on a server answering, unlike the test below -- this has
    to hold in CI too, where nothing ever listens on ``HGIS_URL`` and the one
    real test always skips.
    """

    class _NeverCalled(Transport):
        def request(self, *args: object, **kwargs: object) -> Response:
            raise AssertionError("Der echte Boden wurde erreicht.")

        def events(self, *args: object, **kwargs: object):
            raise AssertionError("Der echte Boden wurde erreicht.")

    floor = _ReadOnlyFloor(_NeverCalled())

    with pytest.raises(AssertionError, match="test_live.py"):
        floor.request("POST", "http://x/api/projects/p/layers", json={"name": "x"})
    with pytest.raises(AssertionError, match="test_live.py"):
        floor.request("DELETE", "http://x/api/layers/x")


def test_the_read_only_floor_lets_reads_through() -> None:
    class _Answering(Transport):
        def request(self, method, url, json=None, timeout=DEFAULT_TIMEOUT, headers=None):
            return Response(200, "{}")

        def events(self, *args: object, **kwargs: object):
            raise AssertionError

    floor = _ReadOnlyFloor(_Answering())

    assert floor.request("GET", "http://x/api/projects").status == 200


@pytest.fixture(autouse=True, scope="module")
def _every_client_built_here_must_be_read_only():
    """
    Refuse any :class:`hgis.Client` built anywhere in this file's test run
    whose transport is not :class:`_ReadOnlyFloor` -- the moment it is
    built, before it can make its first request.

    An earlier version of this safety net read the file's own syntax tree
    and looked for ``hgis.connect(...)`` / ``hgis.Client(...)`` written out
    literally. Demonstrated against it: ``from hgis import connect as c``
    (an alias -- the check only recognised the name ``connect``) and
    ``getattr(hgis, "connect")(...)`` (reflection -- there is no call to
    ``connect`` in the syntax tree at all, only a call to ``getattr``) both
    reached a live, unguarded client without tripping it.

    This checks the effect instead of the syntax: every one of those paths,
    however written, ends at the same :meth:`hgis.Client.__init__` -- there
    is exactly one constructor, and nothing downstream of it can be routed
    around. Wrapping that one place catches all three, and anything else
    reflection could invent, without needing to have thought of it first.

    Scoped to this module and undone in ``finally``, so it never reaches a
    test in another file.
    """
    original_init = hgis.Client.__init__

    def checked_init(self: hgis.Client, *args: object, **kwargs: object) -> None:
        original_init(self, *args, **kwargs)
        floor = self._transport.inner  # RequestGuard.inner: what it wraps
        if not isinstance(floor, _ReadOnlyFloor):
            raise AssertionError(
                f"test_live.py hat einen hgis.Client gebaut, dessen Transport "
                f"nicht _ReadOnlyFloor(...) ist: {floor!r}. Diese Testreihe "
                "läuft gegen einen echten Server und muss lesend bleiben -- "
                "siehe den Modul-Docstring."
            )

    hgis.Client.__init__ = checked_init
    try:
        yield
    finally:
        hgis.Client.__init__ = original_init


def test_a_client_not_built_on_the_read_only_floor_is_refused() -> None:
    """
    The guard from the fixture above, proven directly and without depending
    on a running server: build a client the three ways the review tried --
    plain attribute access, an aliased import, and reflection -- and confirm
    each is refused before it can reach the network.
    """
    from hgis import connect as aliased_connect

    with pytest.raises(AssertionError, match="_ReadOnlyFloor"):
        hgis.connect(URL, timeout=5)
    with pytest.raises(AssertionError, match="_ReadOnlyFloor"):
        aliased_connect(URL, timeout=5)
    with pytest.raises(AssertionError, match="_ReadOnlyFloor"):
        getattr(hgis, "connect")(URL, timeout=5)


#: A layer worth testing against holds more than one page, so paging is
#: actually exercised, and fewer than the 100.000 ids the fids endpoint will
#: return, so asking for all of them is not an error by itself.
_ENOUGH_TO_PAGE = 1_000
_FID_CEILING = 100_000


def _test_subject(projects):
    """
    Pick a layer that exercises something.

    The smallest layer on a server is typically a drawing with one shape and
    no attributes, which would let this test pass without touching a filter or
    a second page. So: prefer the smallest layer that still needs paging, and
    fall back to the largest one there is.
    """
    candidates = []
    for project in projects:
        if project.feature_count == 0:
            continue
        for layer in project.layers():
            if layer.kind == "VECTOR" and layer.feature_count > 0:
                candidates.append((layer, project))

    pageable = [
        pair
        for pair in candidates
        if _ENOUGH_TO_PAGE < pair[0].feature_count <= _FID_CEILING
    ]
    if pageable:
        # Smallest of the ones that page: enough to be a real test, quick.
        ranked = sorted(pageable, key=lambda pair: pair[0].feature_count)
    else:
        # Nothing pages on this server, so take the largest there is.
        ranked = sorted(candidates, key=lambda pair: pair[0].feature_count, reverse=True)

    for layer, project in ranked:
        if layer.fields():  # a layer without attributes tests no filtering
            return layer, project
    return None, None


@pytest.fixture(scope="module")
def live() -> hgis.Client:
    """A client, or a skip when nothing is listening. Cannot write -- see _ReadOnlyFloor."""
    client = hgis.connect(URL, transport=_ReadOnlyFloor(default_transport()), timeout=5)
    try:
        client.projects()
    except hgis.HgisError as error:
        pytest.skip(f"Kein hGIS unter {URL}: {error}")
    return client


def test_the_library_matches_the_running_server(live: hgis.Client) -> None:
    """
    Walk the read paths against a real backend and check they agree.

    What this catches that the stored responses cannot: a renamed parameter, a
    moved path, a field that stopped travelling. The stored responses would
    keep passing through all of it.
    """
    projects = live.projects()
    assert projects, "Der Server hat keine Projekte; der Test braucht mindestens eines."

    layer, project = _test_subject(projects)
    if layer is None:
        pytest.skip("Kein Vektor-Layer mit Objekten und Feldern auf diesem Server.")

    # --- the layer answers what describe() needs --------------------------
    description = layer.describe()
    assert description.name == layer.name
    assert description.fields, "Ein Layer ohne Felder taugt nicht als Prüfstück."
    assert description.feature_count == layer.count()

    # --- filtering happens on the server ----------------------------------
    first = description.fields[0]
    restricted = layer.where(f'"{first.name}" IS NOT NULL')
    filled = restricted.count()
    assert 0 <= filled <= description.feature_count

    # The count and the fid list are two different endpoints answering the
    # same question. They have to agree, or one of them is being built wrongly.
    if filled <= _FID_CEILING:
        assert len(restricted.fids()) == filled

    # --- paging reaches the end -------------------------------------------
    # The layer was chosen to need more than one page, so this walks the
    # cursor rather than reading a single response.
    if description.feature_count <= _FID_CEILING:
        assert len(layer.fids()) == description.feature_count
    assert sum(1 for _ in layer.query()) == description.feature_count

    # --- a rectangle over the layer's own extent keeps everything ---------
    if layer.extent:
        min_lng, min_lat, max_lng, max_lat = layer.extent
        pad = 0.001
        covering = layer.bbox(min_lng - pad, min_lat - pad, max_lng + pad, max_lat + pad)
        assert covering.count() == description.feature_count

    # --- the server still refuses an unknown field, and still says which ---
    with pytest.raises(hgis.ApiError) as error:
        layer.where("dieses_feld_gibt_es_nicht = 1").count()
    message = str(error.value)
    assert "Unbekanntes Feld" in message
    assert first.name in message, "Die Fehlermeldung nennt die vorhandenen Felder nicht mehr."

    # --- reading the view state works and stays read-only -----------------
    view = project.view()
    assert view.basemap
    project.selection()  # no assertion: whatever the user last clicked is valid
