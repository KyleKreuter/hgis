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

import os
import re
import signal
import threading
import time

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


# --- across a fork ---------------------------------------------------------
#
# A forked child inherits the parent's memory. A name computed once at import
# is already sitting there, and this module never runs again to make a new one,
# so parent and child would write under one name. Four workers of a
# multiprocessing.Pool then take each other's changes for their own echo, and a
# real change is dropped without a sound.
#
# These use os.fork directly rather than multiprocessing: same mechanism, no
# pool machinery, and the child exits with os._exit so it never runs pytest's
# teardown.

fork_only = pytest.mark.skipif(
    not hasattr(os, "fork"), reason="fork gibt es auf dieser Plattform nicht"
)

# Python warns that forking a multi-threaded process can deadlock the child.
# It is right in general and does not apply here: the child computes one
# string, writes it and leaves through os._exit, taking no lock on the way.
pytestmark = pytest.mark.filterwarnings("ignore:This process .* is multi-threaded")


def _in_a_forked_child(produce) -> str:
    """Run ``produce()`` in a forked child and return what it produced."""
    read_end, write_end = os.pipe()
    pid = os.fork()
    if pid == 0:
        os.close(read_end)
        try:
            os.write(write_end, produce().encode())
        except BaseException as error:  # never let a child raise into pytest
            os.write(write_end, f"FEHLER {error!r}".encode())
        finally:
            os.close(write_end)
            os._exit(0)

    os.close(write_end)
    produced = os.read(read_end, 4096).decode()
    os.close(read_end)
    os.waitpid(pid, 0)
    assert produced, "Das Kind hat nichts geliefert."
    assert not produced.startswith("FEHLER"), produced
    return produced


@fork_only
def test_a_forked_child_gets_its_own_name(monkeypatch) -> None:
    """
    The reported failure, as a test.

    Same name in parent and child is the bad case, not a cosmetic one: it makes
    each process ignore the other's change as its own echo.
    """
    monkeypatch.delenv(CLIENT_ID_VARIABLE, raising=False)
    parent = default_client_id()

    child = _in_a_forked_child(default_client_id)

    assert child != parent
    assert SERVER_PATTERN.fullmatch(child)


@fork_only
def test_a_client_built_before_the_fork_writes_under_the_child_name(monkeypatch) -> None:
    """
    Building the client first and forking after is the ordinary shape of a
    worker pool, so the name has to follow the process, not the object.
    """
    monkeypatch.delenv(CLIENT_ID_VARIABLE, raising=False)
    client = hgis.connect("http://stub")
    parent = client.client_id

    child = _in_a_forked_child(lambda: client.client_id)

    assert child != parent


@fork_only
def test_the_child_keeps_a_name_that_was_chosen(monkeypatch) -> None:
    """
    A name given explicitly is the caller's decision, and a fork does not
    overrule it. Only the generated fallback belongs to one process.
    """
    monkeypatch.delenv(CLIENT_ID_VARIABLE, raising=False)
    client = hgis.connect("http://stub", client_id="agent-a")

    assert _in_a_forked_child(lambda: client.client_id) == "agent-a"


@fork_only
def test_the_child_keeps_the_name_from_the_environment(monkeypatch) -> None:
    """Set from outside means set on purpose, and it survives the fork."""
    monkeypatch.setenv(CLIENT_ID_VARIABLE, "runner-worker-3")

    assert _in_a_forked_child(default_client_id) == "runner-worker-3"


def test_the_name_is_still_stable_without_a_fork(monkeypatch) -> None:
    """
    Binding the name to the process must not make it change per call.

    A name that differed between two writes of one program would defeat the
    purpose just as thoroughly as a shared one.
    """
    monkeypatch.delenv(CLIENT_ID_VARIABLE, raising=False)
    assert default_client_id() == default_client_id()

    client = hgis.connect("http://stub")
    assert client.client_id == client.client_id


# --- across threads --------------------------------------------------------
#
# The same failure as the fork one, a level down and inside a single process.
# Several threads can find the name unset at once; each then computes its own --
# uuid4 reads the operating system's randomness and lets other threads run while
# it waits -- and each writes over the last, having already returned what it
# made. One process then writes under several names.
#
# Without letting the threads go at the same instant, none of this shows: the
# first caller wins long before the second arrives. Hence the barrier.

THREADS = 32


