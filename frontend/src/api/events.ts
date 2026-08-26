/**
 * The live channel: `GET /api/events`, Server-Sent Events.
 *
 * Two rules hold for everything that arrives here, and they are what makes the rest of
 * this file as short as it is:
 *
 * 1. **An event reports a state, never a change.** "Project X now stands at working-state
 *    version 42", never "something was selected in X". Hearing the same state twice
 *    therefore costs a repeated read and nothing else, and missing one is made good by
 *    the next -- which is why nothing here counts, orders or acknowledges anything.
 * 2. **An event carries no working data.** Only identifiers and numbers. Whoever hears
 *    one reads the content through the ordinary API, so this file stays independent of
 *    every later change to that content's format.
 *
 * The transport itself is the browser's `EventSource`, which reconnects on its own after
 * a dropped connection. That is deliberately left to it; see `connectLiveChannel` for the
 * one case it does not cover.
 */

/** The SSE `event:` name the backend sends. Its own type -- the data carries none. */
export const PROJECT_VIEW_STATE_EVENT = 'project-view-state'

/**
 * The SSE `event:` name the backend sends for a project's *data* -- the layer catalog,
 * and any layer's data/style/clip/render version (`map/layerSpecs.ts#buildTileUrl`).
 */
export const PROJECT_CATALOG_EVENT = 'project-catalog'

/**
 * The SSE `event:` name the backend sends for a project's own map viewport -- its
 * `center` and `zoom`, the two fields `GET /api/projects/{id}` carries directly. Neither
 * the working state above (a client's own local address into the project, never a
 * column of the project itself) nor the data-state one (the layer catalog: list, style,
 * data) covers this, hence a name of its own (TASKS.md Aufgabe 9).
 */
export const PROJECT_VIEWPORT_EVENT = 'project-viewport'

/** Header naming this client on a write, so its own change comes back recognisable. */
export const CLIENT_HEADER = 'X-Hgis-Client'

/**
 * This tab's name on the channel, for the lifetime of the page.
 *
 * Per tab, not per browser and not per user: two tabs on the same project are two
 * independent viewers and each has to hear the other. `crypto.randomUUID` needs a secure
 * context, which plain http on a local network is not, hence the fallback -- both stay
 * within what the server accepts for the header (letters, digits, hyphen, underscore).
 */
export const CLIENT_ID: string = newClientId()

function newClientId(): string {
  const uuid = globalThis.crypto?.randomUUID?.()
  if (uuid) return uuid
  return `c${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`
}

/**
 * The shape shared by every version event this channel carries: which project, which
 * version, and who wrote it. `ProjectViewStateEvent` and `ProjectDataStateEvent` below
 * are this one shape under two names -- one per concept the two events report on, not
 * two wire formats that would have to be kept in sync by hand.
 */
interface VersionEvent {
  projectId: string
  version: number
  /** The `CLIENT_ID` of whoever wrote it, or null when they named none. */
  origin: string | null
}

/**
 * A version event's data, or null when it cannot be read.
 *
 * A stream is a long-lived thing shared with servers and proxies that may be older or
 * newer than this page, so an unreadable line is a possibility rather than an accident.
 * It is dropped: the next event states the same thing again.
 */
function parseVersionEvent(data: string): VersionEvent | null {
  let parsed: unknown
  try {
    parsed = JSON.parse(data)
  } catch {
    return null
  }
  if (typeof parsed !== 'object' || parsed === null) return null
  const candidate = parsed as Record<string, unknown>
  if (typeof candidate.projectId !== 'string') return null
  if (typeof candidate.version !== 'number') return null
  const origin = candidate.origin
  if (origin !== null && origin !== undefined && typeof origin !== 'string') return null
  return { projectId: candidate.projectId, version: candidate.version, origin: origin ?? null }
}

/** A project's working state now stands at `version`. Read it, do not infer from it. */
export type ProjectViewStateEvent = VersionEvent

export function parseProjectViewState(data: string): ProjectViewStateEvent | null {
  return parseVersionEvent(data)
}

/**
 * A project's *data* now stands at `version` -- the layer catalog, or any one layer's
 * data/style/clip/render version. Same read-it-do-not-infer-it rule as
 * {@link ProjectViewStateEvent}; `state/useLiveDataState.ts` is what this triggers.
 */
export type ProjectDataStateEvent = VersionEvent

export function parseProjectDataState(data: string): ProjectDataStateEvent | null {
  return parseVersionEvent(data)
}

/**
 * The shape of an event that names only a project and who wrote it -- no version, since
 * there is nothing to catch up on incrementally: the receiver simply rereads the project.
 * `ProjectViewportEvent` below is the only kind that needs this; `VersionEvent` above
 * still covers the other two.
 */
