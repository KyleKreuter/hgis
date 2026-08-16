"""
The guard that keeps this stage to the requests it means to make.

Not a lock. Anyone who means to write can ``import httpx`` and go around this
library completely, and that is not what this defends against. It defends
against the accidental one: a generic verb, or a path nobody meant to reach,
landing on an endpoint that changes data.

That matters more than it looks, even with a trash and a change log behind
most of these endpoints now (see CONTRACT.md, "Schreibstufe"): a purge has no
trash behind it, and a deleted object is recoverable only through the change
log, never through this library. See ``GuardError``'s docstring for the
current list of what a mistake here still cannot take back.
"""

from __future__ import annotations

import pytest

import hgis
from conftest import LAYER_ID, PROJECT_ID, FakeTransport, stub_server
from hgis.transport import Response

OTHER_UUID = "019fecc1-48a2-76b7-8732-019e83d5532a"


@pytest.fixture
def guarded(transport: FakeTransport) -> hgis.Client:
    return hgis.connect("http://stub", transport=transport)


# --- what must still not get through ---------------------------------------


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("DELETE", f"/api/projects/{PROJECT_ID}"),  # deleting a project: not opened
        ("PUT", f"/api/projects/{PROJECT_ID}/layers/order"),  # reordering: not opened
        ("POST", f"/api/layers/{LAYER_ID}/features/1/split"),  # split: not opened
        ("POST", f"/api/layers/{LAYER_ID}/features/merge"),  # merge: not opened
        ("PATCH", f"/api/layers/{LAYER_ID}/fields/{OTHER_UUID}"),  # renaming a field: not opened
        ("DELETE", f"/api/layers/{LAYER_ID}/fields/x"),  # "x" is not a field id
    ],
)
def test_a_writing_request_outside_this_stage_is_refused(guarded, transport, method, path) -> None:
    """Every one of these is a real endpoint on the backend, none opened by this stage."""
    with pytest.raises(hgis.GuardError):
        guarded._send(method, path, json={})
    assert transport.count == 0, "Die Anfrage hat den Transport erreicht."


def test_the_guard_also_covers_the_transport_itself(guarded, transport) -> None:
    """
    The check sits in front of the floor, not in front of ``get``.

    A check inside :meth:`Client.get` alone would be walked past by anyone
    reaching for ``client._transport`` -- which is one attribute away.
    """
    with pytest.raises(hgis.GuardError):
        guarded._transport.request("DELETE", f"http://stub/api/projects/{PROJECT_ID}")

    assert transport.count == 0


def test_a_supplied_transport_is_wrapped_too(transport) -> None:
    """
    Substituting the floor is for testing and authentication, not for lifting
    the guard. So the wrapper goes on either way.
    """
    client = hgis.connect("http://stub", transport=transport)
    assert isinstance(client._transport, hgis.RequestGuard)
    assert client._transport.inner is transport


def test_there_is_no_generic_write_verb() -> None:
    """
    ``put(path, body)`` was the way in once: not underscored, on a class that
    is exported, and reachable from ``help(client)`` without looking for it.

    It never came back, even though this stage opens real writes. Each one
    got its own named method instead, so a caller cannot assemble a request
    to a path this library never meant to reach.
    """
    for generic in ("put", "post", "patch", "delete"):
        assert not hasattr(hgis.Client, generic), f"Client.{generic} ist ein Schreibverb."

    for named in (
        "save_view_state",
        "create_layer",
        "update_layer",
        "delete_layer",
        "restore_layer",
        "purge_layer",
        "apply_edits",
        "create_field",
        "delete_field",
    ):
        assert hasattr(hgis.Client, named)


def test_the_refusal_names_the_request_and_what_is_allowed(guarded) -> None:
    """The rule everywhere in this API: an error names the way that works."""
    with pytest.raises(hgis.GuardError) as error:
        guarded._send("DELETE", f"/api/projects/{PROJECT_ID}")

    message = str(error.value)
    assert f"DELETE /api/projects/{PROJECT_ID}" in message
    assert "project.select()" in message
    assert "layer.edit()" in message


