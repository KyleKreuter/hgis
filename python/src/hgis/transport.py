"""
The only place in this library that speaks HTTP.

Two floors sit under one synchronous interface:

* :class:`HttpxTransport` for CPython, on ``httpx``.
* :class:`PyodideTransport` for the browser, on a synchronous
  ``XMLHttpRequest``. Pyodide has no sockets, so ``httpx`` can only run
  asynchronously there.

The public interface is synchronous on purpose. Making every call ``await`` in
order to serve the browser would put an ``await`` in front of every line an
agent writes, and that is the single easiest thing to get wrong.

Everything above this module -- client, project, layer, query -- goes through
:meth:`Transport.request` and imports no HTTP library of its own. A test walks
the package's syntax tree and fails if any other module imports ``httpx``,
``urllib.request``, ``requests`` or ``socket``, so this is checked, not merely
intended.
"""

from __future__ import annotations

import json as jsonlib
import sys
from dataclasses import dataclass
from dataclasses import field as dataclass_field
from typing import Any, Iterable, Iterator
from urllib.parse import urlencode  # string work only, no sockets; runs in Pyodide

from .errors import TransportError

DEFAULT_TIMEOUT = 30.0


@dataclass(frozen=True)
class Response:
    """
    What every floor returns: a status, a body and the response headers.

    Headers are part of it because a redirect has to be readable: the floors do
    not follow one by themselves, so whoever called has to see the ``Location``
    and decide. See :class:`hgis.client.RequestGuard`.
    """

    status: int
    text: str
    headers: dict[str, str] = dataclass_field(default_factory=dict)

    def header(self, name: str) -> str | None:
        """One response header, matched case-insensitively as HTTP requires."""
        wanted = name.lower()
        for key, value in self.headers.items():
            if key.lower() == wanted:
                return value
        return None

    def json(self) -> Any:
        """
        The body as parsed JSON.

        :raises TransportError: when the body is not JSON. That means something
            other than the API answered -- a proxy, a login page -- and saying so
            is more useful than a decoder error from deep inside the stack.
        """
        try:
            return jsonlib.loads(self.text)
        except ValueError as error:
            excerpt = self.text[:200]
            raise TransportError(
                f"Die Antwort ist kein JSON (Status {self.status}): {excerpt}"
            ) from error


@dataclass(frozen=True)
class Event:
    """
    One dispatched Server-Sent Event, from the stream :meth:`Transport.events`
    opens.

    A comment line (``: ...``, used for the greeting and the heartbeat, see the
    backend's ``EventStreams``) and a bare ``retry:`` never become one of
    these -- see :func:`_parse_sse`. Only a block that actually carried a
    ``data:`` field is dispatched, which is what keeps a caller from having to
    filter the connection's own housekeeping out of the events it asked for.

    :param name: the SSE ``event:`` field, e.g. ``"project-view-state"``.
        ``"message"`` when the server sent none -- the default the spec itself
        assigns, not a value this library invents.
    :param data: the ``data:`` field, joined with ``\\n`` when it spanned
        several lines. JSON on this channel, so ``json.loads(event.data)`` is
        the usual next step.
    :param id: the SSE ``id:`` field, or None when absent
    """

    name: str
    data: str
    id: str | None = None


def _parse_sse(lines: Iterable[str]) -> Iterator[Event]:
    """
    Turn the raw lines of an SSE body into :class:`Event`.

    One floor-independent function rather than one parser per floor, the same
    reasoning as :func:`build_url`: two implementations of the same eight-line
    state machine are two places a fix has to land twice.

    Follows the WHATWG event-stream grammar, as far as this library needs it:
    a line starting with ``:`` is a comment and carries nothing; ``field:
    value`` sets that field, with a single leading space in ``value`` dropped;
    a blank line dispatches whatever was collected so far -- but **only when a
    ``data:`` line was among it**. A block of only ``retry:`` or only a
    comment (the connection's greeting, and every heartbeat) never dispatches;
    that is what keeps this library's own housekeeping bytes out of the events
    a caller iterates.
    """
    event_type: str | None = None
    data_lines: list[str] = []
    event_id: str | None = None

    for line in lines:
        if line == "":
            if data_lines:
                yield Event(name=event_type or "message", data="\n".join(data_lines), id=event_id)
            event_type, data_lines, event_id = None, [], None
            continue
        if line.startswith(":"):
            continue  # a comment: the greeting, or a heartbeat -- never dispatched

        field_name, separator, value = line.partition(":")
        if not separator:
            continue  # a field with no colon at all; the spec says ignore it
        if value.startswith(" "):
            value = value[1:]

        if field_name == "event":
            event_type = value
        elif field_name == "data":
            data_lines.append(value)
        elif field_name == "id":
            event_id = value
        # "retry" and anything else: not surfaced by this stage.


