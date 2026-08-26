"""
:class:`hgis.jobs.Job`, and the one behaviour that matters most about it:
:meth:`hgis.jobs.Job.wait` blocks on the live channel, not on a loop that
polls ``GET /api/jobs/{id}`` on a timer.

That distinction is the whole point of the class -- see its own module
docstring -- and it is also the easiest thing here to quietly regress: a
loop of ``while not job.done: sleep(...); job.refresh()`` would pass every
test that only checks the *outcome* of :meth:`~hgis.jobs.Job.wait`. Every
test below that exercises :meth:`~hgis.jobs.Job.wait` therefore also asserts
``transport.events_opened >= 1`` -- proof the live channel was actually
read, not merely available. Demonstrated, not assumed: see
``test_a_polling_wait_would_be_caught`` for the mutation that makes this
observable.
"""

from __future__ import annotations

import json as jsonlib
from dataclasses import dataclass, field
from typing import Iterator

import hgis
from conftest import PROJECT_ID
from hgis.channel import PROJECT_CATALOG_EVENT
from hgis.transport import Event, Response, Transport

JOB_ID = "019ff9aa-8888-9999-0000-111122223333"
LAYER_ID = "019ff9aa-9999-0000-1111-222233334444"


def _job_body(status: str, **overrides: object) -> str:
    body = {
        "id": JOB_ID,
        "type": "IMPORT",
        "status": status,
        "filename": "baeume.geojson",
        "processedCount": 0,
        "totalCount": None,
        "skippedCount": 0,
        "outputLayerId": None,
        "outputProjectId": None,
        "message": None,
        "startedAt": None,
        "finishedAt": None,
        "createdAt": "2026-01-01T00:00:00Z",
    }
    body.update(overrides)
    return jsonlib.dumps(body)


def _catalog_change(*, project_id: str = PROJECT_ID, origin: str | None = None) -> Event:
    """A raw SSE event, the shape :func:`hgis.channel._parse_change` reads."""
    return Event(
        name=PROJECT_CATALOG_EVENT,
        data=jsonlib.dumps({"projectId": project_id, "version": 2, "origin": origin}),
    )


@dataclass
class _JobTransport(Transport):
    """
    Answers ``GET /api/jobs/{id}`` from a queue of prepared bodies, and
    ``events()`` from one scripted connection -- enough to drive
    :meth:`hgis.jobs.Job.wait` without a socket.

    ``events_opened`` is the load-bearing counter here: it is what tells a
    test whether :meth:`~hgis.jobs.Job.wait` actually read the channel, as
    opposed to a polling loop that would never call this at all.
    """

    job_bodies: list[str]
    channel_items: list[Event] = field(default_factory=list)
    events_opened: int = 0
    requests: list[str] = field(default_factory=list)

    def request(
        self, method: str, url: str, json: object = None, file: object = None,
        timeout: float = 30.0, headers: dict[str, str] | None = None,
    ) -> Response:
        self.requests.append(url)
        if not self.job_bodies:
            raise AssertionError(f"Mehr GET-Aufrufe als vorbereitete Antworten: {url}")
        return Response(200, self.job_bodies.pop(0))

    def events(
        self, url: str, *, headers: dict[str, str] | None = None, timeout: float | None = None
    ) -> Iterator[Event]:
        self.events_opened += 1
        # A generator, not a plain list iterator: watch() closes the stream
        # it opened, and a bare list_iterator has no .close() -- a real
        # floor's events() is always a generator, so this matches that shape
        # instead of a shortcut that only happens to work here.
        return (item for item in self.channel_items)


def _client(transport: _JobTransport) -> hgis.Client:
    return hgis.connect("http://stub", transport=transport, client_id="agent-a")


def test_wait_returns_immediately_when_already_done() -> None:
    """No channel needed at all when a fresh refresh already shows a terminal status."""
    transport = _JobTransport(job_bodies=[_job_body("SUCCEEDED", outputLayerId=LAYER_ID)])
    client = _client(transport)
    job = hgis.Job(client, jsonlib.loads(_job_body("PENDING")), project_id=PROJECT_ID)

    result = job.wait(timeout=5)

    assert result is job
    assert job.done and job.succeeded
    assert job.output_layer_id == LAYER_ID
    assert transport.events_opened == 0
    assert len(transport.requests) == 1