interface ProjectEvent {
  projectId: string
  /** The `CLIENT_ID` of whoever wrote it, or null when they named none. */
  origin: string | null
}

function parseProjectEvent(data: string): ProjectEvent | null {
  let parsed: unknown
  try {
    parsed = JSON.parse(data)
  } catch {
    return null
  }
  if (typeof parsed !== 'object' || parsed === null) return null
  const candidate = parsed as Record<string, unknown>
  if (typeof candidate.projectId !== 'string') return null
  const origin = candidate.origin
  if (origin !== null && origin !== undefined && typeof origin !== 'string') return null
  return { projectId: candidate.projectId, origin: origin ?? null }
}

/**
 * This project's own map viewport -- center and zoom -- changed. Read it from
 * `GET /api/projects/{projectId}`, which carries both directly; `map/RemoteViewport.tsx`
 * is what this triggers.
 */
export type ProjectViewportEvent = ProjectEvent

export function parseProjectViewport(data: string): ProjectViewportEvent | null {
  return parseProjectEvent(data)
}

/**
 * Whether an event asks this client to read the state back.
 *
 * The one case where it does not is an event this client's own write produced: it already
 * holds that state, and reading it back would overwrite whatever the user has done in the
 * meantime -- visible as a flicker, and, since applying a state is itself worth saving, as
 * a write that provokes the next event. This is not ignoring the news; it is already
 * having it.
 */
export function shouldReadBack(
  event: ProjectViewStateEvent,
  { projectId, clientId }: { projectId: string; clientId: string },
): boolean {
  if (event.projectId !== projectId) return false
  return event.origin !== clientId
}

/**
 * Whether a *data*-state event is worth reacting to -- scoped to this project, and
 * nothing more.
 *
 * Deliberately not filtered by origin the way {@link shouldReadBack} filters a
 * working-state event. That filter exists to break a write/read loop: applying a
 * working-state read back to the map is itself worth saving, so skipping a client's own
 * echo of it is what keeps the loop from ever starting. A data-state refetch never
 * writes anything back -- it only asks the catalog what it now holds -- so there is no
 * loop here to break, and suppressing the echo has nothing to gain.
 *
 * It has something to lose, though. Several writes -- an import running as a background
 * job above all -- answer with 202-style "started", not with the result, and today do
 * not even populate `origin` along the way (measured across six write paths: reordering,
 * a field rename, adding a Kartenbild, import, duplicating a project, and every project
 * route but the working state). For a background import, this client's own echo *is*
 * the only way it ever learns the import finished -- the tab that started it has no
 * other signal. Filtering that echo out the way `shouldReadBack` does would mean the one
 * client waiting on the news never gets it. A style change is the opposite case: its
 * writer already holds the result in the PATCH response, so a read-back on its own echo
 * is unneeded -- but, since it only costs one redundant, already-debounced refetch and
 * never a write, "unneeded" here is harmless where "unneeded" on the working-state side
 * would not be.
 */
export function isForThisProject(event: ProjectDataStateEvent, projectId: string): boolean {
  return event.projectId === projectId
}

/**
 * Whether a viewport event is worth reacting to -- scoped to this project, and filtered
 * by origin, exactly like {@link shouldReadBack} and for the same reason: unlike a
 * data-state refetch, `map/RemoteViewport.tsx`'s `easeTo` feeds straight back into this
 * client's own `ViewportPersistence` save. Left unfiltered, this client would answer its
 * own change with a refetch and a re-save of the very value it just wrote -- the same
 * write/read loop `shouldReadBack` exists to break for the working-state event.
 */
export function shouldFollowRemoteViewport(
  event: ProjectViewportEvent,
  { projectId, clientId }: { projectId: string; clientId: string },
): boolean {
  if (event.projectId !== projectId) return false
  return event.origin !== clientId
}

/** First wait after the browser gives up on its own, before doubling. */
const RECONNECT_BASE_MS = 2000
/** The longest this client ever waits. A full server has to be reachable again eventually. */
const RECONNECT_MAX_MS = 60_000

/**
 * How long to wait before the reconnect this module has to make itself.
 *
 * Doubling with a ceiling, so a server that is down or full is asked less and less often
 * rather than in a tight loop. The jitter is what keeps several tabs that were turned away
 * at the same moment from coming back at the same moment; it is a parameter so a test can
 * pin it down instead of working around the clock.
 *
 * @param attempt how many times in a row the connection has failed, counting from 0
 */