def test_the_refusal_is_an_hgis_error(guarded) -> None:
    """So ``except hgis.HgisError`` catches it like everything else."""
    with pytest.raises(hgis.HgisError):
        guarded._send("DELETE", f"/api/projects/{PROJECT_ID}")


# --- what must still get through --------------------------------------------


def test_reading_is_untouched(guarded, transport) -> None:
    """The guard restricts specific writes. Reading is what this library is for."""
    project = guarded.project(PROJECT_ID)
    layer = project.layer("Gebäude Speicherstadt")

    assert layer.count() == 1003
    assert len(layer.where('"Höhe" > 10').fids()) == 415
    assert layer.describe().name == "Gebäude Speicherstadt"
    assert transport.count > 0


def test_the_view_state_write_still_works(transport) -> None:
    """``select()`` was the first exception the list was written for, and still is one."""
    client = hgis.connect("http://stub", transport=transport)
    project = client.project(PROJECT_ID)

    project.select([1, 2, 3], layer=LAYER_ID)

    written = [request for request in transport.requests if request.method == "PUT"]
    assert len(written) == 1
    assert written[0].path == f"/api/projects/{PROJECT_ID}/view-state"
    assert transport.bodies[-1]["layers"][LAYER_ID]["selection"] == [1, 2, 3]


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("POST", f"/api/projects/{PROJECT_ID}/layers"),
        ("PATCH", f"/api/layers/{LAYER_ID}"),
        ("DELETE", f"/api/layers/{LAYER_ID}"),
        ("POST", f"/api/layers/{LAYER_ID}/restore"),
        ("DELETE", f"/api/layers/{LAYER_ID}/purge"),
        ("POST", f"/api/layers/{LAYER_ID}/edits"),
        ("POST", f"/api/layers/{LAYER_ID}/fields"),
        ("DELETE", f"/api/layers/{LAYER_ID}/fields/{OTHER_UUID}"),
    ],
)
def test_this_stages_write_paths_are_allowed(method, path) -> None:
    """
    Every write CONTRACT.md's Schreibstufe names, opened at the guard.

    Tested at the ``_send`` level, one layer below the named ``Client``
    methods (``create_layer`` and the rest) -- those are covered by their own
    shape in test_writes.py; this is only about whether the allowlist lets
    the request through at all.
    """
    transport = FakeTransport(lambda request: Response(204, ""))
    client = hgis.connect("http://stub", transport=transport)

    client._send(method, path, json={})

    assert transport.count == 1
    assert transport.requests[0].method == method
    assert transport.requests[0].path == path


def test_the_guard_is_not_a_lock(transport) -> None:
    """
    Stated as a test so nobody mistakes it for one.

    The underlying floor is reachable and does what it is told. What the guard
    removes is the accident, not the option -- and a reader who wants to know
    which of the two this is should find the answer here.
    """
    client = hgis.connect("http://stub", transport=transport)

    # Straight past the guard, on the floor it wraps:
    client._transport.inner.request("DELETE", f"http://stub/api/projects/{PROJECT_ID}")

    assert transport.requests[-1].method == "DELETE"


def test_a_test_transport_on_its_own_is_unguarded() -> None:
    """
    The guard belongs to the client, not to the Transport class.

    Which is what lets the suite above build requests that would be refused,
    and assert that they are.
    """
    transport = FakeTransport(stub_server)
    assert not isinstance(transport, hgis.RequestGuard)


# --- the shape of the write paths -------------------------------------------
#
# Every entry names a UUID, not a wildcard. Without the cases below, that is a
# claim: loosening a pattern to `.*` would leave every other test in this file
# green, because none of them vary the id itself.


