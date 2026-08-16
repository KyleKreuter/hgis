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


class UnknownNameError(HgisError, LookupError):
    """
    A name that matches no project or layer in what was actually there.

    Also a :class:`LookupError`, because that is what a lookup by name failing
    is -- ``except LookupError`` catches it, and so does ``except HgisError``.

    The message names the available ones, the same way the server's filter
    errors do.
    """


class ReadOnlyError(HgisError):
    """
    A request that would change data, which this stage does not do.

    Not a lock -- anyone who means to write can import an HTTP library and go
    around this library entirely. It is a guard against the accidental one: a
    generic ``put`` or ``_send("DELETE", ...)`` reaching a real endpoint. That
    matters most right now, because there is no undo behind the API and no
    recycle bin: a deletion is final the moment it arrives.

    The message names the request and the one write that is allowed.
    """


class MissingDependencyError(HgisError, ImportError):
    """
    An optional package is not installed.

    Also an :class:`ImportError` so ``except ImportError`` around an optional
    feature works. The message names the package and the command to install it.
    """
