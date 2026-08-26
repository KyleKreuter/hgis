"""
A long running operation on the server -- today only an import, started by
:meth:`hgis.project.Project.import_file` or
:meth:`hgis.project.Project.import_geoportal`.

The backend runs the write asynchronously (``ImportController``'s own class
docstring says why: a broken file or an implausible CRS has to come back as
an ordinary error, not buried in a job that fails seconds later, so only the
part that actually has to run in the background does). What is returned
right away is a :class:`Job` in ``PENDING``, not the finished layer -- this
module is what a caller waits on instead of polling ``GET /api/jobs/{id}``
by hand.
"""

from __future__ import annotations

import time
from typing import TYPE_CHECKING, Any

from .channel import PROJECT_CATALOG_EVENT, Change

if TYPE_CHECKING:
    from .client import Client

#: Mirrors ``Job.Status`` on the backend. A job never leaves one of these
#: once it reaches it -- see :attr:`Job.done`.
TERMINAL_STATUSES = frozenset({"SUCCEEDED", "FAILED"})


class Job:
    """
    One row of the backend's ``job`` table, as :meth:`refresh` last read it.

    Every field mirrors ``JobDtos.Response`` except :attr:`project_id`,
    which that response does not carry for an import job at all --
    ``outputProjectId`` there is only ever set for a project *duplication*
    (see ``JobService.markDuplicateRunning`` on the backend), never for an
    import. This class is handed the project id instead, by whichever call
    started it (:meth:`hgis.project.Project.import_file` and
    :meth:`hgis.project.Project.import_geoportal` both know it already,
    since they are called on that project), because :meth:`wait` needs it
    to know which project's catalog to listen for.
    """

    def __init__(self, client: "Client", data: dict[str, Any], *, project_id: str) -> None:
        self._client = client
        self._data = data
        self._project_id = project_id

    @property
    def id(self) -> str:
        return self._data["id"]

    @property
    def type(self) -> str:
        """
        ``IMPORT`` today; ``PROCESSING`` and ``DUPLICATE`` exist on the
        backend but nothing here starts one.
        """
        return self._data["type"]

    @property
    def status(self) -> str:
        """``PENDING``, ``RUNNING``, ``SUCCEEDED`` or ``FAILED``."""
        return self._data["status"]

    @property
    def done(self) -> bool:
        """
        Whether :attr:`status` has reached ``SUCCEEDED`` or ``FAILED`` --
        never goes back to False once True.
        """
        return self.status in TERMINAL_STATUSES

    @property
    def succeeded(self) -> bool:
        return self.status == "SUCCEEDED"

    @property
    def failed(self) -> bool:
        return self.status == "FAILED"

    @property
    def filename(self) -> str | None:
        return self._data.get("filename")

    @property
    def processed_count(self) -> int:
        return self._data["processedCount"]

    @property
    def total_count(self) -> int | None:
        """
        None when the format did not know its total up front, or before the
        write has started counting.
        """
        return self._data.get("totalCount")

    @property
    def skipped_count(self) -> int:
        return self._data["skippedCount"]

    @property
    def output_layer_id(self) -> str | None:
        """
        The layer this job is writing into.

        Set as soon as the layer row exists -- at the start of the write,
        not only on success -- so it can already be read while
        :attr:`status` is still ``RUNNING``. Reading the layer before
        :attr:`done` is True sees a table still being filled.
        """
        return self._data.get("outputLayerId")

    @property
    def message(self) -> str | None:
        """
        The failure reason once :attr:`failed`, a warning on an otherwise
        successful job, or None.
        """
        return self._data.get("message")

    def __repr__(self) -> str:
        return f"<hgis.Job {self.id} {self.type} {self.status}>"

    def refresh(self) -> "Job":
        """Re-read this job's current state from the server, and return self."""
        self._data = self._client.get(f"/api/jobs/{self.id}")
        return self

    def wait(self, *, timeout: float | None = None) -> "Job":
        """
        Block until this job reaches ``SUCCEEDED`` or ``FAILED``, and return
        self, refreshed. Still returns after ``timeout`` seconds even if it
        has not finished -- check :attr:`done` on what comes back, and see
        :attr:`id` for following up, either with another :meth:`wait` or a
        fresh ``GET /api/jobs/{id}``.

        **Waits on the live channel, not a polling loop.** The background
        write carries no client header -- nothing started it on behalf
        of any particular client -- so its own writes reach the channel
        with ``origin=None``, and that is the signal this waits for exactly
        as :meth:`hgis.client.Client.wait_for`'s own example does. Every
        matching event triggers a fresh :meth:`refresh`, since the event
        itself carries no content (see ``EventDtos`` on the backend) and a
        catalog change on this job's project is not necessarily *this* job
        finishing -- another write to the same project in the meantime
        would wake this too, and is handled the same way a spurious wakeup
        anywhere else is: read the actual state, and keep waiting if it
        still says so.

        The two limits :meth:`hgis.client.Client.wait_for` itself
        documents -- best-effort past a ``timeout`` shorter than the
        server's heartbeat, and past one longer than its
        ``stream-timeout`` -- apply here unchanged, for the same reason:
        this calls that method underneath.
        """
        self.refresh()
        if self.done:
            return self

        deadline = None if timeout is None else time.monotonic() + timeout
        while True:
            remaining = None if deadline is None else deadline - time.monotonic()
            if remaining is not None and remaining <= 0:
                return self
            match = self._client.wait_for(self._matches_this_job, timeout=remaining)
            self.refresh()
            if self.done:
                return self
            if match is None:
                # wait_for's own deadline elapsed with nothing left to check
                # the state against -- and refresh() just did, and it is
                # still not done.
                return self

    def _matches_this_job(self, item: Any) -> bool:
        """
        Whether a channel item is worth a :meth:`refresh` for this job.

        Not "is this job's own write" in any stronger sense than that --
        the channel does not say which job caused a catalog change, only
        that one happened on this project. See :meth:`wait`'s own docstring
        for why that is still the right thing to wait on.
        """
        return (
            isinstance(item, Change)
            and item.project_id == self._project_id
            and item.name == PROJECT_CATALOG_EVENT
            and item.origin in (self._client.client_id, None)
        )
