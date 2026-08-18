"""
The reader built on top of the raw channel: :func:`hgis.channel.watch`
reconnects, :func:`hgis.channel.wait_for` blocks on a predicate, and neither
ever filters an event by who wrote it -- see the module docstring in
:mod:`hgis.channel` for why each of those is a deliberate choice, not an
oversight.

Three layers of proof, extending the split ``test_events.py`` already uses
for the floor underneath this one:

* ``_parse_change`` and ``_reconnect_delay`` are pure functions, tested as
  such, without any connection at all.
* The reconnect loop itself -- does a clean end reconnect without delay, does
  a failure back off, does ``Connected.reconnected`` flip at the right
  moment -- is tested against a scripted, in-process :class:`Transport` that
  never touches a socket, so these run instantly and assert exact sequences.
* One test proves the same reconnect happens over a real socket that really
  closes, the same reasoning ``test_an_event_really_arrives_over_the_wire``
  uses in ``test_events.py``: a stub transport would keep "reconnecting"
  even if the real one, reading real bytes off a real connection, could not.
"""

from __future__ import annotations

import threading
from collections.abc import Callable, Iterator
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, HTTPServer
from itertools import count

import pytest

import hgis
from hgis import channel
from hgis.channel import (
    PROJECT_CATALOG_EVENT,
    PROJECT_VIEW_STATE_EVENT,
    RECONNECT_BASE_SECONDS,
    RECONNECT_MAX_SECONDS,
    Change,
    Connected,
    _parse_change,
    _reconnect_delay,
    for_project,
)
from hgis.transport import Event, Response, Transport, TransportError

# --- a scripted floor, no socket at all ---------------------------------


@dataclass
class _ScriptedTransport(Transport):
    """
    Hands back one prepared connection per call to :meth:`events`, in order.

    Each entry is a zero-argument callable returning an iterator of
    :class:`Event` -- ordinarily a generator function, so it behaves like a
    real stream: nothing runs until the first ``next()``, and ``close()`` on
    it is a real, harmless operation, the same as on
    :meth:`hgis.transport.HttpxTransport.events`'s own generator.

    Running out of scripted connections is a test-writing mistake, not
    something to fall silent about -- ``next(self._connections)`` would
    raise ``StopIteration`` straight out of :func:`hgis.channel.watch`,
    which -- since that is a generator itself -- Python turns into an opaque
    ``RuntimeError`` (PEP 479) instead of a readable assertion. Raising
    :class:`AssertionError` here avoids that trap.
    """

    connections: list[Callable[[], Iterator[Event]]]
    opened: int = 0

    def request(self, *args: object, **kwargs: object) -> Response:
        raise AssertionError("watch() darf nicht schreiben.")

    def events(
        self, url: str, *, headers: dict[str, str] | None = None, timeout: float | None = None
    ) -> Iterator[Event]:
        if self.opened >= len(self.connections):
            raise AssertionError(
                f"watch() hat mehr Verbindungen geöffnet ({self.opened + 1}) als das "
                "Testskript vorsieht."
            )
        make = self.connections[self.opened]
        self.opened += 1
        return make()


def _client(connections: list[Callable[[], Iterator[Event]]], **kwargs: object) -> hgis.Client:
    return hgis.connect("http://x", transport=_ScriptedTransport(connections), **kwargs)


def _view_state_event(project_id: str, version: int, origin: str | None) -> Event:
    origin_json = "null" if origin is None else f'"{origin}"'
    return Event(
        name=PROJECT_VIEW_STATE_EVENT,
        data=f'{{"projectId":"{project_id}","version":{version},"origin":{origin_json}}}',
    )


def _catalog_event(project_id: str, version: int, origin: str | None) -> Event:
    origin_json = "null" if origin is None else f'"{origin}"'
    return Event(
        name=PROJECT_CATALOG_EVENT,
        data=f'{{"projectId":"{project_id}","version":{version},"origin":{origin_json}}}',
    )


# --- _parse_change, without a connection --------------------------------


def test_parse_change_reads_the_known_shape() -> None:
    event = _catalog_event("p1", 7, "agent-a")
    assert _parse_change(event) == Change(
        name=PROJECT_CATALOG_EVENT, project_id="p1", version=7, origin="agent-a"
    )


def test_parse_change_keeps_a_null_origin() -> None:
    event = _view_state_event("p1", 1, None)
    change = _parse_change(event)
    assert change is not None
    assert change.origin is None


def test_parse_change_drops_an_event_name_it_does_not_know() -> None:
    """The default SSE name, and anything a newer server might add later."""
    event = Event(name="message", data='{"projectId":"p1","version":1,"origin":null}')
    assert _parse_change(event) is None


