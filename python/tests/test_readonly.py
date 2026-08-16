"""
The guard that keeps this stage from writing.

Not a lock. Anyone who means to write can ``import httpx`` and go around this
library completely, and that is not what this defends against. It defends
against the accidental write: a generic verb on the client reaching an endpoint
that deletes a layer.

That matters more than it looks. The backend has endpoints for deleting a
layer, deleting a project and applying a batch of edits. There is no recycle
bin behind them yet, so a request sent by mistake cannot be taken back.
"""

from __future__ import annotations

import pytest

import hgis
from conftest import LAYER_ID, PROJECT_ID, FakeTransport, stub_server


@pytest.fixture
def guarded(transport: FakeTransport) -> hgis.Client:
    return hgis.connect("http://stub", transport=transport)


# --- what must not get through --------------------------------------------


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("DELETE", f"/api/layers/{LAYER_ID}"),
        ("DELETE", f"/api/projects/{PROJECT_ID}"),
        ("POST", f"/api/layers/{LAYER_ID}/edits"),
        ("POST", f"/api/projects/{PROJECT_ID}/layers"),
        ("PATCH", f"/api/layers/{LAYER_ID}"),
        ("PUT", f"/api/projects/{PROJECT_ID}/layers/order"),
        ("POST", f"/api/layers/{LAYER_ID}/features/1/split"),
        ("DELETE", f"/api/layers/{LAYER_ID}/fields/x"),
    ],
)
def test_a_writing_request_is_refused(guarded, transport, method, path) -> None:
    """
    Every one of these is a real endpoint on the backend.

    Sent through this library, none of them may leave.
    """
    with pytest.raises(hgis.ReadOnlyError):
        guarded._send(method, path, json={})

    assert transport.count == 0, "Die Anfrage hat den Transport erreicht."


def test_the_guard_also_covers_the_transport_itself(guarded, transport) -> None:
    """
    The check sits in front of the floor, not in front of ``get``.

    A check inside :meth:`Client.get` alone would be walked past by anyone
    reaching for ``client._transport`` -- which is one attribute away.
    """
    with pytest.raises(hgis.ReadOnlyError):
        guarded._transport.request("DELETE", f"http://stub/api/projects/{PROJECT_ID}")

    assert transport.count == 0


def test_a_supplied_transport_is_wrapped_too(transport) -> None:
    """
    Substituting the floor is for testing and authentication, not for lifting
    the guard. So the wrapper goes on either way.
    """
    client = hgis.connect("http://stub", transport=transport)
    assert isinstance(client._transport, hgis.ReadOnlyGuard)
    assert client._transport.inner is transport


def test_there_is_no_generic_write_verb() -> None:
    """
    ``put(path, body)`` was the way in: not underscored, on a class that is
    exported, and reachable from ``help(client)`` without looking for it.

    It is gone, replaced by the one operation it existed for.
    """
    assert not hasattr(hgis.Client, "put")
    assert hasattr(hgis.Client, "save_view_state")


def test_the_refusal_says_what_is_allowed(guarded) -> None:
    """The rule everywhere in this API: an error names the way that works."""
    with pytest.raises(hgis.ReadOnlyError) as error:
        guarded._send("DELETE", f"/api/layers/{LAYER_ID}")

    message = str(error.value)
    assert "liest nur" in message
    assert f"DELETE /api/layers/{LAYER_ID}" in message
    assert "project.select()" in message


def test_the_refusal_is_an_hgis_error(guarded) -> None:
    """So ``except hgis.HgisError`` catches it like everything else."""
    with pytest.raises(hgis.HgisError):
        guarded._send("DELETE", f"/api/layers/{LAYER_ID}")


# --- what must still get through ------------------------------------------


def test_reading_is_untouched(guarded, transport) -> None:
    """The guard restricts writing. Reading is what this library is for."""
    project = guarded.project(PROJECT_ID)
    layer = project.layer("Gebäude Speicherstadt")

    assert layer.count() == 1003
    assert len(layer.where('"Höhe" > 10').fids()) == 415
    assert layer.describe().name == "Gebäude Speicherstadt"
    assert transport.count > 0


def test_the_one_write_still_works(transport) -> None:
    """
    ``select()`` is the exception the list is written for.

    A guard that also stopped this would have made the library read-only by
    breaking it, which is not the same thing.
    """
    client = hgis.connect("http://stub", transport=transport)
    project = client.project(PROJECT_ID)

    project.select([1, 2, 3], layer=LAYER_ID)

    written = [request for request in transport.requests if request.method == "PUT"]
    assert len(written) == 1
    assert written[0].path == f"/api/projects/{PROJECT_ID}/view-state"
    assert transport.bodies[-1]["layers"][LAYER_ID]["selection"] == [1, 2, 3]


def test_the_write_is_allowed_only_on_that_one_path(guarded, transport) -> None:
    """
    A PUT is not allowed because it is a PUT, but because of where it goes.

    Layer reordering is also a PUT, and it changes what the user sees on the
    map for good.
    """
    guarded.save_view_state(PROJECT_ID, {"version": 1, "activeLayerId": None, "layers": {}})
    assert transport.count == 1

    with pytest.raises(hgis.ReadOnlyError):
        guarded._send("PUT", f"/api/projects/{PROJECT_ID}/layers/order", json={})
    assert transport.count == 1


def test_the_guard_is_not_a_lock(transport) -> None:
    """
    Stated as a test so nobody mistakes it for one.

    The underlying floor is reachable and does what it is told. What the guard
    removes is the accident, not the option -- and a reader who wants to know
    which of the two this is should find the answer here.
    """
    client = hgis.connect("http://stub", transport=transport)

    # Straight past the guard, on the floor it wraps:
    client._transport.inner.request("DELETE", f"http://stub/api/layers/{LAYER_ID}")

    assert transport.requests[-1].method == "DELETE"


def test_a_test_transport_on_its_own_is_unguarded() -> None:
    """
    The guard belongs to the client, not to the Transport class.

    Which is what lets the suite above build requests that would be refused,
    and assert that they are.
    """
    transport = FakeTransport(stub_server)
    assert not isinstance(transport, hgis.ReadOnlyGuard)
