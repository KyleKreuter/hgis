"""
Reading the live channel for real: reconnecting, interpreted, and honest
about the one gap reconnecting cannot close.

:mod:`hgis.transport` and :meth:`hgis.client.Client.events` lay the floor: one
connection, the wire-format :class:`~hgis.transport.Event`, nothing
interpreted, nothing retried. That is groundwork on purpose -- see
:meth:`hgis.client.Client.events`'s own docstring. This module is the reader
built on top of it, and does the three things that docstring named as still
missing.

**Reconnecting.** The server's ``stream-timeout`` ends every connection
after a few minutes, cleanly, with an ordinary HTTP 200 -- see
``EventStreams`` on the backend. A browser's ``EventSource`` reconnects by
itself; a plain Python iterator that stops there would be a trap; a script
meant to listen for an hour would fall silent after a few minutes with
nothing that looks like an error. :func:`watch` is what does not do that: it
opens a new connection the moment one ends and keeps yielding from the next
one, so ``for item in client.watch(): ...`` runs for as long as the caller
keeps the loop going.

**The gap a reconnect opens.** Between a drop and the next connection,
events can be missed -- the server keeps no history, and every event states
a state rather than a change (see ``EventDtos`` on the backend), so a missed
event is not queued for later, it is simply gone. Normally that costs
nothing: whatever happened is still reflected in the *next* event, and
nothing here needs to reconstruct what came before it. The one case that
does not self-heal is a change that lands entirely inside the gap, followed
by silence -- then no further event ever says so. :func:`watch` closes this
the way the frontend's own ``onOpen`` does (`frontend/src/api/events.ts`):
every new connection, including the very first one, first yields
:class:`Connected` before anything else, and ``reconnected=True`` on every
one after the first tells the receiver it may have missed something and
ought to reread whatever it cares about through the ordinary API -- not
through this channel, which carries no working data to reread it from.

**The echo.** ``origin`` is :data:`~hgis.client.CLIENT_HEADER` of whoever
wrote it, or None when the write named none -- deliberately, for a
background job such as an import, where the writer's own echo is the only
signal it ever gets that the job is done. Nothing in this module filters by
origin. A caller who wants to skip its own echo compares ``change.origin``
against :attr:`hgis.client.Client.client_id` itself; a caller waiting for
its own echo compares the other way, or accepts ``None`` too. Baking either
policy in here would lock out the other.
"""

from __future__ import annotations

import json as jsonlib
import random
import time
from collections.abc import Callable, Iterator
from dataclasses import dataclass
from typing import TYPE_CHECKING

from .transport import Event, TransportError

if TYPE_CHECKING:
    from .client import Client

#: The SSE ``event:`` name for a project's saved view state -- active layer,
#: per-layer sort, query and selection. Mirrors ``EventDtos.EventNames`` on
#: the backend and ``PROJECT_VIEW_STATE_EVENT`` in the frontend.
PROJECT_VIEW_STATE_EVENT = "project-view-state"

#: The SSE ``event:`` name for a project's catalog -- its layer list, a
#: layer's properties, its style, its data. Mirrors ``EventDtos.EventNames``
#: on the backend and ``PROJECT_CATALOG_EVENT`` in the frontend.
PROJECT_CATALOG_EVENT = "project-catalog"

_KNOWN_EVENT_NAMES = (PROJECT_VIEW_STATE_EVENT, PROJECT_CATALOG_EVENT)


@dataclass(frozen=True)
class Change:
    """
    One project now stands at ``version``, on ``name``.

    Parsed from an :class:`~hgis.transport.Event` the channel dispatched --
    see the module docstring for what ``origin`` means and why it is not
    filtered here. Carries no working data, the same rule the wire format
    itself follows: read the actual content through the ordinary API,
    keyed on ``project_id``.

    :param name: :data:`PROJECT_VIEW_STATE_EVENT` or
        :data:`PROJECT_CATALOG_EVENT`
    :param project_id: which project this is about
    :param version: rises with every write this event's ``name`` covers
    :param origin: the writer's :data:`~hgis.client.CLIENT_HEADER`, or None
        when it named none
    """

    name: str
    project_id: str
    version: int
    origin: str | None


@dataclass(frozen=True)
class Connected:
    """
    The channel is open and delivering events -- yielded by :func:`watch`
    before anything else, once per connection.

    :param reconnected: False exactly once, for the very first connection a
        call to :func:`watch` makes. True for every one after: a reconnect
        opened a gap events may have been lost in, and this is what says so
        -- see the module docstring's "The gap a reconnect opens" for what to
        do about it, and what is already safe to ignore.
    """

    reconnected: bool


#: Everything :func:`watch` can yield.
ChannelItem = Change | Connected