def test_wait_blocks_on_the_catalog_event_then_refreshes() -> None:
    """
    The shape the whole class exists for: PENDING, an unrelated-looking
    catalog change with no origin (a background job's own signature -- see
    the module docstring of :mod:`hgis.channel`), then a refresh that shows
    it done.
    """
    transport = _JobTransport(
        job_bodies=[_job_body("PENDING"), _job_body("SUCCEEDED", outputLayerId=LAYER_ID)],
        channel_items=[_catalog_change()],
    )
    client = _client(transport)
    job = hgis.Job(client, jsonlib.loads(_job_body("PENDING")), project_id=PROJECT_ID)

    result = job.wait(timeout=5)

    assert result is job
    assert job.succeeded
    assert job.output_layer_id == LAYER_ID
    assert transport.events_opened == 1, "wait() muss den Live-Kanal lesen, nicht abfragen."
    assert len(transport.requests) == 2, "ein refresh() vor und eins nach dem Ereignis"


def test_wait_ignores_a_catalog_change_for_a_different_project() -> None:
    """
    A change on some other project is not this job finishing -- it must not
    even trigger a refresh. ``_matches_this_job`` filters on ``project_id``
    itself, so ``wait_for`` simply keeps scanning past it within the same
    connection until the matching change arrives -- one connection, one
    refresh afterwards, not one refresh per event on the wire.
    """
    other_project = "019fecc1-48a2-76b7-8732-019e83d5532a"
    transport = _JobTransport(
        job_bodies=[_job_body("PENDING"), _job_body("SUCCEEDED", outputLayerId=LAYER_ID)],
        channel_items=[_catalog_change(project_id=other_project), _catalog_change()],
    )
    client = _client(transport)
    job = hgis.Job(client, jsonlib.loads(_job_body("PENDING")), project_id=PROJECT_ID)

    result = job.wait(timeout=5)

    assert result.succeeded
    assert transport.events_opened == 1, "beide Ereignisse kommen über dieselbe Verbindung."
    assert len(transport.requests) == 2, "die fremde Änderung darf keinen refresh() auslösen"


def test_wait_accepts_the_clients_own_client_id_as_the_jobs_echo() -> None:
    """
    ``origin`` is None for the background write itself, but this client may
    also be the one that *started* the import -- see
    ``hgis.channel.wait_for``'s own worked example, which this mirrors.
    """
    transport = _JobTransport(
        job_bodies=[_job_body("PENDING"), _job_body("SUCCEEDED", outputLayerId=LAYER_ID)],
        channel_items=[_catalog_change(origin="agent-a")],
    )
    client = _client(transport)
    job = hgis.Job(client, jsonlib.loads(_job_body("PENDING")), project_id=PROJECT_ID)

    assert job.wait(timeout=5).succeeded


def test_wait_gives_up_after_the_deadline_and_reports_the_current_state() -> None:
    """
    Nothing on the channel ever matches -- ``wait()`` still returns, with
    the job left exactly as unfinished as the last refresh found it, rather
    than blocking forever or raising.
    """
    transport = _JobTransport(job_bodies=[_job_body("PENDING"), _job_body("RUNNING")])
    client = _client(transport)
    job = hgis.Job(client, jsonlib.loads(_job_body("PENDING")), project_id=PROJECT_ID)

    result = job.wait(timeout=0.15)

    assert result is job
    assert not job.done
    assert job.status == "RUNNING"
    assert transport.events_opened >= 1
    assert len(transport.requests) == 2, "genau ein refresh() vor und eins nach der Frist"


def test_refresh_re_reads_the_job_and_returns_self() -> None:
    transport = _JobTransport(job_bodies=[_job_body("RUNNING", processedCount=12)])
    client = _client(transport)
    job = hgis.Job(client, jsonlib.loads(_job_body("PENDING")), project_id=PROJECT_ID)

    result = job.refresh()

    assert result is job
    assert job.status == "RUNNING"
    assert job.processed_count == 12
    assert transport.requests == [f"http://stub/api/jobs/{JOB_ID}"]


def test_failed_carries_the_message() -> None:
    client = _client(_JobTransport(job_bodies=[]))
    job = hgis.Job(
        client, jsonlib.loads(_job_body("FAILED", message="Unbekannte Zeichenkodierung: latin7")),
        project_id=PROJECT_ID,
    )

    assert job.failed and not job.succeeded and job.done
    assert job.message == "Unbekannte Zeichenkodierung: latin7"


def test_repr_names_id_type_and_status() -> None:
    client = _client(_JobTransport(job_bodies=[]))
    job = hgis.Job(client, jsonlib.loads(_job_body("RUNNING")), project_id=PROJECT_ID)

    assert repr(job) == f"<hgis.Job {JOB_ID} IMPORT RUNNING>"