def _all_at_once(work, count: int = THREADS) -> list:
    """Run ``work()`` in ``count`` threads released at the same instant."""
    barrier = threading.Barrier(count)
    results: list = []
    guard = threading.Lock()

    def run() -> None:
        barrier.wait()
        value = work()
        with guard:
            results.append(value)

    threads = [threading.Thread(target=run) for _ in range(count)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    assert len(results) == count, "Nicht jeder Thread hat geliefert."
    return results


@pytest.fixture
def unnamed_process(monkeypatch):
    """No name from outside, and nothing computed yet."""
    monkeypatch.delenv(CLIENT_ID_VARIABLE, raising=False)
    monkeypatch.setattr(hgis.client, "_generated", None)


@pytest.mark.parametrize("attempt", range(10))
def test_threads_starting_together_get_one_name(unnamed_process, attempt) -> None:
    """
    The reported failure, as a test.

    Repeated ten times, because a race that shows up sometimes is a race that
    passes sometimes. Measured against the code before the lock: two to five
    names per run, and roughly three runs in five caught it -- so one attempt
    would have been a coin toss and ten make a miss unlikely.
    """
    names = _all_at_once(default_client_id)
    assert len(set(names)) == 1, f"{len(set(names))} Namen unter {THREADS} Threads"


@pytest.mark.parametrize("attempt", range(3))
def test_clients_built_together_write_under_one_name(unnamed_process, attempt) -> None:
    """
    The practical shape of it: an agent parallelising over a thread pool.

    Two names among those clients means one takes the other's change for a
    stranger's and handles it twice.
    """
    names = _all_at_once(lambda: hgis.connect("http://stub").client_id, count=24)
    assert len(set(names)) == 1, f"{len(set(names))} Namen unter 24 Clients"


def test_the_name_does_not_change_under_repeated_reads(unnamed_process) -> None:
    """
    Each thread has to keep its own answer as well.

    A thread that read early and never asked again would carry a name that was
    overwritten behind it -- and would not notice.
    """

    def fifty_reads() -> str:
        seen = {default_client_id() for _ in range(50)}
        assert len(seen) == 1, "Ein Thread hat seinen eigenen Namen gewechselt."
        return seen.pop()

    assert len(set(_all_at_once(fifty_reads))) == 1


# --- a name from the environment is checked on every read ------------------


def test_a_name_broken_after_the_client_was_built_is_refused(monkeypatch) -> None:
    """
    The environment is read on every access, so it has to be checked on every
    access too. Reading fresh and checking once is the combination that lets an
    unusable name out as a header.
    """
    monkeypatch.delenv(CLIENT_ID_VARIABLE, raising=False)
    client = hgis.connect("http://stub")
    assert client.client_id  # fine so far

    monkeypatch.setenv(CLIENT_ID_VARIABLE, "nicht erlaubt!")
    with pytest.raises(hgis.InvalidClientIdError):
        client.client_id


def test_a_name_repaired_after_the_client_was_built_is_used(monkeypatch) -> None:
    """Reading fresh cuts both ways, and the good direction has to work too."""
    monkeypatch.delenv(CLIENT_ID_VARIABLE, raising=False)
    client = hgis.connect("http://stub")

    monkeypatch.setenv(CLIENT_ID_VARIABLE, "spaeter-gesetzt")
    assert client.client_id == "spaeter-gesetzt"


def test_a_chosen_name_ignores_the_environment_entirely(monkeypatch) -> None:
    """Passed in explicitly means the environment does not get a say later."""
    client = hgis.connect("http://stub", client_id="agent-a")
    monkeypatch.setenv(CLIENT_ID_VARIABLE, "nicht erlaubt!")
    assert client.client_id == "agent-a"


@fork_only
def test_a_fork_while_the_lock_is_held_does_not_deadlock_the_child(
    unnamed_process,
) -> None:
    """
    The case ``_reset_after_fork`` exists for, and the only one that shows it.

    A lock held by some thread at the moment of the fork stays held forever in
    the child: the thread that would release it does not exist there. The child
    would then block on the first call and never return -- no error, no output,
    a worker that simply stops.

    The other fork tests cannot catch this. They fork while nothing holds the
    lock, so an inherited lock is free and everything works without the reset.
    """
    holding = threading.Event()
    release = threading.Event()

    def hold_the_lock() -> None:
        with hgis.client._lock:
            holding.set()
            release.wait(timeout=10)

    keeper = threading.Thread(target=hold_the_lock)
    keeper.start()
    assert holding.wait(timeout=5), "Der Thread hat die Sperre nicht genommen."

    read_end, write_end = os.pipe()
    pid = os.fork()
    if pid == 0:
        os.close(read_end)
        try:
            # Deadlocks here if the child inherited a held lock.
            os.write(write_end, default_client_id().encode())
        except BaseException as error:
            os.write(write_end, f"FEHLER {error!r}".encode())
        finally:
            os.close(write_end)
            os._exit(0)

    os.close(write_end)
    try:
        deadline = time.monotonic() + 10
        finished = False
        while time.monotonic() < deadline:
            waited, _ = os.waitpid(pid, os.WNOHANG)
            if waited == pid:
                finished = True
                break
            time.sleep(0.02)

        if not finished:
            os.kill(pid, signal.SIGKILL)  # our own child, started just above
            os.waitpid(pid, 0)
            pytest.fail(
                "Das Kind hat sich an der geerbten Sperre aufgehängt. "
                "os.register_at_fork muss sie erneuern."
            )

        produced = os.read(read_end, 4096).decode()
        assert produced and not produced.startswith("FEHLER"), produced
        assert SERVER_PATTERN.fullmatch(produced)
    finally:
        os.close(read_end)
        release.set()
        keeper.join(timeout=5)