@pytest.mark.parametrize(
    "data",
    [
        "kein json",
        "[]",
        '{"projectId":1,"version":1,"origin":null}',
        '{"projectId":"p1","version":"1","origin":null}',
        '{"projectId":"p1","version":true,"origin":null}',
        '{"projectId":"p1","version":1,"origin":7}',
        '{"projectId":"p1"}',
    ],
    ids=[
        "kein-json",
        "array-statt-objekt",
        "projectId-keine-zeichenkette",
        "version-keine-zahl",
        "version-ist-bool",
        "origin-keine-zeichenkette",
        "version-und-origin-fehlen",
    ],
)
def test_parse_change_drops_a_malformed_payload(data: str) -> None:
    """
    A stream is long-lived and shared with a server that may run older or
    newer code than this library -- see the module docstring. Every one of
    these is dropped, not raised.
    """
    event = Event(name=PROJECT_CATALOG_EVENT, data=data)
    assert _parse_change(event) is None


# --- _reconnect_delay, without a connection ------------------------------


def test_reconnect_delay_is_between_half_and_full_at_the_first_attempt() -> None:
    assert _reconnect_delay(0, jitter=0.0) == pytest.approx(RECONNECT_BASE_SECONDS * 0.5)
    assert _reconnect_delay(0, jitter=1.0) == pytest.approx(RECONNECT_BASE_SECONDS)


def test_reconnect_delay_doubles_then_caps() -> None:
    assert _reconnect_delay(1, jitter=1.0) == pytest.approx(RECONNECT_BASE_SECONDS * 2)
    assert _reconnect_delay(2, jitter=1.0) == pytest.approx(RECONNECT_BASE_SECONDS * 4)
    assert _reconnect_delay(10, jitter=1.0) == pytest.approx(RECONNECT_MAX_SECONDS)


# --- for_project, without a connection -----------------------------------


def test_for_project_matches_the_id_and_optionally_the_name() -> None:
    change = Change(name=PROJECT_CATALOG_EVENT, project_id="p1", version=1, origin=None)
    assert for_project("p1")(change) is True
    assert for_project("p2")(change) is False
    assert for_project("p1", name=PROJECT_VIEW_STATE_EVENT)(change) is False
    assert for_project("p1", name=PROJECT_CATALOG_EVENT)(change) is True


def test_for_project_never_matches_connected() -> None:
    assert for_project("p1")(Connected(reconnected=False)) is False


# --- watch(): the reconnect loop, scripted -------------------------------


def test_the_first_connection_signals_connected_false_then_the_change() -> None:
    def first() -> Iterator[Event]:
        yield _view_state_event("p1", 1, "a")

    it = _client([first]).watch()
    try:
        assert next(it) == Connected(reconnected=False)
        assert next(it) == Change(
            name=PROJECT_VIEW_STATE_EVENT, project_id="p1", version=1, origin="a"
        )
    finally:
        it.close()


def test_a_clean_end_reconnects_at_once_and_signals_reconnected_true(monkeypatch) -> None:
    """
    The server's own ``stream-timeout`` ends a connection cleanly -- HTTP 200,
    no error -- and that is not a failure: no backoff, straight into the next
    connection.
    """

    def no_sleeping(seconds: float) -> None:
        raise AssertionError(f"watch() hat auf einen sauberen Abschluss hin geschlafen: {seconds}s")

    monkeypatch.setattr(channel.time, "sleep", no_sleeping)

    def first() -> Iterator[Event]:
        yield _catalog_event("p1", 1, None)
        # returns here: a clean end, like the server's SseEmitter timeout.

    def second() -> Iterator[Event]:
        yield _catalog_event("p1", 2, None)

    it = _client([first, second]).watch()
    try:
        items = [next(it) for _ in range(4)]
    finally:
        it.close()

    assert items == [
        Connected(reconnected=False),
        Change(name=PROJECT_CATALOG_EVENT, project_id="p1", version=1, origin=None),
        Connected(reconnected=True),
        Change(name=PROJECT_CATALOG_EVENT, project_id="p1", version=2, origin=None),
    ]


def test_an_otherwise_silent_connection_still_signals_connected() -> None:
    """
    Nothing recognised arrived before the connection ended -- an idle
    ``stream-timeout`` window. Nothing was necessarily missed, but
    :class:`Connected` still fires: see the module docstring, "The gap a
    reconnect opens".
    """

    def silent() -> Iterator[Event]:
        return
        yield  # pragma: no cover -- makes this a generator that yields nothing

    def then_something() -> Iterator[Event]:
        yield _catalog_event("p1", 1, None)

    it = _client([silent, then_something]).watch()
    try:
        assert next(it) == Connected(reconnected=False)
        assert next(it) == Connected(reconnected=True)
    finally:
        it.close()


def test_a_failed_attempt_backs_off_before_the_next_one(monkeypatch) -> None:
    sleeps: list[float] = []
    monkeypatch.setattr(channel, "_reconnect_delay", lambda attempt, jitter=None: 0.0)
    monkeypatch.setattr(channel.time, "sleep", sleeps.append)

    def broken() -> Iterator[Event]:
        raise TransportError("Verbindung abgebrochen")
        yield  # pragma: no cover -- makes this a generator function

    def recovered() -> Iterator[Event]:
        yield _view_state_event("p1", 3, None)

    it = _client([broken, recovered]).watch()
    try:
        item = next(it)
    finally:
        it.close()

    # A failed attempt never connected, so this is still the *first* success.
    assert item == Connected(reconnected=False)
    assert sleeps == [0.0]