#: First wait after a connection attempt fails, before doubling. Mirrors
#: `frontend/src/api/events.ts`'s ``RECONNECT_BASE_MS`` -- same shape of
#: problem, same answer, so the two clients back off the same way.
RECONNECT_BASE_SECONDS = 2.0

#: The longest this ever waits between attempts. A server that is down or
#: full has to be reachable again eventually. Mirrors the frontend's
#: ``RECONNECT_MAX_MS``.
RECONNECT_MAX_SECONDS = 60.0


def _reconnect_delay(attempt: int, jitter: float | None = None) -> float:
    """
    How long to wait before the reconnect :func:`watch` makes itself, after
    ``attempt`` connections in a row have failed (counting from 0).

    Only for a failed *attempt* -- the server ending a healthy connection at
    its own ``stream-timeout`` is not a failure and is not delayed at all;
    see :func:`watch`. Doubling with a ceiling, so a server that is down or
    full is asked less and less often rather than in a tight loop; the
    jitter keeps several scripts turned away at the same moment from all
    trying again at the same moment. A parameter, not `random.random()`
    called inside, so a test can pin it down instead of working around the
    clock -- the same reason the frontend's ``reconnectDelay`` takes one.
    """
    if jitter is None:
        jitter = random.random()
    capped = min(RECONNECT_BASE_SECONDS * 2 ** max(0, attempt), RECONNECT_MAX_SECONDS)
    return capped * (0.5 + jitter * 0.5)


def _parse_change(event: Event) -> Change | None:
    """
    One dispatched :class:`~hgis.transport.Event` as a :class:`Change`, or
    None when it is not one of :data:`_KNOWN_EVENT_NAMES`, or its ``data``
    does not have the shape ``EventDtos`` promises.

    Dropped rather than raised, the same choice `frontend/src/api/events.ts`
    makes in ``parseVersionEvent``: a stream is long-lived and shared with a
    server that may run older or newer code than this library, so a line
    this version does not recognise is a possibility, not a bug to crash
    over. Whatever it would have said, the next event says again.
    """
    if event.name not in _KNOWN_EVENT_NAMES:
        return None
    try:
        payload = jsonlib.loads(event.data)
    except ValueError:
        return None
    if not isinstance(payload, dict):
        return None

    project_id = payload.get("projectId")
    version = payload.get("version")
    origin = payload.get("origin")
    if not isinstance(project_id, str):
        return None
    # bool is a subclass of int in Python; a JSON `true`/`false` must not
    # pass as a version number.
    if not isinstance(version, int) or isinstance(version, bool):
        return None
    if origin is not None and not isinstance(origin, str):
        return None

    return Change(name=event.name, project_id=project_id, version=version, origin=origin)


def watch(
    client: "Client",
    *,
    timeout: float | None = None,
) -> Iterator[ChannelItem]:
    """
    The live channel, reconnecting for as long as the caller keeps reading.

    >>> for item in client.watch():
    ...     if isinstance(item, Change) and item.name == PROJECT_CATALOG_EVENT:
    ...         print(item.project_id, "steht jetzt bei", item.version)

    Never returns on its own -- a script meant to listen for an hour just
    keeps iterating. Stop it the ordinary way, by breaking out of the loop
    or letting it go out of scope; either closes the underlying connection
    promptly rather than waiting on garbage collection.

    Every :class:`Event` this reads comes from one call to
    :meth:`hgis.client.Client.events`, forwarding ``timeout`` -- see that
    method for what it bounds (the connection, and how long a single read
    may stay silent; not the stream's total lifetime). A connection that
    ends cleanly -- the server's own ``stream-timeout`` -- is reopened
    immediately, no backoff: that is a healthy, expected end, not a failure.
    A connection that fails to open, or breaks while open
    (:class:`~hgis.transport.TransportError`), is retried after
    :func:`_reconnect_delay`, doubling on repeated failures.

    Every event this library recognises (:data:`PROJECT_VIEW_STATE_EVENT`,
    :data:`PROJECT_CATALOG_EVENT`) becomes a :class:`Change`, in order,
    across every reconnect; anything else is dropped, see
    :func:`_parse_change`. Before the first of those on every connection --
    including the very first one, and including a connection that ends
    without ever carrying a recognised event -- a :class:`Connected` is
    yielded first. See its own docstring and the module docstring's "The
    gap a reconnect opens" for what ``reconnected=True`` means and what a
    receiver that cares about missing nothing should do with it.

    The channel is project-spanning, one connection for every project on the
    server -- filtering to the one a caller cares about is this function's
    job to make easy, not the caller's job to reinvent; see :func:`for_project`.

    :raises hgis.errors.GuardError: the client's transport refuses the one
        path this opens (:class:`hgis.client.RequestGuard`) -- a
        misconfigured ``base_url`` or a substituted transport, not something
        a running connection can trigger later
    """
    reconnected = False
    failures = 0

    while True:
        stream = client.events(timeout=timeout)
        signalled = False
        try:
            for event in stream:
                if not signalled:
                    yield Connected(reconnected=reconnected)
                    signalled = True
                    reconnected = True
                    failures = 0
                change = _parse_change(event)
                if change is not None:
                    yield change
            if not signalled:
                # The connection opened and ended cleanly without ever
                # carrying a recognised event -- an idle stream-timeout
                # window. Nothing was necessarily missed (nothing happened
                # for this to say), but a receiver that reread on every
                # Connected regardless still gets a chance to notice.
                yield Connected(reconnected=reconnected)
                reconnected = True
                failures = 0
            # A clean end is the server's own stream-timeout, not a failure:
            # reconnect right away, same as a browser's EventSource would.
        except TransportError:
            delay = _reconnect_delay(failures)
            failures += 1
            time.sleep(delay)
        finally:
            stream.close()


