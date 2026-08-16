"""What can go wrong, as four exceptions.

Every one of them is an :class:`HgisError`, so a caller who wants to catch
everything this library raises needs one name.

The server already writes messages that name what would have been valid --
"Unbekanntes Feld: hoehe. Verfügbar: höhe, breite, flaeche." -- and
:class:`ApiError` carries that sentence through unchanged. Wrapping it in a
message of our own would replace the only text that says what to do next.

Messages raised here are German, like the server's. The code around them is
English. That is the same split the backend uses.
"""

from __future__ import annotations


class HgisError(Exception):
    """Base for everything this library raises."""


class TransportError(HgisError):
    """The request never produced an answer: no server, no route, a timeout."""


class ApiError(HgisError):
    """
    The server answered with an error status.

    ``str(error)`` is the server's own ``detail`` -- the sentence that names the
    valid fields, the actual count, the allowed maximum -- and nothing else.

    :param status: HTTP status code
    :param detail: RFC 7807 ``detail``, the server's message
    :param title: RFC 7807 ``title``, e.g. "Ungültige Anfrage"
    :param instance: the path that produced the error
    """

    def __init__(
        self,
        status: int,
        detail: str,
        title: str | None = None,
        instance: str | None = None,
    ) -> None:
        super().__init__(detail)
        self.status = status
        self.detail = detail
        self.title = title
        self.instance = instance

    def __str__(self) -> str:
        return self.detail


class NotFoundError(ApiError):
    """A project, layer or feature the server does not have (HTTP 404)."""


class ConflictError(ApiError):
    """
    A write collided with another one (HTTP 409).

    Raised by :meth:`hgis.layer.Layer.edit` and the convenience methods built
    on it, when the ``row_version`` sent with an update or a delete no longer
    matches the row -- someone else wrote it in the meantime.

    :param current: the row as it stands on the server right now, in the same
        shape ``GET /api/layers/{id}/features/{fid}`` returns -- ``fid``,
        ``properties``, ``geometry``, ``rowVersion``. Read it, decide, and try
        again with the fresh ``rowVersion``. None when the server's answer did
        not carry one.
    """

    def __init__(
        self,
        status: int,
        detail: str,
        title: str | None = None,
        instance: str | None = None,
        current: dict | None = None,
    ) -> None:
        super().__init__(status, detail, title, instance)
        self.current = current


class UnknownNameError(HgisError, LookupError):
    """
    A name that matches no project or layer in what was actually there.

    Also a :class:`LookupError`, because that is what a lookup by name failing
    is -- ``except LookupError`` catches it, and so does ``except HgisError``.

    The message names the available ones, the same way the server's filter
    errors do.
    """


class InvalidClientIdError(HgisError, ValueError):
    """
    The client name does not fit what the server accepts.

    Also a :class:`ValueError`, because that is what a bad constructor argument
    is. Raised when the client is built rather than when it first writes: a
    name that cannot travel is a mistake in the calling program, and finding it
    at the first write means finding it late.
    """


class GuardError(HgisError):
    """
    A request that :class:`hgis.client.RequestGuard` refused before it reached
    the server.

    Not a lock -- anyone who means to write can import an HTTP library and go
    around this library entirely. It is a guard against the accidental one: a
    request nobody meant to send reaching a real endpoint, or a redirect
    quietly turning a checked request into an unchecked one. What is allowed
    grows with what this library can do; see :class:`hgis.client.RequestGuard`
    for the current list.

    Two things a deletion still cannot undo, guard or not: a *layer* deleted
    with :meth:`hgis.layer.Layer.purge` (its :meth:`hgis.layer.Layer.delete`
    only moves it to the project's trash, and stays reversible until purged),
    and an *object* deleted through :meth:`hgis.layer.Layer.edit` -- the
    server's own change log is the only way back for those, never this
    library. See the README's "Was unwiederbringlich ist".

    The message names the request and, where useful, what is allowed instead.
    """


class MissingDependencyError(HgisError, ImportError):
    """
    An optional package is not installed.

    Also an :class:`ImportError` so ``except ImportError`` around an optional
    feature works. The message names the package and the command to install it.
    """
