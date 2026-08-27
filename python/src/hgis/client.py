"""The connection, and the two lookups that start every session."""

from __future__ import annotations

import os
import re
import threading
import uuid
from typing import TYPE_CHECKING, Any, Callable, Iterable, Iterator, Mapping
from urllib.parse import urljoin, urlsplit

from .channel import ChannelItem
from .channel import wait_for as _wait_for
from .channel import watch as _watch
from .errors import (
    ApiError,
    ConflictError,
    GuardError,
    InvalidArgumentError,
    InvalidClientIdError,
    NotFoundError,
    TransportError,
    UnknownNameError,
)
from .transport import DEFAULT_TIMEOUT, Event, Response, Transport, build_url, default_transport

#: Where hGIS listens by default.
#:
#: Plain ``http``, and the assumption behind that is written down rather than
#: left to be inferred: hGIS is a local GIS, and this address is the loopback
#: interface, where there is no network segment for anyone to sit on.
#:
#: Point this at a host across a real network and that stops being true. Then
#: the traffic is readable and changeable in transit -- including the redirects
#: :class:`RequestGuard` has to reason about, which is why it refuses a hop
#: that leaves the origin. Use ``https://`` for anything that is not localhost.
DEFAULT_BASE_URL = "http://localhost:8080"

# --- naming this client ---------------------------------------------------
#
# The live channel (GET /api/events) reports that a project's working state
# changed, and repeats the name of whoever wrote it. A client that finds its own
# name there already knows the state and can leave the event alone. Without a
# name, it reads back what it just wrote.
#
# The header name is written once, here. It is still under review on the server
# side, so it has to be changeable in one place rather than five.

#: The header the server reads. See ClientId.HEADER in the backend.
CLIENT_HEADER = "X-Hgis-Client"

#: What the server accepts, mirrored from ClientId.ALLOWED. Checked here so a
#: bad name fails in Python rather than as a 400 on the first write.
_CLIENT_ID_PATTERN = re.compile(r"[A-Za-z0-9_-]{1,64}")

#: Environment variable for naming this process from outside.
CLIENT_ID_VARIABLE = "HGIS_CLIENT_ID"

#: The generated name, together with the process id it was made for.
#:
#: The pid is stored with it because of ``fork``: a forked child inherits the
#: parent's memory, so a name computed once at import is already there and this
#: module never runs again to make a new one. Four workers of a
#: ``multiprocessing.Pool`` then share one name, each takes the others' changes
#: for its own echo, and a real change is dropped in silence. Comparing the pid
#: is what makes the name belong to the process using it rather than to the one
#: that happened to import first.
_generated: tuple[int, str] | None = None

#: Guards the line above. Threads race for it exactly like processes do: several
#: can find it unset at once, each then computes its own name -- ``uuid4`` reads
#: the operating system's randomness and lets other threads run while it waits
#: -- and each writes over the last. Every one of them has already returned the
#: name it made, so one process ends up writing under several. Measured before
#: this lock existed: up to five names among thirty-two threads.
_lock = threading.Lock()


def _reset_after_fork() -> None:
    """
    Give the child its own lock and forget the parent's name.

    A lock held by some thread at the moment of the fork stays held forever in
    the child, because the thread that would release it does not exist there.
    The child gets a fresh one instead. Clearing the name is belt and braces:
    the pid comparison would catch it anyway, and saying it outright costs
    nothing.
    """
    global _lock, _generated
    _lock = threading.Lock()
    _generated = None


if hasattr(os, "register_at_fork"):  # not on Windows
    os.register_at_fork(after_in_child=_reset_after_fork)


def _check_client_id(client_id: str) -> str:
    """
    :raises InvalidClientIdError: naming the character set the server allows
    """
    stripped = client_id.strip()
    if not _CLIENT_ID_PATTERN.fullmatch(stripped):
        raise InvalidClientIdError(
            f"Ungültiger Client-Name: {client_id!r}. Erlaubt sind 1 bis 64 Zeichen "
            "aus Buchstaben, Ziffern, Bindestrich und Unterstrich."
        )
    return stripped


def default_client_id() -> str:
    """
    The name this process writes under.

    ``HGIS_CLIENT_ID`` when set, otherwise a random name belonging to this
    process. Set the variable when something outside needs to recognise this
    program's writes -- an agent runner naming its workers, say. Two programs
    running at once need two names.

    A name that came from the environment is left exactly as it is, across a
    fork included: it was chosen deliberately, and choosing is the caller's.
    It is checked on every read, because it can be changed after the client
    was built and an unusable name must not leave as a header.

    Safe to call from several threads: the generated name is computed once and
    every caller gets that one.

    :raises InvalidClientIdError: when ``HGIS_CLIENT_ID`` holds a name the
        server would refuse
    """
    from_environment = os.environ.get(CLIENT_ID_VARIABLE)
    if from_environment:
        return _check_client_id(from_environment)

    global _generated
    pid = os.getpid()
    with _lock:
        # Checked again in here, not only outside: without the second look, a
        # thread that computed while another held the lock would overwrite a
        # name already handed out.
        if _generated is None or _generated[0] != pid:
            _generated = (pid, f"hgis-python-{uuid.uuid4().hex[:12]}")
        return _generated[1]


#: Page size for the project browser. The server allows 1 to 100.
_PROJECT_PAGE_SIZE = 100

_UUID = r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"


class _Unset:
    """
    The default for a keyword that can also be set to ``None`` on purpose.

    Every optional argument on :meth:`Client.update_layer` except ``basemap``
    and ``basemap_opacity`` treats a plain ``None`` default as "leave this
    alone" -- there is no meaningful ``None`` of its own to confuse it with.
    Those two are the exception: a layer's basemap can be reset back to
    following its project's again, and the server tells that apart from
    "unchanged" exactly the way :meth:`Layer.set_style
    <hgis.layer.Layer.set_style>` already does for style -- by whether the
    key is present in the body at all, not by what it holds. ``None`` itself
    has to stay free to mean that reset, so it cannot double as the "not
    given" default the way it does everywhere else in this library. This
    class exists only to be that other default; see :data:`_UNSET`, its one
    instance.
    """

    def __repr__(self) -> str:
        return "<nicht angegeben>"


#: The one instance of :class:`_Unset` every default below uses.
_UNSET = _Unset()

#: The one path :meth:`RequestGuard.events` may open. See its docstring for
#: why this is a literal check rather than an entry in :data:`_ALLOWED`.
_EVENTS_PATH = "/api/events"

