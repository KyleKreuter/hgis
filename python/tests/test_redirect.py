"""
Redirects, and why they get checked one hop at a time.

A guard that checks a request once and then lets the HTTP library follow a
redirect is not a guard. httpx follows *inside* the call that was checked, so
the second request never reaches the check -- and 307/308 keep the method and
the body while they do it.

That was demonstrated against this library: a permitted
``PUT /api/projects/<uuid>/view-state`` answered with 307 left again as
``PUT /api/layers/DANGER/order``, unchecked, and the caller saw a plain
success.

Most of this file talks to a real HTTP server on localhost. It has to: the
failure was in how the httpx client was configured, and a stubbed transport
would have kept passing right through it.
"""

from __future__ import annotations

import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

import hgis
from hgis.client import ReadOnlyGuard
from hgis.transport import HttpxTransport, Response, Transport

PROJECT = "019fec3a-ef0c-775c-a14f-7535e8a676eb"
VIEW_STATE = f"/api/projects/{PROJECT}/view-state"
FORBIDDEN = "/api/layers/019fecb8-6f1d-7f11-abbf-beeeb5953247/order"


# --- a real server, because the bug lived in the HTTP client --------------


class _Redirecting(BaseHTTPRequestHandler):
    """Answers the one allowed write with a redirect to a forbidden path."""

    status = 307
    seen: list[tuple[str, str]] = []

    def _handle(self) -> None:
        type(self).seen.append((self.command, self.path))
        # Read the body before answering. Leaving it in the socket makes the
        # client see a connection reset instead of the redirect.
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        if self.path == VIEW_STATE:
            self.send_response(type(self).status)
            self.send_header("Location", FORBIDDEN)
            self.end_headers()
            return
        self.send_response(204)
        self.end_headers()

    do_GET = do_PUT = do_POST = do_DELETE = _handle

    def log_message(self, *args: object) -> None:
        """Quiet: the test reads self.seen, not the console."""


@pytest.fixture
def redirecting_server():
    """A localhost server that redirects the write, and records every hit."""
    _Redirecting.seen = []
    _Redirecting.status = 307
    server = HTTPServer(("127.0.0.1", 0), _Redirecting)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield server, _Redirecting
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def _client(server: HTTPServer) -> hgis.Client:
    host, port = server.server_address[:2]
    return hgis.connect(f"http://{host}:{port}", client_id="test-agent", timeout=5)


@pytest.mark.parametrize("status", [307, 308])
def test_a_redirect_cannot_smuggle_a_write_past_the_guard(
    redirecting_server, status
) -> None:
    """
    The reported break, as a test.

    307 and 308 keep the method and the body, so the second request is a PUT
    carrying the same payload -- aimed at a path the guard exists to refuse.
    """
    server, handler = redirecting_server
    handler.status = status
    client = _client(server)

    with pytest.raises(hgis.ReadOnlyError) as error:
        client.save_view_state(PROJECT, {"version": 1, "activeLayerId": None, "layers": {}})

    assert FORBIDDEN in str(error.value)

    methods_and_paths = handler.seen
    assert (("PUT", VIEW_STATE)) in methods_and_paths, "Der erste, erlaubte Schreibvorgang fehlt."
    assert not any(path == FORBIDDEN for _, path in methods_and_paths), (
        f"Die Anfrage ist beim verbotenen Pfad angekommen: {methods_and_paths}"
    )


def test_the_http_client_does_not_follow_on_its_own() -> None:
    """
    The configuration the break came from, pinned.

    ``follow_redirects=True`` is a security property here, not a preference:
    with it on, the guard's per-hop check is unreachable no matter how it is
    written.
    """
    transport = HttpxTransport()
    assert transport._client.follow_redirects is False


def test_a_redirect_is_visible_to_the_caller(redirecting_server) -> None:
    """
    The floors hand the redirect back instead of resolving it, so whoever
    called can decide. That needs the Location header to survive.
    """
    server, _ = redirecting_server
    host, port = server.server_address[:2]

    response = HttpxTransport().request("PUT", f"http://{host}:{port}{VIEW_STATE}", json={})

    assert response.status == 307
    assert response.header("Location") == FORBIDDEN
    assert response.header("location") == FORBIDDEN  # case-insensitive, as HTTP is


# --- the hop rules, without a server --------------------------------------


