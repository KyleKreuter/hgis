"""
The second form :class:`hgis.transport.Transport` offers: a stream of Server-
Sent Events instead of one :class:`~hgis.transport.Response`.

Two layers of proof, the same split test_redirect.py uses for the same
reason: :func:`_parse_sse` is a pure state machine and is tested as one,
without a server; whether ``HttpxTransport`` actually receives bytes off a
socket and turns them into the same events needs a real one, because that is
exactly the kind of thing a stub would keep passing through even if broken.
"""

from __future__ import annotations

import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

import hgis
from hgis.transport import Event, HttpxTransport, PyodideTransport, TransportError, _parse_sse

# --- the parser, without a server -------------------------------------------


def test_a_comment_line_is_never_an_event() -> None:
    """The greeting and the heartbeat are both exactly this: a line starting with ':'."""
    assert list(_parse_sse([": Live-Kanal offen", "", ": hb", ""])) == []


def test_a_bare_retry_is_never_an_event() -> None:
    assert list(_parse_sse(["retry: 15000", ""])) == []


def test_a_named_event_with_data_is_dispatched() -> None:
    lines = [
        'event: project-view-state',
        'data: {"projectId":"p1","version":3,"origin":"agent-a"}',
        "",
    ]
    assert list(_parse_sse(lines)) == [
        Event(name="project-view-state", data='{"projectId":"p1","version":3,"origin":"agent-a"}'),
    ]


def test_an_event_without_a_name_defaults_to_message() -> None:
    """The default the SSE spec itself assigns, not one this library invents."""
    assert list(_parse_sse(["data: hallo", ""])) == [Event(name="message", data="hallo")]


def test_several_data_lines_are_joined_with_newline() -> None:
    lines = ["data: erste Zeile", "data: zweite Zeile", ""]
    assert list(_parse_sse(lines)) == [Event(name="message", data="erste Zeile\nzweite Zeile")]


def test_the_id_field_travels() -> None:
    lines = ["id: 42", "data: x", ""]
    assert list(_parse_sse(lines)) == [Event(name="message", data="x", id="42")]


def test_one_leading_space_after_the_colon_is_dropped() -> None:
    """Per the spec: exactly one, so a value that starts with real spaces keeps the rest."""
    assert list(_parse_sse(["data:  zwei Leerzeichen", ""])) == [
        Event(name="message", data=" zwei Leerzeichen"),
    ]


def test_a_field_with_no_colon_at_all_is_ignored() -> None:
    assert list(_parse_sse(["data", "data: x", ""])) == [Event(name="message", data="x")]


def test_several_events_in_one_stream_are_dispatched_separately() -> None:
    lines = [
        "event: a", "data: 1", "",
        ": hb", "",
        "event: b", "data: 2", "",
    ]
    assert list(_parse_sse(lines)) == [
        Event(name="a", data="1"),
        Event(name="b", data="2"),
    ]


def test_an_unterminated_block_without_a_trailing_blank_line_is_not_dispatched() -> None:
    """No blank line yet means the block is still open -- there is nothing to yield."""
    assert list(_parse_sse(["event: a", "data: 1"])) == []


# --- the floors --------------------------------------------------------


def test_pyodide_says_where_the_channel_does_not_run_yet() -> None:
    with pytest.raises(TransportError) as error:
        PyodideTransport().events("http://x/api/events")
    assert "CPython" in str(error.value)


def test_events_is_the_second_form_next_to_request() -> None:
    """Both are part of the same seam; substituting one should not silently drop the other."""
    from hgis.transport import Transport

    assert hasattr(Transport, "request")
    assert hasattr(Transport, "events")


# --- a real server, because the bug this proves against would live in how
# the socket is read, not in _parse_sse ---------------------------------


class _Streaming(BaseHTTPRequestHandler):
    """Answers /api/events with a real SSE body: a greeting, a heartbeat, one event."""

    def do_GET(self) -> None:
        if self.path != "/api/events":
            self.send_response(404)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        self.wfile.write(b"retry: 15000\n")
        self.wfile.write(b": Live-Kanal offen\n\n")
        self.wfile.write(b": hb\n\n")
        self.wfile.write(
            b'event: project-view-state\n'
            b'data: {"projectId":"019fec3a-ef0c-775c-a14f-7535e8a676eb",'
            b'"version":3,"origin":"agent-a"}\n\n'
        )
        self.wfile.flush()

    def log_message(self, *args: object) -> None:
        """Quiet: the test reads the parsed events, not the console."""


@pytest.fixture
def streaming_server():
    server = HTTPServer(("127.0.0.1", 0), _Streaming)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield server
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def test_an_event_really_arrives_over_the_wire(streaming_server) -> None:
    """
    The proof this stage promised: not a stub answering in-process, an actual
    socket, an actual ``httpx`` stream, parsed back into the same Event the
    unit tests above build by hand.
    """
    host, port = streaming_server.server_address[:2]
    transport = HttpxTransport()

    events = list(transport.events(f"http://{host}:{port}/api/events"))

    assert events == [
        Event(
            name="project-view-state",
            data='{"projectId":"019fec3a-ef0c-775c-a14f-7535e8a676eb",'
                 '"version":3,"origin":"agent-a"}',
        ),
    ]


def test_the_event_arrives_through_the_client_too(streaming_server) -> None:
    """The same proof, through Client.events() -- guard, floor and parser together."""
    host, port = streaming_server.server_address[:2]
    client = hgis.connect(f"http://{host}:{port}", client_id="agent-a", timeout=5)

    events = list(client.events())

    assert len(events) == 1
    assert events[0].name == "project-view-state"
    import json

    payload = json.loads(events[0].data)
    assert payload["origin"] == "agent-a"
    assert payload["version"] == 3


def test_a_status_other_than_200_is_reported(streaming_server) -> None:
    host, port = streaming_server.server_address[:2]
    transport = HttpxTransport()

    with pytest.raises(TransportError) as error:
        list(transport.events(f"http://{host}:{port}/nicht-die-events-route"))

    assert "404" in str(error.value)