#: Every request this stage may make, as (method, path pattern).
#:
#: Reading is unrestricted -- a GET cannot destroy anything, and leaving it open
#: keeps the API explorable. Writing is this list: the saved view state
#: (:meth:`hgis.project.Project.select`); a project's own lifecycle -- create
#: (:meth:`Client.create_project`), change its own properties -- name,
#: description, basemap and where the map stands
#: (:meth:`hgis.project.Project.update`) -- and delete, for good
#: (:meth:`Client.delete_project`); a layer's lifecycle -- create, change,
#: delete (to the trash), restore, purge (out of the trash, for good); a
#: batch of object edits; a field's own create and delete; and pulling data
#: in -- inspecting a file or upload before anything is written
#: (:meth:`Client.inspect_import`), importing one into a new layer
#: (:meth:`Client.start_import`), and the same from the Geoportal Hamburg
#: instead of a file (:meth:`Client.start_geoportal_import`); one saved
#: feature split along a line, and several saved features merged into one
#: (:meth:`hgis.layer.Layer.split`, :meth:`hgis.layer.Layer.merge`); a
#: project's own duplicate, as a job (:meth:`Client.duplicate_project`, see
#: :meth:`hgis.project.Project.duplicate`); and a project's whole layer
#: stacking order, written wholesale (:meth:`Client.reorder_layers`, see
#: :meth:`hgis.project.Project.reorder_layers` and
#: :meth:`hgis.project.Project.move_layer` for moving one layer without
#: naming every other). Every other method or path is refused before it
#: reaches the network -- the maintenance endpoints among them, which have
#: no project, layer or object behind them and which no stage opens.
#:
#: A deleted project has no trash behind it, the same as a purged layer --
#: :meth:`Client.delete_project` is as final as :meth:`Client.purge_layer`
#: the moment it returns. What stands in front of it is not in this list, on
#: purpose: :meth:`hgis.mcp.write_tools.delete_project` requires the
#: project's name a second time, given literally, before it ever calls this;
#: a caller of the library itself carries that same responsibility, the
#: guard only ever having checked *where* a request goes.
#:
#: Deliberately still a list of (method, path pattern) rather than a generic
#: ``request(method, path, body)`` on :class:`Client`: the pattern only ever
#: checks *where* a request goes, never what its body contains, so a caller
#: who could name any of these paths directly could also send any body to it.
#: Each entry below therefore has exactly one named method on :class:`Client`
#: that builds its body, and that is the only way to reach it from this
#: library -- the same shape :meth:`Client.save_view_state` already had.
_ALLOWED: tuple[tuple[str, str], ...] = (
    ("GET", r".*"),
    ("PUT", rf"/api/projects/{_UUID}/view-state"),
    ("POST", r"/api/projects"),
    ("PATCH", rf"/api/projects/{_UUID}"),
    ("DELETE", rf"/api/projects/{_UUID}"),
    ("POST", rf"/api/projects/{_UUID}/layers"),
    ("POST", rf"/api/projects/{_UUID}/imports/inspect"),
    ("POST", rf"/api/projects/{_UUID}/imports"),
    ("POST", rf"/api/projects/{_UUID}/geoportal-imports"),
    ("PATCH", rf"/api/layers/{_UUID}"),
    ("DELETE", rf"/api/layers/{_UUID}"),
    ("POST", rf"/api/layers/{_UUID}/restore"),
    ("DELETE", rf"/api/layers/{_UUID}/purge"),
    ("POST", rf"/api/layers/{_UUID}/edits"),
    ("POST", rf"/api/layers/{_UUID}/fields"),
    ("DELETE", rf"/api/layers/{_UUID}/fields/{_UUID}"),
    # --- Paket A (Aufgabe 21): Objekt teilen und Objekte zusammenführen ---
    ("POST", rf"/api/layers/{_UUID}/features/\d+/split"),
    ("POST", rf"/api/layers/{_UUID}/features/merge"),
    # --- Paket B (Aufgabe 21): Projekt duplizieren, Layer neu ordnen ---
    ("POST", rf"/api/projects/{_UUID}/duplicate"),
    ("PUT", rf"/api/projects/{_UUID}/layers/order"),
)


def _check_allowed(method: str, url: str) -> None:
    """
    Refuse anything the list above does not name.

    :raises GuardError: naming the request and, in short, what this stage can
        do instead
    """
    path = urlsplit(url).path
    for allowed_method, pattern in _ALLOWED:
        if method.upper() == allowed_method and re.fullmatch(pattern, path):
            return
    raise GuardError(
        f"{method.upper()} {path} ist nicht vorgesehen. Erlaubt sind lesende "
        "Anfragen, project.select() und die Schreibwege dieser Stufe: ein Projekt "
        "anlegen oder endgültig löschen (client.create_project(), "
        "client.delete_project()), ein Projekt ändern (project.update()), ein "
        "Layer anlegen, ändern, löschen, wiederherstellen oder endgültig löschen "
        "(project.create_layer(), layer.update()/.delete()/.restore()/.purge()), "
        "ein Stapel Objekt-Änderungen (layer.edit(), layer.insert(), "
        "layer.update_feature(), layer.delete_features()), ein Objekt teilen "
        "oder mehrere zusammenführen (layer.split(), layer.merge()), ein Feld "
        "anlegen oder löschen (layer.create_field(), layer.delete_field()), "
        "Daten hereinholen (project.inspect_import(), project.import_file(), "
        "project.import_geoportal()), ein Projekt duplizieren "
        "(project.duplicate()) sowie die Layer-Reihenfolge eines Projekts "
        "neu setzen (project.reorder_layers(), project.move_layer())."
    )


#: Statuses that send a request somewhere else.
_REDIRECT_STATUS = frozenset({301, 302, 303, 307, 308})

#: How many hops to follow before giving up. A real API needs none of them.
_MAX_REDIRECTS = 5


def _method_after_redirect(status: int, method: str) -> str:
    """
    The method the next hop would use.

    303 always becomes GET. 301 and 302 become GET for anything but GET/HEAD,
    which is what every browser does. **307 and 308 keep both the method and
    the body** -- which is exactly what makes an unchecked redirect dangerous:
    a PUT stays a PUT, with its payload, and lands wherever it is sent.
    """
    if status in (307, 308):
        return method
    if status == 303:
        return "GET"
    return "GET" if method.upper() not in ("GET", "HEAD") else method