class _Scripted(Transport):
    """Returns prepared responses in order and records what it was asked."""

    def __init__(self, *responses: Response) -> None:
        self.responses = list(responses)
        self.seen: list[tuple[str, str, object]] = []

    def request(self, method, url, json=None, timeout=30.0, headers=None) -> Response:
        self.seen.append((method, url, json))
        return self.responses.pop(0) if self.responses else Response(204, "")


def _redirect(status: int, location: str) -> Response:
    return Response(status, "", {"Location": location})


def test_an_allowed_hop_is_followed() -> None:
    """
    Refusing every redirect would be simpler and would break a server that
    moves a path. A hop that lands somewhere allowed is fine.
    """
    inner = _Scripted(_redirect(307, VIEW_STATE), Response(204, ""))
    guard = ReadOnlyGuard(inner)

    response = guard.request("PUT", f"http://host{VIEW_STATE}", json={"a": 1})

    assert response.status == 204
    assert [method for method, _, _ in inner.seen] == ["PUT", "PUT"]


def test_the_body_does_not_travel_when_the_method_changes() -> None:
    """
    303 turns the request into a GET. Carrying the old payload onto it would
    send a body that belonged to a different request.
    """
    inner = _Scripted(_redirect(303, "/api/projects"), Response(200, "{}"))
    guard = ReadOnlyGuard(inner)

    guard.request("PUT", f"http://host{VIEW_STATE}", json={"a": 1})

    assert [(method, body) for method, _, body in inner.seen] == [
        ("PUT", {"a": 1}),
        ("GET", None),
    ]


def test_a_hop_to_another_host_is_refused() -> None:
    """
    Otherwise an injected redirect could send this request, and the client
    name on it, to a server nobody chose. The default address is plain http,
    so injecting one needs no malicious server -- only the network in between.
    """
    inner = _Scripted(_redirect(307, f"http://anderer-host{VIEW_STATE}"))
    guard = ReadOnlyGuard(inner)

    with pytest.raises(hgis.ReadOnlyError) as error:
        guard.request("PUT", f"http://host{VIEW_STATE}", json={})

    assert "anderen Server" in str(error.value)
    assert len(inner.seen) == 1


def test_a_hop_to_another_scheme_is_refused() -> None:
    """https and http are different origins; a downgrade is a change of one."""
    inner = _Scripted(_redirect(307, f"https://host{VIEW_STATE}"))
    with pytest.raises(hgis.ReadOnlyError):
        ReadOnlyGuard(inner).request("PUT", f"http://host{VIEW_STATE}", json={})


def test_a_relative_location_is_resolved_against_the_current_url() -> None:
    """``Location: ../order`` is a redirect like any other and gets checked."""
    inner = _Scripted(_redirect(307, "../../layers/x/order"))
    guard = ReadOnlyGuard(inner)

    with pytest.raises(hgis.ReadOnlyError) as error:
        guard.request("PUT", f"http://host{VIEW_STATE}", json={})

    assert "/api/layers/x/order" in str(error.value)


def test_a_redirect_loop_ends() -> None:
    """A server pointing at itself must not spin this in place."""
    inner = _Scripted(*[_redirect(307, VIEW_STATE) for _ in range(20)])
    guard = ReadOnlyGuard(inner)

    with pytest.raises(hgis.TransportError) as error:
        guard.request("PUT", f"http://host{VIEW_STATE}", json={})

    assert "Umleitungen" in str(error.value)
    assert len(inner.seen) <= 6


def test_a_redirect_without_a_location_is_handed_back() -> None:
    """Nothing to follow, so nothing to check; the caller sees what came."""
    inner = _Scripted(Response(307, ""))
    response = ReadOnlyGuard(inner).request("GET", "http://host/api/projects")
    assert response.status == 307


def test_reads_are_redirected_normally(redirecting_server) -> None:
    """
    The guard restricts writing. A read following a redirect stays a read, and
    every hop is still a GET, so nothing is refused.
    """
    server, handler = redirecting_server
    host, port = server.server_address[:2]
    guard = ReadOnlyGuard(HttpxTransport())

    response = guard.request("GET", f"http://{host}:{port}{VIEW_STATE}")

    assert response.status == 204
    assert ("GET", FORBIDDEN) in handler.seen  # allowed: a GET may go anywhere
