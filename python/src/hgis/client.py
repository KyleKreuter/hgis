"""The connection, and the two lookups that start every session."""

from __future__ import annotations

import os
import re
import uuid
from typing import TYPE_CHECKING, Any, Iterable
from urllib.parse import urljoin, urlsplit

from .errors import (
    ApiError,
    InvalidClientIdError,
    NotFoundError,
    ReadOnlyError,
    TransportError,
    UnknownNameError,
)
from .transport import DEFAULT_TIMEOUT, Response, Transport, build_url, default_transport

#: Where hGIS listens by default.
#:
#: Plain ``http``, and the assumption behind that is written down rather than
#: left to be inferred: hGIS is a local GIS, and this address is the loopback
#: interface, where there is no network segment for anyone to sit on.
#:
#: Point this at a host across a real network and that stops being true. Then
#: the traffic is readable and changeable in transit -- including the redirects
#: :class:`ReadOnlyGuard` has to reason about, which is why it refuses a hop
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

#: This process's name, made once at import.
#:
#: Random rather than fixed, and that is the point: two agents running at the
#: same time under one name would each take the other's change for their own
#: echo and ignore it. A real change would then be swallowed, which is worse
#: than processing one twice.
_PROCESS_CLIENT_ID = f"hgis-python-{uuid.uuid4().hex[:12]}"


def default_client_id() -> str:
    """
    The name this process writes under.

    ``HGIS_CLIENT_ID`` when set, otherwise a random per-process name. Set the
    variable when something outside needs to recognise this program's writes --
    an agent runner naming its workers, say. Keep it different per process.
    """
    return os.environ.get(CLIENT_ID_VARIABLE) or _PROCESS_CLIENT_ID


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


#: Page size for the project browser. The server allows 1 to 100.
_PROJECT_PAGE_SIZE = 100

_UUID = r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"

#: Every request this stage may make, as (method, path pattern).
#:
#: Reading is unrestricted -- a GET cannot destroy anything, and leaving it open
#: keeps the API explorable. Writing is one entry long: the saved view state,
#: which is what :meth:`hgis.project.Project.select` writes. Every other method
#: is refused before it reaches the network.
#:
#: The backend has endpoints for deleting a layer, deleting a project and
#: applying a batch of edits, and a generic ``put(path, body)`` would reach all
#: three. There is no recycle bin behind them yet, so a request sent by mistake
#: cannot be taken back. This list is what makes such a request a Python error
#: instead of a lost layer.
_ALLOWED: tuple[tuple[str, str], ...] = (
    ("GET", r".*"),
    ("PUT", rf"/api/projects/{_UUID}/view-state"),
)


def _check_allowed(method: str, url: str) -> None:
    """
    Refuse anything the list above does not name.

    :raises ReadOnlyError: naming the request and the one write that is allowed
    """
    path = urlsplit(url).path
    for allowed_method, pattern in _ALLOWED:
        if method.upper() == allowed_method and re.fullmatch(pattern, path):
            return
    raise ReadOnlyError(
        f"Diese Stufe der Bibliothek liest nur. {method.upper()} {path} ist nicht "
        "vorgesehen. Erlaubt sind lesende Anfragen und das Speichern der Auswahl "
        "über project.select()."
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


class ReadOnlyGuard(Transport):
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

    It stops mistakes, not intent: writing to hGIS from Python needs nothing
    more than ``import httpx``. What it removes is the accidental write that
    this library itself would otherwise make easy.
    """

    def __init__(self, inner: Transport) -> None:
        self.inner = inner

    def request(
        self,
        method: str,
        url: str,
        json: Any = None,
        timeout: float = DEFAULT_TIMEOUT,
        headers: dict[str, str] | None = None,
    ) -> Response:
        origin = _origin(url)

        for _ in range(_MAX_REDIRECTS + 1):
            _check_allowed(method, url)
            response = self.inner.request(
                method, url, json=json, timeout=timeout, headers=headers
            )
            if response.status not in _REDIRECT_STATUS:
                return response

            location = response.header("Location")
            if not location:
                # A redirect naming no target is not something to reason about.
                return response

            url = urljoin(url, location)
            if _origin(url) != origin:
                raise ReadOnlyError(
                    f"Die Umleitung führt zu einem anderen Server: {url}. "
                    "Das Programm folgt Umleitungen nur innerhalb desselben Servers."
                )

            next_method = _method_after_redirect(response.status, method)
            if next_method != method:
                # The body belonged to the old method; it does not travel on.
                json = None
            method = next_method

        raise TransportError(
            f"Mehr als {_MAX_REDIRECTS} Umleitungen für {url}. Das Programm bricht ab."
        )


def _origin(url: str) -> tuple[str, str]:
    """Scheme and host:port -- what a redirect may not change."""
    parts = urlsplit(url)
    return (parts.scheme.lower(), parts.netloc.lower())


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
    An hGIS server, reachable and read-only.

    Read-only is enforced, not merely intended: the transport is wrapped in a
    :class:`ReadOnlyGuard`, so a request that would change data is refused
    before it is sent. See :data:`_ALLOWED`.
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
        self.client_id = _check_client_id(
            client_id if client_id is not None else default_client_id()
        )
        floor = transport if transport is not None else default_transport()
        # Wrapped even when the caller supplied the floor: a substituted
        # transport is for testing or authentication, not for lifting the guard.
        self._transport = ReadOnlyGuard(floor)
        self._timeout = timeout

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

    def save_view_state(self, project_id: str, state: dict[str, Any]) -> None:
        """
        Write a project's saved view state. The one write this stage makes.

        Named after what it does rather than after the HTTP verb it uses. A
        generic ``put(path, body)`` would be a way to reach every writing
        endpoint the backend has -- layer deletion among them -- and naming the
        one operation instead removes that reach without taking anything away.

        The state is the working state, never the data: which layer is active,
        and per layer its sort, query and selection. See
        :meth:`hgis.project.Project.select`, which is how to call this.

        Carries :data:`CLIENT_HEADER` with this client's name, so the live
        channel can report who wrote and this program can skip its own echo.

        :param state: the complete state; the endpoint replaces it wholesale
        """
        self._send("PUT", f"/api/projects/{project_id}/view-state", json=state)

    def _send(
        self,
        method: str,
        path: str,
        params: dict[str, Any] | None = None,
        json: Any = None,
    ) -> Any:
        url = build_url(self.base_url, path, params)
        # The one place that decides which requests carry the client name.
        # Only writes: a read produces no event, so there is no echo to
        # recognise and nothing for the server to pass on.
        headers = None if method.upper() == "GET" else {CLIENT_HEADER: self.client_id}
        response = self._transport.request(
            method, url, json=json, timeout=self._timeout, headers=headers
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
    try:
        body = response.json()
        if isinstance(body, dict):
            detail = body.get("detail")
            title = body.get("title")
            instance = body.get("instance")
    except Exception:
        pass

    if not detail:
        excerpt = response.text[:200].strip()
        detail = f"HTTP {response.status} für {path}" + (f": {excerpt}" if excerpt else "")

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
    from .layer import Layer
    from .project import Project