class RequestGuard(Transport):
    """
    Wraps a transport and lets only the allowed requests through.

    Sits between the client and the real floor, so every path into the network
    passes it -- including ``client._transport.request(...)``, which would walk
    straight past a check placed in :meth:`Client.get` alone.

    **Redirects are followed here, one hop at a time, and every hop is
    checked.** Letting the HTTP library follow them would undo the whole guard:
    it follows *inside* the single call that was checked once, so a permitted
    ``PUT .../view-state`` answered with a 307 would leave again as a
    ``PUT .../layers/order`` -- same method, same body, never checked, and the
    caller sees an ordinary success. That is not a hypothetical; it was
    demonstrated against this library.

    A hop may not change origin either. Without that rule, an injected redirect
    could send this request -- and the headers on it -- to another host.

    **This depends on the floor handing a redirect response back untouched --
    enforced for the one way that was found to fail, not guaranteed for
    every way it could.** A caller who hands
    :class:`hgis.transport.HttpxTransport` an ``httpx.Client`` configured with
    ``follow_redirects=True`` would put this class in exactly the position
    the paragraph above describes -- httpx resolving the whole chain *inside*
    the one call this loop checked once, so the loop never runs a second
    iteration and never sees where the request actually went. That
    configuration is refused, not merely discouraged: on every call
    :class:`hgis.transport.HttpxTransport` makes, not only when it is built,
    since ``follow_redirects`` is a plain attribute the caller can still flip
    afterwards on a client they own; see
    :class:`hgis.errors.UnsafeTransportError`.

    **A known gap, left open rather than hidden:** that check reads one
    attribute. A caller who instead plugs a custom ``httpx.BaseTransport``
    into ``httpx.Client(transport=...)`` -- official, public httpx API, the
    same extension point a retry, caching or auth wrapper would use -- and
    has *that* transport resolve the redirect internally puts this class
    back in the same position, with ``follow_redirects`` never touched and
    the check above never tripped. Demonstrated the same way the paragraph
    above was: a checked ``PUT`` -- full body, the client-name header
    included -- arrived unchecked at a second, forbidden host, while
    ``response.url``, ``response.history`` and ``response.request.url`` on
    the answer this loop received all still read back the *original* URL --
    the same values an ordinary, un-redirected answer would carry. There is
    nothing on the response this loop could check that would tell the two
    apart: ``httpx.BaseTransport`` is opaque by the design of the interface
    it implements, which is exactly what lets a legitimate transport add
    retries or caching without this class ever needing to know. Closing this
    would mean ``HttpxTransport`` refusing to build on a caller-supplied
    ``httpx.Client`` at all, giving up the connection-pool reuse that
    argument exists for -- a bigger trade than this stage has made. Named
    here so it is not mistaken for closed; see ``test_redirect.py`` for the
    demonstration kept as a test, not only as this paragraph.

    Substituting the *entire* floor with a caller's own
    :class:`~hgis.transport.Transport` is a different case, and does stay
    outside what either class can see, on purpose: ``HttpxTransport`` is not
    involved at all then, so there is no promise about it left to break.
    That is intent, not the accident the two paragraphs above guard against.

    **A third known gap, narrower than the two above:** :func:`_check_allowed`
    parses ``url`` with :func:`urllib.parse.urlsplit`, which is not the exact
    string that travels afterwards. ``urlsplit`` (hardened against
    CVE-2021-23336) strips ``\\t``, ``\\n`` and ``\\r`` from the *whole* URL,
    not only its edges, before splitting it -- so a path carrying one of
    those is checked here with the character already gone, while
    ``self.inner.request(...)`` two lines below still receives the original,
    unstripped ``url``. Measured: a literal path of
    ``/api/projects/{id}/layers\\norder`` -- ``\\n`` standing where the next
    ``/`` would be -- is read by this check as ``/api/projects/{id}/layers``,
    a permitted GET. Harmless today for a specific, checked reason rather
    than none: :class:`hgis.transport.HttpxTransport` builds on
    ``httpx.Client``, and ``httpx.URL(...)`` itself refuses a URL containing
    such a character with ``InvalidURL`` before anything is sent -- verified
    directly against httpx, not assumed. That refusal lives one floor below
    this class, though, in a library this class does not control; a future
    httpx version, or a :class:`~hgis.transport.Transport` substituted the
    way the paragraph above describes, is not bound by it. Named here for the
    same reason the two paragraphs above are: checked for the one way this
    was found to matter, not proven closed.

    It stops mistakes, not intent: writing to hGIS from Python needs nothing
    more than ``import httpx``. What it removes is the accidental write that
    this library itself would otherwise make easy -- a wider one now than the
    single view-state write this class was first built for, but the same
    property: every path into the network is named ahead of time, in
    :data:`_ALLOWED`, or it does not go.
    """

    def __init__(self, inner: Transport) -> None:
        self.inner = inner

    def request(
        self,
        method: str,
        url: str,
        json: Any = None,
        file: tuple[str, bytes] | None = None,
        timeout: float = DEFAULT_TIMEOUT,
        headers: dict[str, str] | None = None,
    ) -> Response:
        origin = _origin(url)

        for _ in range(_MAX_REDIRECTS + 1):
            _check_allowed(method, url)
            response = self.inner.request(
                method, url, json=json, file=file, timeout=timeout, headers=headers
            )
            if response.status not in _REDIRECT_STATUS:
                return response

            location = response.header("Location")
            if not location:
                # A redirect naming no target is not something to reason about.
                return response

            url = urljoin(url, location)
            if _origin(url) != origin:
                raise GuardError(
                    f"Die Umleitung führt zu einem anderen Server: {url}. "
                    "Das Programm folgt Umleitungen nur innerhalb desselben Servers."
                )

            next_method = _method_after_redirect(response.status, method)
            if next_method != method:
                # 301/302/303 turn this into a GET, so it is no longer a write.
                # Both the body and the client name belonged to the write and
                # do not travel on: the name says who is changing something,
                # and after this hop nobody is. An upload's file is exactly
                # as much a write payload as a JSON body -- dropped for the
                # same reason.
                json = None
                file = None
                headers = {
                    name: value
                    for name, value in (headers or {}).items()
                    if name.lower() != CLIENT_HEADER.lower()
                } or None
            method = next_method

        raise TransportError(
            f"Mehr als {_MAX_REDIRECTS} Umleitungen für {url}. Das Programm bricht ab."
        )

    def events(
        self,
        url: str,
        *,
        headers: dict[str, str] | None = None,
        timeout: float | None = None,
    ) -> Iterator[Event]:
        """
        The live channel, guarded -- but not by :data:`_ALLOWED` or by the
        per-hop redirect check :meth:`request` runs.

        Neither fits a stream. ``_ALLOWED`` would not add anything here: it
        already lets every GET through -- ``("GET", r".*")`` -- so checking a
        stream's path against it could never refuse one; a dedicated check
        against the one path this stage opens is the check that actually
        means something. And the redirect logic answers a question a stream
        does not ask: it exists so a checked *write*, once redirected, cannot
        leave again unchecked, and a stream is always a GET -- see
        :meth:`Client.events`, the only caller -- so there is no write for it
        to protect here. The browser floor already makes the same trade for
        reads: :class:`hgis.transport.PyodideTransport` cannot intercept a
        redirect at all, and relies on exactly this fact.

        That argument is why a *followed* redirect would be harmless here --
        it is not why one never happens. What actually keeps a redirect from
        being followed at all is one floor below this method, not in it:
        :meth:`hgis.transport.HttpxTransport.events` raises on any status
        but 200 before it ever reads ``Location``, so this method never even
        sees a 3xx to decide about. This class has no per-hop loop for
        ``events`` the way :meth:`request` has one -- there is nothing here
        that *would* follow one if the floor changed its mind. Worth naming
        because the GET argument above would keep reading as true even if
        that changed -- a floor that started following redirects on its own
        (a load balancer rewriting ``/api/events``, say) would restore
        exactly the risk :meth:`request`'s own docstring describes, and
        nothing in this method would notice, since :meth:`request`'s
        per-hop origin check has no counterpart here.

        What this still refuses, which a bare ``self.inner.events(...)``
        would not: a call whose *path* is not the one live channel this stage
        knows about, before anything reaches the network.
        """
        path = urlsplit(url).path
        if path != _EVENTS_PATH:
            raise GuardError(
                f"GET {path} ist als Ereignisstrom nicht vorgesehen. "
                f"Erlaubt ist nur {_EVENTS_PATH}."
            )
        return self.inner.events(url, headers=headers, timeout=timeout)