def build_url(base_url: str, path: str, params: dict[str, Any] | None = None) -> str:
    """
    Assemble one absolute URL.

    Lives here so both floors encode a query the same way; getting a bbox or an
    umlaut in a filter through differently on the two would be a difference
    nobody looks for.

    A parameter whose value is None is left out entirely -- that is how an unset
    filter, sort or cursor disappears instead of travelling as the string
    "None". A list becomes a repeated parameter, which is what Spring binds to
    an array.
    """
    url = base_url.rstrip("/") + "/" + path.lstrip("/")
    if not params:
        return url

    pairs: list[tuple[str, str]] = []
    for key, value in params.items():
        if value is None:
            continue
        if isinstance(value, bool):
            # Python prints True; Spring reads true.
            pairs.append((key, "true" if value else "false"))
        elif isinstance(value, (list, tuple)):
            pairs.extend((key, str(item)) for item in value)
        else:
            pairs.append((key, str(value)))

    if not pairs:
        return url
    return url + "?" + urlencode(pairs)


class Transport:
    """
    One request, one answer, synchronously.

    The seam between this library and the network. Substitute it to test without
    a server, to add authentication, or to run somewhere neither floor below
    fits.
    """

    def request(
        self,
        method: str,
        url: str,
        json: Any = None,
        timeout: float = DEFAULT_TIMEOUT,
        headers: dict[str, str] | None = None,
    ) -> Response:
        """
        Perform one HTTP request.

        :param method: GET, PUT, POST ...
        :param url: absolute URL, query included
        :param json: body to send as ``application/json``, or None
        :param timeout: seconds to wait
        :param headers: extra request headers, or None
        :raises TransportError: when no answer arrives. A 4xx or 5xx *is* an
            answer and comes back as a :class:`Response`; turning it into an
            error is the client's job, because only the client knows the
            server's error format.
        """
        raise NotImplementedError

    def events(
        self,
        url: str,
        *,
        headers: dict[str, str] | None = None,
        timeout: float | None = None,
    ) -> Iterator[Event]:
        """
        Open a stream of Server-Sent Events and return an iterator over them.

        The second form this seam offers, next to :meth:`request`. One
        :class:`Response` cannot stand for ``/api/events``: that connection
        stays open and pushes events for as long as the caller keeps reading,
        so this hands back one :class:`Event` at a time instead of one answer
        for the whole exchange. The request-response form above is unchanged.

        Laid down in this stage as groundwork; nothing in this library reads
        from it yet -- see :meth:`Client.events`.

        :param timeout: how long to wait for the *connection* to open. Once
            open, the stream is meant to run for as long as the caller reads
            it, so this is not a per-event timeout.
        :raises TransportError: the connection cannot be opened at all, or
            answers with anything but 200
        """
        raise NotImplementedError


class HttpxTransport(Transport):
    """The CPython floor. Needs ``httpx``."""

    def __init__(self, client: Any = None) -> None:
        """
        :param client: an ``httpx.Client`` to reuse. One is created when None,
            which keeps the connection pool alive across requests -- the
            difference between one TCP handshake and one per page while paging
            through a large layer.
        """
        if client is None:
            try:
                import httpx
            except ImportError as error:
                raise MissingHttpLibrary(
                    "httpx ist nicht installiert. Installieren Sie es mit: "
                    "pip install 'hgis[http]' oder pip install httpx"
                ) from error
            # follow_redirects stays off, and that is a security property rather
            # than a preference: httpx would follow a redirect *inside* this one
            # call, so a request checked once could leave as a second, unchecked
            # one -- and 307/308 keep the method and the body while doing it.
            # ReadOnlyGuard follows redirects itself, checking each hop.
            client = httpx.Client(follow_redirects=False)
        self._client = client

    def request(
        self,
        method: str,
        url: str,
        json: Any = None,
        timeout: float = DEFAULT_TIMEOUT,
        headers: dict[str, str] | None = None,
    ) -> Response:
        import httpx

        try:
            response = self._client.request(
                method, url, json=json, timeout=timeout, headers=headers
            )
        except httpx.HTTPError as error:
            raise TransportError(f"{url} ist nicht erreichbar: {error}") from error
        return Response(response.status_code, response.text, dict(response.headers))

    def events(
        self,
        url: str,
        *,
        headers: dict[str, str] | None = None,
        timeout: float | None = None,
    ) -> Iterator[Event]:
        import httpx

        try:
            with self._client.stream("GET", url, headers=headers, timeout=timeout) as response:
                if response.status_code != 200:
                    raise TransportError(
                        f"{url} antwortet mit Status {response.status_code} statt 200."
                    )
                yield from _parse_sse(response.iter_lines())
        except httpx.HTTPError as error:
            raise TransportError(f"{url} ist nicht erreichbar: {error}") from error

    def close(self) -> None:
        """Release the connection pool."""
        self._client.close()


