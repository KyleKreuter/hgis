"""
The name this program writes under.

The live channel (``GET /api/events``) reports that a project's working state
changed and repeats the name of whoever wrote it. A client that finds its own
name there already knows the state and leaves the event alone.

Two failures are possible, and they are not equally bad. Without a name, a
program reads back what it just wrote -- wasteful, harmless. With the *same*
name as another program, each takes the other's change for its own echo and
ignores it -- and then a real change is lost. That is why the default name is
random per process rather than a fixed string.
"""

from __future__ import annotations

import re

import pytest

import hgis
from conftest import LAYER_ID, PROJECT_ID, FakeTransport, stub_server
from hgis.client import CLIENT_HEADER, CLIENT_ID_VARIABLE, default_client_id

#: Mirrored from ClientId.ALLOWED in the backend.
SERVER_PATTERN = re.compile(r"[A-Za-z0-9_-]{1,64}")


def _write(client: hgis.Client) -> None:
    """The one write this stage makes."""
    client.project(PROJECT_ID).select([1], layer=LAYER_ID)


def _written_header(transport: FakeTransport) -> str | None:
    put = [request for request in transport.requests if request.method == "PUT"]
    assert len(put) == 1
    return put[0].headers.get(CLIENT_HEADER)


# --- the header travels ---------------------------------------------------


def test_the_write_carries_the_client_name(transport) -> None:
    """Without it, an agent on the live channel reads back its own writes."""
    client = hgis.connect("http://stub", transport=transport, client_id="agent-a")
    _write(client)

    assert _written_header(transport) == "agent-a"


def test_reads_carry_no_name(transport) -> None:
    """
    A read produces no event, so there is no echo to recognise.

    Sending it anyway would be harmless and would still be noise on every one
    of the many requests a query makes.
    """
    client = hgis.connect("http://stub", transport=transport, client_id="agent-a")
    client.layer(LAYER_ID).count()

    assert all(request.method == "GET" for request in transport.requests)
    assert all(CLIENT_HEADER not in request.headers for request in transport.requests)


def test_the_header_name_is_written_once() -> None:
    """
    It is still under review on the server side, so it has to be changeable in
    one place. This test fails if a second spelling appears anywhere.
    """
    from pathlib import Path

    source = Path(hgis.__file__).parent
    occurrences = [
        path.name
        for path in source.glob("*.py")
        if "X-Hgis-Client" in path.read_text(encoding="utf-8")
    ]
    assert occurrences == ["client.py"]


# --- where the name comes from --------------------------------------------


def test_the_default_name_is_different_per_process(monkeypatch) -> None:
    """
    Two agents under one name would each ignore the other's change as their
    own echo, and a real change would be swallowed. A fixed default would
    produce exactly that whenever two programs run at once.
    """
    monkeypatch.delenv(CLIENT_ID_VARIABLE, raising=False)
    assert SERVER_PATTERN.fullmatch(default_client_id())
    assert default_client_id().startswith("hgis-python-")


def test_the_name_is_stable_within_a_process(monkeypatch) -> None:
    """
    Held for the program's lifetime, so writes made minutes apart are still
    recognisable as coming from the same program.
    """
    monkeypatch.delenv(CLIENT_ID_VARIABLE, raising=False)
    assert default_client_id() == default_client_id()
    assert hgis.connect("http://x").client_id == hgis.connect("http://x").client_id


def test_the_environment_names_the_process(monkeypatch, transport) -> None:
    """For a runner that starts several workers and wants to tell them apart."""
    monkeypatch.setenv(CLIENT_ID_VARIABLE, "runner-worker-3")

    client = hgis.connect("http://stub", transport=transport)
    assert client.client_id == "runner-worker-3"

    _write(client)
    assert _written_header(transport) == "runner-worker-3"


def test_the_argument_wins_over_the_environment(monkeypatch, transport) -> None:
    monkeypatch.setenv(CLIENT_ID_VARIABLE, "aus-der-umgebung")
    client = hgis.connect("http://stub", transport=transport, client_id="aus-dem-code")
    assert client.client_id == "aus-dem-code"


def test_an_empty_environment_variable_falls_back(monkeypatch) -> None:
    """An unset variable and one set to nothing mean the same thing."""
    monkeypatch.setenv(CLIENT_ID_VARIABLE, "")
    assert default_client_id().startswith("hgis-python-")


# --- names the server would refuse ----------------------------------------


@pytest.mark.parametrize(
    "name",
    [
        "",
        "   ",
        "mit leerzeichen",
        "mit.punkt",
        "mit/schrägstrich",
        "Umlaut-ä",
        "neue\nzeile",
        "x" * 65,
    ],
)
def test_a_name_the_server_would_refuse_fails_here_first(name) -> None:
    """
    Checked in Python rather than sent and rejected with a 400.

    The server answers a malformed name with 400, which would make the whole
    write fail -- and it would fail at the first write, possibly minutes after
    the mistake was made. This fails while the client is being built.
    """
    assert not SERVER_PATTERN.fullmatch(name.strip()), "Der Server würde das annehmen."

    with pytest.raises(hgis.InvalidClientIdError):
        hgis.connect("http://stub", client_id=name)


def test_the_refusal_names_the_character_set() -> None:
    with pytest.raises(hgis.InvalidClientIdError) as error:
        hgis.connect("http://stub", client_id="mit leerzeichen")

    message = str(error.value)
    assert "Ungültiger Client-Name" in message
    assert "64" in message
    assert "Bindestrich" in message


def test_a_bad_name_from_the_environment_fails_too(monkeypatch) -> None:
    """The variable is as much a mistake as the argument, and fails as loudly."""
    monkeypatch.setenv(CLIENT_ID_VARIABLE, "nicht erlaubt!")
    with pytest.raises(hgis.InvalidClientIdError):
        hgis.connect("http://stub")


def test_the_refusal_is_both_an_hgis_error_and_a_value_error() -> None:
    """A bad constructor argument is a ValueError; everything here is HgisError."""
    with pytest.raises(hgis.HgisError):
        hgis.connect("http://stub", client_id="!")
    with pytest.raises(ValueError):
        hgis.connect("http://stub", client_id="!")


@pytest.mark.parametrize(
    "name",
    ["a", "agent-1", "agent_1", "AGENT-1", "hgis-python-2d2dc2ed1874", "x" * 64],
)
def test_names_the_server_accepts_are_accepted(name) -> None:
    """The check mirrors the server's, so it must not be stricter either."""
    assert SERVER_PATTERN.fullmatch(name), "Der Server würde das ablehnen."
    assert hgis.connect("http://stub", client_id=name).client_id == name


def test_surrounding_space_is_trimmed_like_the_server_does() -> None:
    """ClientId.require trims before matching; a name from a file often has it."""
    assert hgis.connect("http://stub", client_id="  agent-a  ").client_id == "agent-a"


def test_the_client_says_who_it_is(transport) -> None:
    """Printed into an agent's context, so it has to show the name it writes under."""
    client = hgis.connect("http://stub", transport=transport, client_id="agent-a")
    assert "agent-a" in repr(client)


def test_two_clients_can_be_named_apart(transport) -> None:
    """
    The case the default guards against, made explicit: two programs writing
    at the same time must be distinguishable on the live channel.
    """
    first = hgis.connect("http://stub", transport=transport, client_id="agent-a")
    second = hgis.connect("http://stub", transport=FakeTransport(stub_server), client_id="agent-b")

    assert first.client_id != second.client_id