def _origin(url: str) -> tuple[str, str]:
    """Scheme and host:port -- what a redirect may not change."""
    parts = urlsplit(url)
    return (parts.scheme.lower(), parts.netloc.lower())


def _read_file(path: str) -> tuple[str, bytes]:
    """
    ``(filename, content)`` for :meth:`RequestGuard.request`'s ``file`` --
    read once, from the local filesystem of whoever runs this process. See
    :meth:`hgis.project.Project.import_file`'s own docstring for what that
    means when this process is an MCP server rather than the agent itself.

    :raises InvalidArgumentError: ``path`` cannot be opened -- wrapped
        rather than left as the bare :class:`OSError` Python would raise, so
        the message reads like every other one in this library instead of
        naming a class an agent has never heard of.
    """
    try:
        with open(path, "rb") as handle:
            return os.path.basename(path), handle.read()
    except OSError as error:
        raise InvalidArgumentError(f"Datei nicht lesbar: {path!r} ({error}).") from error


def connect(
    base_url: str = DEFAULT_BASE_URL,
    *,
    transport: Transport | None = None,
    timeout: float = DEFAULT_TIMEOUT,
    client_id: str | None = None,
) -> "Client":
    """
    Connect to an hGIS server.

    >>> import hgis
    >>> client = hgis.connect()
    >>> client = hgis.connect("http://localhost:8080")

    Nothing is sent here. The first request happens when you ask for something.

    :param base_url: where the server listens
    :param transport: substitute the HTTP floor, see :mod:`hgis.transport`
    :param timeout: seconds to wait per request
    :param client_id: the name this program writes under, so it can recognise
        its own change on the live channel. Defaults to ``HGIS_CLIENT_ID`` or a
        random per-process name. Give two programs running at once two names.
    """
    return Client(base_url, transport=transport, timeout=timeout, client_id=client_id)