@pytest.mark.parametrize(
    "project_id",
    [
        "abc",
        "019fec3a",
        "019fec3a-ef0c-775c-a14f",
        "019fec3a-ef0c-775c-a14f-7535e8a676eb-extra",
        "019fec3a_ef0c_775c_a14f_7535e8a676eb",
        "019fec3a-ef0c-775c-a14f-7535e8a676eg",  # g is not a hex digit
        "019fec3a-ef0c-775c-a14f-7535e8a676e",  # one short
        "019fec3a-ef0c-775c-a14f-7535e8a676ebb",  # one long
        "..",
        "%2e%2e",
        "*",
    ],
)
def test_the_view_state_write_needs_a_real_project_id(guarded, transport, project_id) -> None:
    """Anything but a UUID in that position is refused."""
    with pytest.raises(hgis.GuardError):
        guarded._send("PUT", f"/api/projects/{project_id}/view-state", json={})

    assert transport.count == 0


@pytest.mark.parametrize(
    "project_id",
    [
        PROJECT_ID,
        PROJECT_ID.upper(),  # hex digits are case-insensitive
        "00000000-0000-0000-0000-000000000000",
    ],
)
def test_a_real_project_id_is_accepted(transport, project_id) -> None:
    """The check must not be so tight that a legitimate write fails."""
    client = hgis.connect("http://stub", transport=transport)
    client.save_view_state(project_id, {"version": 1, "activeLayerId": None, "layers": {}})
    assert transport.count == 1


def test_the_view_state_write_must_end_at_the_view_state(guarded, transport) -> None:
    """Not a prefix match: the entry names one resource, not a subtree."""
    for path in (
        f"/api/projects/{PROJECT_ID}/view-state/extra",
        f"/api/projects/{PROJECT_ID}/view-state2",
        f"/api/projects/{PROJECT_ID}/layers/order",
        f"/api/projects/{PROJECT_ID}",
    ):
        with pytest.raises(hgis.GuardError):
            guarded._send("PUT", path, json={})

    assert transport.count == 0


@pytest.mark.parametrize(
    "layer_id",
    ["abc", "019fecb8", "019fecb8-6f1d-7f11-abbf-beeeb5953247-extra", "*"],
)
def test_the_layer_write_paths_need_a_real_layer_id(guarded, transport, layer_id) -> None:
    """The same UUID discipline applies to every layer write entry, not only the view state."""
    for method, path in (
        ("PATCH", f"/api/layers/{layer_id}"),
        ("DELETE", f"/api/layers/{layer_id}"),
        ("POST", f"/api/layers/{layer_id}/restore"),
        ("DELETE", f"/api/layers/{layer_id}/purge"),
        ("POST", f"/api/layers/{layer_id}/edits"),
        ("POST", f"/api/layers/{layer_id}/fields"),
    ):
        with pytest.raises(hgis.GuardError):
            guarded._send(method, path, json={})

    assert transport.count == 0


# --- the live channel goes through the same guard ---------------------------


def test_events_are_checked_before_reaching_the_transport() -> None:
    """
    ``RequestGuard.events`` runs the same allowlist check as ``request`` --
    the redirect-hop logic does not apply (see its docstring), but a wrong
    path must still be refused before the network is touched.
    """

    class _RecordingTransport(hgis.Transport):
        def request(self, *args, **kwargs):  # pragma: no cover - not exercised here
            raise AssertionError("request() wurde aufgerufen")

        def events(self, url, *, headers=None, timeout=None):  # pragma: no cover
            raise AssertionError("events() hat den Boden erreicht")

    guard = hgis.RequestGuard(_RecordingTransport())

    with pytest.raises(hgis.GuardError):
        guard.events("http://stub/api/not-events")


def test_the_events_path_itself_is_allowed() -> None:
    """``/api/events`` is a plain GET, so it passes the same rule every other read does."""

    class _Counting(hgis.Transport):
        def request(self, *args, **kwargs):  # pragma: no cover
            raise AssertionError

        def events(self, url, *, headers=None, timeout=None):
            self.opened = url
            return iter(())

    inner = _Counting()
    guard = hgis.RequestGuard(inner)

    guard.events("http://stub/api/events")

    assert inner.opened == "http://stub/api/events"