export function reconnectDelay(attempt: number, jitter: number = Math.random()): number {
  const capped = Math.min(RECONNECT_BASE_MS * 2 ** Math.max(0, attempt), RECONNECT_MAX_MS)
  return Math.round(capped * (0.5 + jitter * 0.5))
}

/** The part of `EventSource` this module uses; the browser's own satisfies it. */
interface LiveSource {
  readyState: number
  addEventListener(type: string, listener: (event: Event) => void): void
  close(): void
}

/** `EventSource.CLOSED`. Named here because the stand-in in the tests is not one. */
const SOURCE_CLOSED = 2

export interface LiveChannelHandlers {
  /**
   * A stream is open and carrying events.
   *
   * @param reconnected false the first time, true for every connection after it. A
   *   reconnect means events may have been missed while the stream was down, and since
   *   nothing is replayed, the receiver has to read the current state once here. The
   *   first connection needs no such read: whatever opened the page has just loaded it.
   */
  onOpen?: (reconnected: boolean) => void
  onProjectViewState?: (event: ProjectViewStateEvent) => void
  /**
   * A project's data changed -- a layer's data, style, clip or render version, or the
   * layer catalog itself (`PROJECT_CATALOG_EVENT`). Parsed the same way as the event
   * above and handed over whole; `state/useLiveDataState.ts` (wired in through
   * `useLiveViewState`'s own options, since the connection is one resource per open
   * project) is what decides what it means.
   */
  onProjectDataState?: (event: ProjectDataStateEvent) => void
  /**
   * This project's own map viewport -- center and zoom -- changed. Parsed the same way
   * as the two events above and handed over whole; `state/useLiveViewState.ts` (wired
   * in through its own options, the same connection this shares with everything else)
   * is what decides what it means.
   */
  onProjectViewport?: (event: ProjectViewportEvent) => void
}

/**
 * Opens the live channel and keeps it open.
 *
 * Reconnecting is the browser's job and is left to it: an SSE stream that ends -- because
 * the server's own timeout ended it, or the network dropped -- is reopened by
 * `EventSource` after the interval the server named in `retry:`. Nothing here interferes
 * with that; `readyState` is what tells the two apart.
 *
 * The one case the browser does not cover is a response that is not 200: it treats that
 * as final and never tries again. That is what a full server answers (503), so this
 * schedules its own reconnect for it -- with a growing delay, because retrying a server
 * that is turning clients away is the problem rather than the cure.
 *
 * @returns a function that closes the stream and cancels any pending reconnect. Calling
 *   it is what leaving the page must do: a stream left open holds one of the browser's
 *   few connections to the server for as long as the tab lives.
 */
export function connectLiveChannel(handlers: LiveChannelHandlers): () => void {
  const EventSourceCtor = globalThis.EventSource
  if (!EventSourceCtor) {
    // No transport, so no channel: the application keeps working, it simply hears nothing.
    return () => {}
  }

  let source: LiveSource | null = null
  let timer: ReturnType<typeof setTimeout> | null = null
  let attempt = 0
  let opened = false
  let disposed = false

  function open() {
    timer = null
    if (disposed) return
    const next: LiveSource = new EventSourceCtor('/api/events')
    source = next

    next.addEventListener('open', () => {
      handlers.onOpen?.(opened)
      opened = true
      // Only a connection that actually opened clears the backoff. Counting a failed
      // attempt as progress is what turns a growing delay back into a tight loop.
      attempt = 0
    })

    next.addEventListener(PROJECT_VIEW_STATE_EVENT, (event) => {
      const parsed = parseProjectViewState((event as MessageEvent<string>).data)
      if (parsed) handlers.onProjectViewState?.(parsed)
    })

    next.addEventListener(PROJECT_CATALOG_EVENT, (event) => {
      const parsed = parseProjectDataState((event as MessageEvent<string>).data)
      if (parsed) handlers.onProjectDataState?.(parsed)
    })

    next.addEventListener(PROJECT_VIEWPORT_EVENT, (event) => {
      const parsed = parseProjectViewport((event as MessageEvent<string>).data)
      if (parsed) handlers.onProjectViewport?.(parsed)
    })

    next.addEventListener('error', () => {
      // Still CONNECTING means the browser is already retrying by itself -- the ordinary
      // case, and stepping in here would open a second stream alongside the one it is
      // about to make.
      if (next.readyState !== SOURCE_CLOSED || disposed) return
      next.close()
      if (timer !== null) return
      timer = setTimeout(open, reconnectDelay(attempt))
      attempt += 1
    })
  }

  open()

  return () => {
    disposed = true
    if (timer !== null) clearTimeout(timer)
    timer = null
    source?.close()
    source = null
  }
}