def test_several_failures_in_a_row_each_back_off(monkeypatch) -> None:
    attempts: list[int] = []

    def recorded_delay(attempt: int, jitter: float | None = None) -> float:
        attempts.append(attempt)
        return 0.0

    monkeypatch.setattr(channel, "_reconnect_delay", recorded_delay)
    monkeypatch.setattr(channel.time, "sleep", lambda seconds: None)

    def broken() -> Iterator[Event]:
        raise TransportError("kaputt")
        yield  # pragma: no cover

    def recovered() -> Iterator[Event]:
        yield _catalog_event("p1", 1, None)

    it = _client([broken, broken, broken, recovered]).watch()
    try:
        next(it)
    finally:
        it.close()

    assert attempts == [0, 1, 2]


def test_the_echo_is_not_filtered() -> None:
    """
    A caller who wants to skip its own write compares ``origin`` against
    ``client.client_id`` itself -- see the module docstring, "The echo".
    """

    def conn() -> Iterator[Event]:
        yield _catalog_event("p1", 1, "me")

    client = _client([conn], client_id="me")
    it = client.watch()
    try:
        next(it)  # Connected
        change = next(it)
    finally:
        it.close()

    assert isinstance(change, Change)
    assert change.origin == "me" == client.client_id


# --- wait_for(): scripted, deterministic ---------------------------------


def test_wait_for_returns_the_first_matching_item() -> None:
    def conn() -> Iterator[Event]:
        yield _view_state_event("other", 1, None)
        yield _catalog_event("p1", 9, None)

    match = _client([conn]).wait_for(for_project("p1"))

    assert match == Change(name=PROJECT_CATALOG_EVENT, project_id="p1", version=9, origin=None)


def test_wait_for_gives_up_after_the_deadline(monkeypatch) -> None:
    # Start, one check that is not yet due, one check that is overdue.
    times = iter([0.0, 0.0, 10.0])
    monkeypatch.setattr(channel.time, "monotonic", lambda: next(times))

    def conn() -> Iterator[Event]:
        yield _view_state_event("other", 1, None)
        yield _view_state_event("other", 2, None)

    result = _client([conn]).wait_for(for_project("p1"), timeout=5)

    assert result is None


def test_wait_for_can_match_connected_itself() -> None:
    """
    ``predicate`` sees everything :func:`watch` yields -- matching on
    :class:`Connected` directly is how a caller notices a (re)connect rather
    than any particular change.
    """

    def conn() -> Iterator[Event]:
        yield _catalog_event("p1", 1, None)

    match = _client([conn]).wait_for(lambda item: isinstance(item, Connected))

    assert match == Connected(reconnected=False)


# --- a real socket, because a stub would keep "reconnecting" even if the
# real floor could not ----------------------------------------------------


class _EndsAfterOneEvent(BaseHTTPRequestHandler):
    """
    Answers every request to ``/api/events`` with exactly one event, then
    ends the connection -- simulating the server's own clean
    ``stream-timeout`` close on every single connection, so a handful of
    real reconnects happen inside one short-lived test.
    """

    _versions = count(1)

    def do_GET(self) -> None:
        if self.path != "/api/events":
            self.send_response(404)
            self.end_headers()
            return
        version = next(_EndsAfterOneEvent._versions)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        self.wfile.write(b": Live-Kanal offen\n\n")
        self.wfile.write(
            f'event: {PROJECT_CATALOG_EVENT}\n'
            f'data: {{"projectId":"p1","version":{version},"origin":null}}\n\n'.encode()
        )
        self.wfile.flush()
        # Returning here closes the connection (HTTP/1.0, no Content-Length) --
        # the same clean, exception-free end test_events.py's own _Streaming
        # relies on for test_an_event_really_arrives_over_the_wire.

    def log_message(self, *args: object) -> None:
        """Quiet: the test reads the parsed events, not the console."""


@pytest.fixture
def ending_stream_server():
    server = HTTPServer(("127.0.0.1", 0), _EndsAfterOneEvent)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield server
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def test_watch_reconnects_over_a_real_socket_that_really_closes(ending_stream_server) -> None:
    host, port = ending_stream_server.server_address[:2]
    client = hgis.connect(f"http://{host}:{port}", timeout=5)

    it = client.watch()
    try:
        items = [next(it) for _ in range(6)]  # three real connections' worth
    finally:
        it.close()

    connected = [item for item in items if isinstance(item, Connected)]
    changes = [item for item in items if isinstance(item, Change)]
    assert [c.reconnected for c in connected] == [False, True, True]
    assert [c.version for c in changes] == sorted(c.version for c in changes)
    assert len(changes) == 3
