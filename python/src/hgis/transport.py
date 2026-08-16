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
from typing import Any
from urllib.parse import urlencode  # string work only, no sockets; runs in Pyodide

from .errors import TransportError

DEFAULT_TIMEOUT = 30.0


@dataclass(frozen=True)
class Response:
    """What every floor returns: a status and a body, nothing driver-specific."""

    status: int
    text: str

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
    ) -> Response:
        """
        Perform one HTTP request.

        :param method: GET, PUT, POST ...
        :param url: absolute URL, query included
        :param json: body to send as ``application/json``, or None
        :param timeout: seconds to wait
        :raises TransportError: when no answer arrives. A 4xx or 5xx *is* an
            answer and comes back as a :class:`Response`; turning it into an
            error is the client's job, because only the client knows the
            server's error format.
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
            client = httpx.Client(follow_redirects=True)
        self._client = client

    def request(
        self,
        method: str,
        url: str,
        json: Any = None,
        timeout: float = DEFAULT_TIMEOUT,
    ) -> Response:
        import httpx

        try:
            response = self._client.request(method, url, json=json, timeout=timeout)
        except httpx.HTTPError as error:
            raise TransportError(f"{url} ist nicht erreichbar: {error}") from error
        return Response(response.status_code, response.text)

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
    """

    def request(
        self,
        method: str,
        url: str,
        json: Any = None,
        timeout: float = DEFAULT_TIMEOUT,
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
        body = None
        if json is not None:
            request.setRequestHeader("Content-Type", "application/json")
            body = jsonlib.dumps(json)

        try:
            request.send(body)
        except Exception as error:  # a JsException; the name is not importable here
            raise TransportError(f"{url} ist nicht erreichbar: {error}") from error

        return Response(request.status, request.responseText)


class MissingHttpLibrary(TransportError):
    """No HTTP library for this interpreter. The message names what to install."""


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