class PyodideTransport(Transport):
    """
    The browser floor: a synchronous ``XMLHttpRequest``.

    Not exercised by the test suite -- there is no browser in it. It is written
    out rather than stubbed so the seam it hangs on is real, and so the work
    left for the editor stage is to run it, not to design it.

    A synchronous XHR blocks the tab it runs in. That is the price of a
    synchronous interface in the browser, and it is the reason Pyodide is
    normally driven from a web worker, where blocking costs nothing.

    ``timeout`` is accepted and ignored: XMLHttpRequest only honours its
    ``timeout`` property in asynchronous mode.

    **Redirects cannot be intercepted here.** XMLHttpRequest follows them by
    itself and offers no way to turn that off, so the per-hop check that
    :class:`hgis.client.ReadOnlyGuard` performs on CPython does not apply in
    the browser. What limits the damage there is the browser itself: a page can
    only reach its own origin unless the server allows otherwise, and this
    library talks to the server that served the page. Worth knowing before this
    floor is put to use.
    """

    def request(
        self,
        method: str,
        url: str,
        json: Any = None,
        timeout: float = DEFAULT_TIMEOUT,
        headers: dict[str, str] | None = None,
    ) -> Response:
        try:
            from js import XMLHttpRequest  # type: ignore[import-not-found]
        except ImportError as error:
            raise TransportError(
                "PyodideTransport läuft nur im Browser. Außerhalb davon "
                "verwenden Sie HttpxTransport."
            ) from error

        request = XMLHttpRequest.new()
        request.open(method, url, False)  # False: synchronous
        for name, value in (headers or {}).items():
            request.setRequestHeader(name, value)
        body = None
        if json is not None:
            request.setRequestHeader("Content-Type", "application/json")
            body = jsonlib.dumps(json)

        try:
            request.send(body)
        except Exception as error:  # a JsException; the name is not importable here
            raise TransportError(f"{url} ist nicht erreichbar: {error}") from error

        return Response(request.status, request.responseText,
                        _parse_headers(request.getAllResponseHeaders()))

    def events(
        self,
        url: str,
        *,
        headers: dict[str, str] | None = None,
        timeout: float | None = None,
    ) -> Iterator[Event]:
        """
        Not implemented on this floor yet.

        A synchronous ``XMLHttpRequest`` cannot be read incrementally the way
        this needs -- it hands back the body only once the connection is
        finished, which a live channel by definition never is. The browser
        binding for this belongs to the stage that actually uses the channel,
        not this one; see the module docstring for ``request``'s equivalent
        limits on this floor.
        """
        raise TransportError(
            "Der Ereigniskanal läuft in dieser Stufe nur unter CPython. "
            "PyodideTransport hat noch keine Anbindung dafür."
        )


class MissingHttpLibrary(TransportError):
    """No HTTP library for this interpreter. The message names what to install."""


def _parse_headers(raw: str | None) -> dict[str, str]:
    """
    ``getAllResponseHeaders()`` returns one CRLF-separated block; split it.

    Kept next to the browser floor rather than inside it so it can be read and
    tested without a browser.
    """
    headers: dict[str, str] = {}
    for line in (raw or "").splitlines():
        name, separator, value = line.partition(":")
        if separator:
            headers[name.strip()] = value.strip()
    return headers


def in_pyodide() -> bool:
    """Whether this interpreter is Pyodide, i.e. compiled to WebAssembly."""
    return sys.platform == "emscripten"


def default_transport() -> Transport:
    """
    The floor that fits this interpreter.

    Chosen when a client is built rather than at import time, so importing the
    package never depends on ``httpx`` being present -- describe(), the errors
    and the query builder all work without it.
    """
    return PyodideTransport() if in_pyodide() else HttpxTransport()
