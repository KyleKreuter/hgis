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
    Refuse any :class:`hgis.Client` built while a test in this module is
    running whose transport is not :class:`_ReadOnlyFloor` -- the moment it
    is built, before it can make its first request.

    An earlier version of this safety net read the file's own syntax tree
    and looked for ``hgis.connect(...)`` / ``hgis.Client(...)`` written out
    literally. Demonstrated against it: ``from hgis import connect as c``
    (an alias -- the check only recognised the name ``connect``) and
    ``getattr(hgis, "connect")(...)`` (reflection -- there is no call to
    ``connect`` in the syntax tree at all, only a call to ``getattr``) both
    reached a live, unguarded client without tripping it.

    This checks the effect instead of the syntax: every one of those paths,
    however written, calls the same :meth:`hgis.Client.__init__` -- there is
    exactly one constructor, and nothing a test's own code does downstream
    of it can be routed around. Two things this does *not* cover, named
    rather than left for the next person to assume are handled:

    * **Anything that runs at definition time rather than call time.** A
      ``client = hgis.connect(URL)`` written directly at this file's top
      level runs while pytest is still collecting this file -- before this
      fixture's setup has had any test to run around, and so before it has
      installed anything. The same is true, less obviously, of a decorator
      argument, a parameter default value, and a class body's own
      statements: all three run the moment the ``def``/``class`` statement
      itself does, not when whatever they decorate or default is later
      called. Demonstrated separately for all of these, and closed the
      other way this file already knows: see
      ``test_no_module_level_statement_in_this_file_builds_a_client``
      below, which reads the syntax tree for exactly what collection would
      run immediately, rather than trying to patch something that is not
      there yet to patch.
    * **``hgis.Client.__new__`` called directly**, with attributes set by
      hand instead of going through ``__init__`` at all. Not a plausible
      accident the way the two syntax tricks above are -- not something
      either check in this file is built to catch.

    Scoped to this module's own test run and undone in ``finally``, so it
    never reaches a test in another file.
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


def test_no_module_level_statement_in_this_file_builds_a_client() -> None:
    """
    The gap the fixture above names but does not close: its patch is
    installed by fixture *setup*, which pytest only runs around a test call.
    A ``client = hgis.connect(URL)`` written directly at this file's top
    level -- outside any function, a plausible slip while editing this file,
    not a deliberate bypass the way an aliased import or reflection is --
    would run while pytest is still collecting this module, before any
    fixture exists to catch it.

    Confirmed: with the fixture's patch installed by hand *before* import
    instead of relying on fixture setup, a module-level ``hgis.connect(...)``
    added to a copy of this file still reached a real, unguarded client --
    the same result as if no check existed at all.

    So this reads the syntax tree instead, and only for what collection
    would actually run *immediately* -- which is more than "module level"
    suggests. A plain function body is deferred and does not count here; the
    runtime check above already covers it regardless of the syntax used to
    reach it. But a decorator expression, a parameter default value, and a
    base class or keyword argument on a ``class`` statement all run at
    *definition* time, the moment the ``def``/``class`` statement itself
    runs -- and a class body is not deferred at all, unlike a function's: it
    executes immediately, as part of building the class's namespace, which
    is exactly how a class gets its attributes in the first place.
    Confirmed for all three: a client built in a decorator argument, in a
    default value, and as a bare class-body assignment all ran during
    import, before any fixture existed, and before this check's earlier,
    simpler version (which skipped a ``def``/``class`` node entirely) saw
    any of them.
    """
    import ast
    from pathlib import Path

    tree = ast.parse(Path(__file__).read_text(encoding="utf-8"), filename=__file__)

    def _name(node: ast.expr) -> str | None:
        if isinstance(node, ast.Name):
            return node.id
        if isinstance(node, ast.Attribute):
            return node.attr
        return None

    def _client_calls(node: ast.AST) -> list[ast.Call]:
        return [
            n
            for n in ast.walk(node)
            if isinstance(n, ast.Call) and _name(n.func) in ("connect", "Client")
        ]

    found: list[ast.Call] = []

    def visit(node: ast.AST) -> None:
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            # The decorator and every default run now, at the def statement
            # itself; the function's own body is deferred until it is
            # called, which the runtime check above already covers.
            for part in (*node.decorator_list, *node.args.defaults, *node.args.kw_defaults):
                if part is not None:
                    found.extend(_client_calls(part))
            return
        if isinstance(node, ast.ClassDef):
            # The decorator, every base class and every keyword argument
            # (a metaclass=... among them) run now too -- and unlike a
            # function, the class body itself is not deferred either: it
            # runs immediately, building the class's namespace.
            for part in (*node.decorator_list, *node.bases, *node.keywords):
                found.extend(_client_calls(part))
            for statement in node.body:
                visit(statement)
            return
        if isinstance(node, ast.Lambda):
            return  # a lambda's body is deferred until the lambda is called
        if isinstance(node, ast.Call) and _name(node.func) in ("connect", "Client"):
            found.append(node)
        for child in ast.iter_child_nodes(node):
            visit(child)

    for statement in tree.body:
        visit(statement)

    if found:
        pytest.fail(
            f"Zeile {found[0].lineno}: ein hgis.Client wird beim Einsammeln dieser "
            "Datei gebaut -- entweder auf Modulebene, oder in einem Dekorator, "
            "einem Standardwert oder einer Klassenkörper-Anweisung, die schon "
            "beim def/class-Statement laufen -- bevor irgendeine Fixture etwas "
            "patchen könnte."
        )


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