def for_project(
    project_id: str,
    *,
    name: str | None = None,
) -> Callable[[ChannelItem], bool]:
    """
    A predicate matching a :class:`Change` for one project -- for
    :func:`wait_for`, or for a plain ``if`` in a :func:`watch` loop, since
    what this returns is an ordinary callable either way.

    Never filters by ``origin`` -- see the module docstring for why. Add
    ``and item.origin != client.client_id`` (skip this client's own echo) or
    ``and item.origin in (client.client_id, None)`` (wait for it, or for a
    write that named none) to what this returns, or write the check by hand;
    both are one line, and this function is not the place to choose between
    them for you.

    :param name: :data:`PROJECT_VIEW_STATE_EVENT` or
        :data:`PROJECT_CATALOG_EVENT` to match only that kind of change;
        None to match either.
    """

    def predicate(item: ChannelItem) -> bool:
        return (
            isinstance(item, Change)
            and item.project_id == project_id
            and (name is None or item.name == name)
        )

    return predicate


def wait_for(
    client: "Client",
    predicate: Callable[[ChannelItem], bool],
    *,
    timeout: float | None = None,
) -> ChannelItem | None:
    """
    Block on :func:`watch` until ``predicate`` matches something it yields,
    and return that. None once ``timeout`` seconds have passed without a
    match; forever when ``timeout`` is None.

    ``predicate`` sees everything :func:`watch` yields, :class:`Connected`
    included -- matching on it directly is how a caller notices "the
    channel just (re)connected" rather than any particular change; matching
    with :func:`for_project` is how a caller waits for one project.

    >>> # Wait for the import this client itself started to finish. It
    >>> # writes with no origin at all (see the module docstring), so this
    >>> # waits for either that or this client's own name -- not "any write
    >>> # that is not mine", which is what for_project() alone would give.
    >>> match = client.wait_for(
    ...     lambda item: (
    ...         isinstance(item, Change)
    ...         and item.project_id == project.id
    ...         and item.name == PROJECT_CATALOG_EVENT
    ...         and item.origin in (client.client_id, None)
    ...     ),
    ...     timeout=120,
    ... )

    **Best-effort on the deadline, not exact.** The wait can only recheck
    ``timeout`` when something arrives from :func:`watch` to recheck it
    against -- and the heartbeat that keeps an otherwise idle connection
    from looking dead also keeps it from yielding anything at all. ``timeout``
    is also forwarded as the connection's own read timeout (see
    :meth:`hgis.client.Client.events`), so a ``timeout`` shorter than the
    server's heartbeat interval (25s by default) bounds the wait tightly: a
    silent connection times out and reconnects well before the deadline, and
    :class:`Connected` gives the next chance to recheck it. Left at its
    default (None, which falls back to the client's own connect timeout,
    30s by default -- comfortably longer than one heartbeat) or set past
    that interval, a heartbeat can keep an otherwise idle connection open
    indefinitely, and the first thing to recheck the deadline against may
    then be the :class:`Connected` that only arrives once the server's own
    ``stream-timeout`` ends that connection (five minutes by default) -- so
    the wait can run past its deadline, by as much as one ``stream-timeout``
    cycle. Named here rather than hidden: closing it exactly would need a
    watchdog thread this synchronous library does not otherwise have. In
    practice this matters little for what ``wait_for`` is for -- waiting on
    something that is expected to actually happen, such as a job already
    running -- and not at all once the first real event arrives, since every
    yielded item is a fresh chance to recheck the deadline.
    """
    deadline = None if timeout is None else time.monotonic() + timeout
    stream = watch(client, timeout=timeout)
    try:
        for item in stream:
            if predicate(item):
                return item
            if deadline is not None and time.monotonic() >= deadline:
                return None
    finally:
        stream.close()
    return None  # pragma: no cover -- watch() does not stop on its own