class Client:
    """
    An hGIS server, reachable through the writes this stage names and nothing
    else.

    Enforced, not merely intended: the transport is wrapped in a
    :class:`RequestGuard`, so a request outside :data:`_ALLOWED` is refused
    before it is sent.
    """

    def __init__(
        self,
        base_url: str = DEFAULT_BASE_URL,
        *,
        transport: Transport | None = None,
        timeout: float = DEFAULT_TIMEOUT,
        client_id: str | None = None,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        # Checked now, not at the first write: a name that cannot travel is a
        # mistake in the calling program, and the first write may be minutes away.
        # A name given here is kept as given; without one, the name is looked up
        # per access so that a client built before a fork does not carry the
        # parent's name into the child. See :func:`default_client_id`.
        self._client_id = _check_client_id(client_id) if client_id is not None else None
        if self._client_id is None:
            # Reads the environment and checks it, so a bad HGIS_CLIENT_ID is
            # caught here rather than at the first write.
            default_client_id()
        floor = transport if transport is not None else default_transport()
        # Wrapped even when the caller supplied the floor: a substituted
        # transport is for testing or authentication, not for lifting the guard.
        self._transport = RequestGuard(floor)
        self._timeout = timeout

    @property
    def client_id(self) -> str:
        """
        The name this client writes under.

        Read rather than stored when it was not given explicitly, so that a
        client built before a ``fork`` writes under the child's name in the
        child. A name the caller passed in stays what the caller passed in.
        """
        return self._client_id if self._client_id is not None else default_client_id()

    def __repr__(self) -> str:
        return f"<hgis.Client {self.base_url} als {self.client_id}>"

    # --- the two lookups ---------------------------------------------------

    def projects(self) -> list["Project"]:
        """
        Every project on this server, most recently opened first.

        Pages through the browser's cursor, so a server with more than 100
        projects returns all of them.
        """
        from .project import Project

        items: list[dict[str, Any]] = []
        cursor: str | None = None
        while True:
            page = self.get(
                "/api/projects", limit=_PROJECT_PAGE_SIZE, cursor=cursor
            )
            items.extend(page["items"])
            cursor = page.get("nextCursor")
            if not cursor:
                break
        return [Project(self, item) for item in items]

    def project(self, name_or_id: str) -> "Project":
        """
        One project, by name or by id.

        The name is matched case-insensitively but in full: a near miss names
        the projects that exist rather than guessing which one was meant.

        :raises UnknownNameError: when no project has that name, or when two do
        """
        from .project import Project

        if _looks_like_id(name_or_id):
            return Project(self, self.get(f"/api/projects/{name_or_id}"))

        projects = self.projects()
        matches = [p for p in projects if p.name.casefold() == name_or_id.casefold()]
        if len(matches) == 1:
            return matches[0]
        if len(matches) > 1:
            ids = ", ".join(p.id for p in matches)
            raise UnknownNameError(
                f"Mehrere Projekte heißen {name_or_id!r}. "
                f"Verwenden Sie eine Kennung: {ids}."
            )
        raise UnknownNameError(
            f"Unbekanntes Projekt: {name_or_id}. "
            f"Verfügbar: {_names(p.name for p in projects)}."
        )

    def layer(self, layer_id: str) -> "Layer":
        """
        One layer by id, without going through its project.

        For picking up an id that was written down earlier -- by name, ask the
        project, since layer names are only unique within one.
        """
        from .layer import Layer

        return Layer(self, self.get(f"/api/layers/{layer_id}"))

    def basemaps(self) -> list["Basemap"]:
        """
        The basemap catalog -- every background map :meth:`hgis.project.Project.update`'s
        ``basemap`` and :meth:`hgis.layer.Layer.update`'s ``basemap`` accept as a
        catalog id, alongside the other case both also accept: an own tile URL
        template, with ``{z}``, ``{x}``, ``{y}``, for a service the catalog
        does not list.

        Reading is unrestricted, so this is one ordinary GET -- unlike
        :meth:`projects`, the catalog is small enough that the server does
        not page it.

        >>> catalog = client.basemaps()
        >>> [item.id for item in catalog if item.group == "Deutschland"]
        ['basemapde-grau', ...]
        """
        from .basemap import _to_basemap

        items = self.get("/api/basemaps")["basemaps"]
        return [_to_basemap(item) for item in items]

    # --- structural: splitting and merging (Aufgabe 21, Paket A) -----------

    def split_feature(
        self,
        layer_id: str,
        fid: int,
        line: Mapping[str, Any],
        *,
        row_version: str | None = None,
    ) -> Any:
        """
        Cut one saved feature along ``line``. See :meth:`hgis.layer.Layer.split`,
        which is how to call this.

        :param line: GeoJSON ``LineString`` or ``MultiLineString`` in
            EPSG:4326 -- the same shape ``shapely.geometry.mapping(line)``
            produces, so a caller doing the cut geometry with Shapely can pass
            its result straight through
        :param row_version: the fid's ``xmin`` as it was read; a mismatch
            raises :class:`hgis.errors.ConflictError`. Omitted, no conflict
            check is made -- the same rule :meth:`apply_edits` follows for an
            update.
        """
        body: dict[str, Any] = {"line": dict(line)}
        if row_version is not None:
            body["rowVersion"] = row_version
        return self._send("POST", f"/api/layers/{layer_id}/features/{fid}/split", json=body)

    def merge_features(
        self,
        layer_id: str,
        fids: Iterable[int],
        lead_fid: int,
        *,
        row_versions: Mapping[int, str] | None = None,
    ) -> Any:
        """
        Join several saved features into ``lead_fid``. See
        :meth:`hgis.layer.Layer.merge`, which is how to call this.

        :param row_versions: the ``xmin`` per fid, as it was read, keyed by
            fid. Converted here to the string keys the wire body needs --
            JSON object keys are always strings, and a caller of this library
            should not have to know that. A fid missing from the mapping
            skips its own conflict check.
        """
        body: dict[str, Any] = {"fids": [int(fid) for fid in fids], "leadFid": int(lead_fid)}
        if row_versions:
            body["rowVersions"] = {str(fid): version for fid, version in row_versions.items()}
        return self._send("POST", f"/api/layers/{layer_id}/features/merge", json=body)

    # --- HTTP --------------------------------------------------------------

    def get(self, path: str, **params: Any) -> Any:
        """
        GET one path and return the parsed body.

        Parameters whose value is None are dropped, so an unset filter or
        cursor simply does not travel.

        :raises ApiError: on any 4xx or 5xx, carrying the server's own message
        :raises TransportError: when no answer arrives at all
        """
        return self._send("GET", path, params=params)

    def create_project(
        self,
        name: str,
        *,
        description: str | None = None,
        srid: int | None = None,
        basemap: str | None = None,
    ) -> "Project":
        """
        Create a new, empty project -- an agent's own workspace, instead of
        writing into a project a person already has open. The other half of
        :meth:`delete_project`.

        Unlike :meth:`create_layer`, this returns a :class:`hgis.project.Project`
        directly rather than the raw body -- the same shape :meth:`project`
        already hands back, since there is no parent object to wrap it
        through the way :meth:`hgis.project.Project.create_layer` wraps this
        method's layer counterpart.

        >>> scratch = client.create_project("agent-scratch")
        >>> layer = scratch.create_layer("Punkte", "MULTIPOINT")
        ...
        >>> client.delete_project(scratch.id)

        :param srid: storage CRS, as an EPSG code, e.g. 25832. None falls
            back to the server's default (EPSG:25832), validated there
            against ``spatial_ref_sys`` rather than a fixed list. Fixed at
            creation -- there is no way to change it afterwards, the same
            gap :meth:`update_project` documents for its own ``srid``.
        """
        from .project import Project

        body: dict[str, Any] = {"name": name}
        if description is not None:
            body["description"] = description
        if srid is not None:
            body["srid"] = srid
        if basemap is not None:
            body["basemap"] = basemap
        data = self._send("POST", "/api/projects", json=body)
        return Project(self, data)

    def save_view_state(self, project_id: str, state: dict[str, Any]) -> None:
        """
        Write a project's saved view state.

        Named after what it does rather than after the HTTP verb it uses --
        the same reasoning behind every write method below. The state is the
        working state, never the data: which layer is active, and per layer
        its sort, query and selection. See :meth:`hgis.project.Project.select`,
        which is how to call this.

        Carries :data:`CLIENT_HEADER` with this client's name, so the live
        channel can report who wrote and this program can skip its own echo.

        :param state: the complete state; the endpoint replaces it wholesale
        """
        self._send("PUT", f"/api/projects/{project_id}/view-state", json=state)

    def update_project(
        self,
        project_id: str,
        *,
        name: str | None = None,
        description: str | None = None,
        basemap: str | None = None,
        basemap_opacity: float | None = None,
        center: Iterable[float] | None = None,
        zoom: float | None = None,
    ) -> Any:
        """
        Change a project's own properties -- name, description, basemap and
        where the map stands. See :meth:`hgis.project.Project.update`, which
        is how to call this.

        Every argument left at None is left as it stood -- ``srid`` is not
        among them; it is fixed at creation and this endpoint has no way to
        change it.

        :param center: (lng, lat) in EPSG:4326
        :param zoom: 0 to 24, checked by the server
        """
        body: dict[str, Any] = {}
        if name is not None:
            body["name"] = name
        if description is not None:
            body["description"] = description
        if basemap is not None:
            body["basemap"] = basemap
        if basemap_opacity is not None:
            body["basemapOpacity"] = basemap_opacity
        if center is not None:
            body["center"] = list(center)
        if zoom is not None:
            body["zoom"] = zoom
        return self._send("PATCH", f"/api/projects/{project_id}", json=body)

    def deletion_impact(self, project_id: str) -> Any:
        """
        What deleting this project would destroy, without destroying
        anything -- how many layers, how many objects across all of them.
        See :meth:`delete_project`.

        Reading is unrestricted, so this is not itself a checked write --
        the same ``GET`` :meth:`get` could make directly. Named as its own
        method because :meth:`hgis.mcp.write_tools.delete_project` calls it
        for exactly one reason, the same reason the UI's own delete dialog
        does: to say what was actually destroyed, not merely that something
        was.
        """
        return self.get(f"/api/projects/{project_id}/deletion-impact")

    def delete_project(self, project_id: str) -> None:
        """
        Permanently delete a project, with every layer and object in it.

        Not reversible through this library, or through the server: unlike
        :meth:`delete_layer`, there is no trash behind a project, so this is
        as final as :meth:`purge_layer` the moment it returns. See
        :meth:`deletion_impact` for what this would destroy before calling
        it, and :meth:`hgis.mcp.write_tools.delete_project` for the one
        safeguard this stage actually has: that tool refuses to call this at
        all unless the project's name is given a second time, literally.
        """
        self._send("DELETE", f"/api/projects/{project_id}")

    # --- layers --------------------------------------------------------

    def create_layer(
        self,
        project_id: str,
        name: str,
        geometry_type: str,
        *,
        fields: Iterable[tuple[str, str]] | None = None,
    ) -> Any:
        """
        Create an empty layer. See :meth:`hgis.project.Project.create_layer`,
        which is how to call this -- it returns a :class:`hgis.layer.Layer`
        instead of the raw body this hands back.

        :param geometry_type: MULTIPOINT, MULTILINESTRING, MULTIPOLYGON or
            GEOMETRY
        :param fields: (name, type) pairs to create alongside the layer, in
            this order. ``type`` is one of nine tokens -- TEXT, INTEGER,
            BIGINT, DOUBLE, NUMERIC, BOOLEAN, DATE, TIME, TIMESTAMP -- checked
            by the server, not here; an unknown one comes back naming itself.
        """
        body: dict[str, Any] = {"name": name, "geometryType": geometry_type}
        if fields:
            body["fields"] = [{"name": name, "type": type_} for name, type_ in fields]
        return self._send("POST", f"/api/projects/{project_id}/layers", json=body)

    def update_layer(
        self,
        layer_id: str,
        *,
        name: str | None = None,
        visible: bool | None = None,
        z_index: int | None = None,
        min_zoom: int | None = None,
        max_zoom: int | None = None,
        basemap: str | None | _Unset = _UNSET,
        basemap_opacity: float | None | _Unset = _UNSET,
    ) -> Any:
        """
        Change a layer's ordinary properties. See :meth:`hgis.layer.Layer.update`.

        ``name``, ``visible``, ``z_index``, ``min_zoom`` and ``max_zoom`` left
        at None are left as they stood -- none of the five has a meaningful
        None of its own. Style has its own call, :meth:`update_layer_style`
        -- see :meth:`hgis.layer.Layer.set_style`. Clip mode is still not
        part of this stage.

        :param basemap: catalog id (see :meth:`basemaps`) or own tile URL
            template overriding the project's basemap for this one layer --
            in one of two forms, with ``{z}``, ``{x}``, ``{y}`` (XYZ or
            WMTS) or with ``{bbox-epsg-3857}`` in their place instead (a WMS
            ``GetMap`` URL, see :func:`hgis.mcp.write_tools.set_basemap` for
            a full example). Left at the default (:data:`_UNSET`), the
            layer's basemap is unchanged; given explicitly as None, it is
            reset to follow its project's basemap again -- a third state
            plain None cannot serve as the default for, unlike every
            argument above
        :param basemap_opacity: the same for opacity, 0 to 1, independently
            of ``basemap`` -- a layer can override one without the other
        """
        body: dict[str, Any] = {}
        if name is not None:
            body["name"] = name
        if visible is not None:
            body["visible"] = visible
        if z_index is not None:
            body["zIndex"] = z_index
        if min_zoom is not None:
            body["minZoom"] = min_zoom
        if max_zoom is not None:
            body["maxZoom"] = max_zoom
        if not isinstance(basemap, _Unset):
            body["basemap"] = basemap
        if not isinstance(basemap_opacity, _Unset):
            body["basemapOpacity"] = basemap_opacity
        return self._send("PATCH", f"/api/layers/{layer_id}", json=body)

    def update_layer_style(self, layer_id: str, style: dict[str, Any] | None) -> Any:
        """
        Replace a layer's style wholesale. See :meth:`hgis.layer.Layer.set_style`,
        which builds ``style`` and is how to call this.

        Its own PATCH, carrying only the ``style`` key -- never combined with
        :meth:`update_layer`'s body, so a caller changing the ordinary
        properties never touches the style by accident, and the other way
        round. ``style=None`` still sends the key, as an explicit JSON null:
        that is what resets the layer to its default rendering on the
        server, as opposed to leaving the key out, which
        :meth:`update_layer` does and which means "unchanged".
        """
        return self._send("PATCH", f"/api/layers/{layer_id}", json={"style": style})

    def delete_layer(self, layer_id: str) -> Any:
        """
        Move a layer to its project's trash. See :meth:`hgis.layer.Layer.delete`.

        Reversible with :meth:`restore_layer`, until someone empties the trash
        with :meth:`purge_layer` -- the only one of these that actually
        destroys the data.

        :return: whatever the server answered with, unchanged -- as of this
            writing that is a trash-entry body (200): how many objects moved
            to the trash, when, by whom. See :meth:`hgis.layer.Layer.delete`,
            which turns it into a :class:`hgis.layer.TrashEntry`. Still
            handled defensively for an answer with no body (204) too, should
            this endpoint ever go back to one -- see that method's own
            docstring
        """
        return self._send("DELETE", f"/api/layers/{layer_id}")

    def restore_layer(self, layer_id: str) -> Any:
        """
        Bring a trashed layer back. See :meth:`hgis.layer.Layer.restore`.

        :return: whatever the server answered with, unchanged
        """
        return self._send("POST", f"/api/layers/{layer_id}/restore")

    def purge_layer(self, layer_id: str) -> Any:
        """
        Permanently delete a trashed layer and its data. See
        :meth:`hgis.layer.Layer.purge`.

        Not reversible. There is no trash behind this call, unlike
        :meth:`delete_layer` -- the name says so on purpose.

        :return: whatever the server answered with, unchanged -- as of this
            writing that is a trash-entry body (200), the same shape
            :meth:`delete_layer` answers with. Still handled defensively for
            an answer with no body (204) too: there is nothing this library
            could honestly report instead without either guessing or asking
            the server again beforehand, and a "before" read cannot be
            trusted for an operation whose whole point is to destroy what it
            named.
        """
        return self._send("DELETE", f"/api/layers/{layer_id}/purge")

    # --- objects ---------------------------------------------------------

    def apply_edits(self, layer_id: str, body: dict[str, Any]) -> Any:
        """
        Send one batch of creates, updates and deletes. See
        :func:`hgis.edits.apply_edits`, which builds ``body`` and is how to
        call this -- it also turns a 409 into
        :class:`hgis.errors.ConflictError` with the current row attached.
        """
        return self._send("POST", f"/api/layers/{layer_id}/edits", json=body)

    # --- fields ------------------------------------------------------------

    def create_field(self, layer_id: str, name: str, type: str) -> Any:
        """Add one attribute field. See :meth:`hgis.layer.Layer.create_field`."""
        return self._send("POST", f"/api/layers/{layer_id}/fields", json={
            "name": name, "type": type,
        })

    def delete_field(self, layer_id: str, field_id: str) -> None:
        """Delete one attribute field. See :meth:`hgis.layer.Layer.delete_field`."""
        self._send("DELETE", f"/api/layers/{layer_id}/fields/{field_id}")

    # --- imports -------------------------------------------------------

    def inspect_import(
        self,
        project_id: str,
        *,
        file_path: str | None = None,
        upload_id: str | None = None,
        srid: int | None = None,
        charset: str | None = None,
    ) -> Any:
        """
        Report what an import would produce, without producing anything.
        See :meth:`hgis.project.Project.inspect_import`, which is how to
        call this and wraps the answer in a
        :class:`~hgis.project.Inspection`.

        Exactly one of ``file_path``/``upload_id`` -- the server enforces
        that and names the mistake if this sends neither or both.
        ``upload_id`` re-inspects a file already sent by an earlier call,
        with a different ``srid`` or ``charset`` say, without sending it a
        second time; it comes from that earlier call's own ``uploadId``.
        Folgenlos either way: nothing is written, so this is safe to repeat.

        :param file_path: read once from the local filesystem of whoever
            runs this process -- see :meth:`start_import` for what that
            means when this process is an MCP server rather than the agent
            itself
        """
        params: dict[str, Any] = {}
        if upload_id is not None:
            params["uploadId"] = upload_id
        if srid is not None:
            params["srid"] = srid
        if charset is not None:
            params["charset"] = charset
        file = _read_file(file_path) if file_path is not None else None
        return self._send(
            "POST", f"/api/projects/{project_id}/imports/inspect", params=params, file=file
        )

    def start_import(
        self,
        project_id: str,
        *,
        file_path: str | None = None,
        upload_id: str | None = None,
        name: str | None = None,
        srid: int | None = None,
        charset: str | None = None,
    ) -> Any:
        """
        Start importing a file, or a previously inspected upload, into a
        new layer. See :meth:`hgis.project.Project.import_file`, which is
        how to call this and wraps the answer in a :class:`hgis.jobs.Job`.

        Answers as soon as the upload is known to be readable -- an
        unreadable file or an implausible CRS comes back as an ordinary
        :class:`~hgis.errors.ApiError` right here, not as a job that fails
        moments later. The writing itself keeps running on the server after
        this returns; see :class:`hgis.jobs.Job` for following it to
        completion instead of polling by hand.

        :param file_path: see :meth:`inspect_import`
        :param name: the new layer's name. None uses the uploaded file's own
            name, without its extension
        """
        params: dict[str, Any] = {}
        if upload_id is not None:
            params["uploadId"] = upload_id
        if name is not None:
            params["name"] = name
        if srid is not None:
            params["srid"] = srid
        if charset is not None:
            params["charset"] = charset
        file = _read_file(file_path) if file_path is not None else None
        return self._send(
            "POST", f"/api/projects/{project_id}/imports", params=params, file=file
        )

    def start_geoportal_import(
        self,
        project_id: str,
        dataset_id: str,
        *,
        bbox: Iterable[float] | None = None,
        fields: Iterable[str] | None = None,
        name: str | None = None,
    ) -> Any:
        """
        Start importing a dataset from the Geoportal Hamburg into a new
        layer -- the same job shape as :meth:`start_import`, from a network
        fetch instead of an upload. See
        :meth:`hgis.project.Project.import_geoportal`, which is how to call
        this and wraps the answer in a :class:`hgis.jobs.Job`.

        :param dataset_id: id from the Geoportal catalog, e.g.
            ``client.get("/api/geoportal/datasets")`` -- reading is
            unrestricted, and this stage has no dedicated method for that
            lookup yet
        :param bbox: (minLng, minLat, maxLng, maxLat) in EPSG:4326. None
            imports the whole dataset
        :param fields: technical field names to keep. None keeps every
            field the dataset has; its id field travels along regardless
        """
        body: dict[str, Any] = {"datasetId": dataset_id}
        if bbox is not None:
            body["bbox"] = list(bbox)
        if fields is not None:
            body["fields"] = list(fields)
        if name is not None:
            body["name"] = name
        return self._send("POST", f"/api/projects/{project_id}/geoportal-imports", json=body)

    # --- Paket 21-B: Projekt duplizieren, Layer neu ordnen -----------------

    def duplicate_project(self, project_id: str, *, name: str | None = None) -> Any:
        """
        Start copying a whole project into a new, independent one. See
        :meth:`hgis.project.Project.duplicate`, which wraps the answer in a
        :class:`hgis.jobs.Job` -- this hands back the raw job body, PENDING,
        the same shape :meth:`start_import` does for an import.

        :param name: the new project's name. None names it the way the
            duplicate button in the UI does -- ``"<Name> (Kopie)"``, then
            ``"<Name> (Kopie 2)"`` and so on -- chosen by the server, not here
        """
        body: dict[str, Any] = {}
        if name is not None:
            body["name"] = name
        return self._send("POST", f"/api/projects/{project_id}/duplicate", json=body)

    def reorder_layers(self, project_id: str, layer_ids_bottom_to_top: Iterable[str]) -> Any:
        """
        Write a whole project's layer stacking order in one call. See
        :meth:`hgis.project.Project.reorder_layers`, which is how to call
        this, and :meth:`hgis.project.Project.move_layer` for moving one
        layer without naming every other.

        :param layer_ids_bottom_to_top: every layer this project has, each
            exactly once, lowest first. The server refuses the write
            outright -- nothing changes -- for a list that leaves one out,
            names an unknown id, or repeats one; there is no partial
            reorder.
        """
        body = {"layerIdsBottomToTop": list(layer_ids_bottom_to_top)}
        return self._send("PUT", f"/api/projects/{project_id}/layers/order", json=body)

    # --- the live channel ----------------------------------------------

    def events(self, *, timeout: float | None = None) -> Iterator[Event]:
        """
        The live channel, ``GET /api/events``, as a stream of :class:`Event`.

        The raw primitive: one connection, the wire-format :class:`Event`
        exactly as the server sent it, nothing interpreted, nothing retried
        when it ends. That is deliberate -- it is the floor
        :meth:`watch` is built on, not a shortcut around it. Reach for this
        directly only when :meth:`watch`'s :class:`~hgis.channel.Change` /
        :class:`~hgis.channel.Connected` shape does not fit; everything else
        described there -- reconnecting after the server's own
        ``stream-timeout`` ends this cleanly, recognising the gap a reconnect
        opens -- has to be built again by hand on top of this method alone.

        >>> for event in client.events():
        ...     print(event.name, event.data)

        :raises TransportError: the connection cannot be opened, or the
            floor has none (:class:`hgis.transport.PyodideTransport`, so far)
        """
        url = build_url(self.base_url, "/api/events")
        return self._transport.events(
            url, timeout=timeout if timeout is not None else self._timeout
        )

    def watch(
        self,
        *,
        timeout: float | None = None,
        stop: threading.Event | None = None,
    ) -> Iterator[ChannelItem]:
        """
        The live channel, interpreted and reconnecting -- see
        :func:`hgis.channel.watch`, which this calls straight through to.

        The method to reach for by default; :meth:`events` is the raw form
        underneath it.
        """
        return _watch(self, timeout=timeout, stop=stop)

    def wait_for(
        self,
        predicate: Callable[[ChannelItem], bool],
        *,
        timeout: float | None = None,
        stop: threading.Event | None = None,
    ) -> ChannelItem | None:
        """
        Block on :meth:`watch` until ``predicate`` matches -- see
        :func:`hgis.channel.wait_for`, which this calls straight through to.
        """
        return _wait_for(self, predicate, timeout=timeout, stop=stop)

    def _send(
        self,
        method: str,
        path: str,
        params: dict[str, Any] | None = None,
        json: Any = None,
        file: tuple[str, bytes] | None = None,
    ) -> Any:
        url = build_url(self.base_url, path, params)
        # The one place that decides which requests carry the client name.
        # Only writes: a read produces no event, so there is no echo to
        # recognise and nothing for the server to pass on.
        headers = None if method.upper() == "GET" else {CLIENT_HEADER: self.client_id}
        response = self._transport.request(
            method, url, json=json, file=file, timeout=self._timeout, headers=headers
        )
        if response.status >= 400:
            raise _to_error(response, path)
        if not response.text.strip():
            # 204 No Content, which is what a successful PUT of the view state is.
            return None
        return response.json()


def _to_error(response: Response, path: str) -> ApiError:
    """
    Turn an error response into an exception that says what the server said.

    The backend answers RFC 7807: ``{type, title, status, detail}``. ``detail``
    is the sentence worth reading -- "Unbekanntes Feld: hoehe. Verfügbar: ..."
    -- and it is handed on unchanged. Only when the body is not a problem
    document at all does this write a message of its own, because then there is
    none to pass on.
    """
    detail = None
    title = None
    instance = None
    current = None
    try:
        body = response.json()
        if isinstance(body, dict):
            detail = body.get("detail")
            title = body.get("title")
            instance = body.get("instance")
            # Only a 409 carries this -- the row as it stands now, so a caller
            # can decide without a second request. See ConflictError.
            current = body.get("current")
    except Exception:
        pass

    if not detail:
        excerpt = response.text[:200].strip()
        detail = f"HTTP {response.status} für {path}" + (f": {excerpt}" if excerpt else "")

    if response.status == 409:
        return ConflictError(response.status, detail, title, instance, current)
    error_type = NotFoundError if response.status == 404 else ApiError
    return error_type(response.status, detail, title, instance)


def _looks_like_id(value: str) -> bool:
    """
    Whether this is a UUID rather than a name.

    Checked by shape, not by asking the server: a name that happens to parse as
    a UUID is not a case that occurs, while one round trip per lookup is a cost
    that always does.
    """
    parts = value.split("-")
    return (
        len(parts) == 5
        and [len(part) for part in parts] == [8, 4, 4, 4, 12]
        and all(character in "0123456789abcdefABCDEF" for character in value.replace("-", ""))
    )


def _names(names: Iterable[str]) -> str:
    """The available names, in the shape the server's own errors use."""
    listed = list(names)
    return ", ".join(listed) if listed else "keine"


if TYPE_CHECKING:
    # Type checkers only. The runtime imports sit inside the methods above,
    # which is what keeps client, project and layer out of an import cycle.
    from .basemap import Basemap
    from .layer import Layer
    from .project import Project
