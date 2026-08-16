import { vi } from 'vitest'

/**
 * A stand-in for the browser's `EventSource`, which jsdom does not implement at all.
 *
 * It is a stand-in and not a simulation: it does exactly what a test tells it to do, and
 * a test built on it therefore shows how our code reacts to those events -- nothing more.
 * Whether a real browser produces them is a separate question no test in this suite can
 * answer:
 *
 * - that a stream ending (the server's own timeout) is followed by an automatic reconnect,
 * - that a non-200 answer leaves `readyState` at `CLOSED` and is never retried,
 * - that a `retry:` field changes how long the browser waits.
 *
 * All three are the browser's behaviour, and the live channel is built on them. They have
 * to be checked in a real browser, not here.
 */
export class FakeEventSource {
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSED = 2

  /** Every source created since `installFakeEventSource`, oldest first. */
  static instances: FakeEventSource[] = []

  readyState: number = FakeEventSource.CONNECTING
  readonly url: string
  closed = false

  private readonly listeners = new Map<string, ((event: Event) => void)[]>()

  constructor(url: string) {
    this.url = url
    FakeEventSource.instances.push(this)
  }

  addEventListener(type: string, listener: (event: Event) => void) {
    const forType = this.listeners.get(type) ?? []
    forType.push(listener)
    this.listeners.set(type, forType)
  }

  close() {
    this.closed = true
    this.readyState = FakeEventSource.CLOSED
  }

  // --- what a test drives ----------------------------------------------------

  /** The connection is up and carrying events. */
  connect() {
    this.readyState = FakeEventSource.OPEN
    this.dispatch('open', { type: 'open' })
  }

  /** One named event with its data line, exactly as the wire carries it. */
  emit(type: string, data: string) {
    this.dispatch(type, { type, data })
  }

  /**
   * @param fatal what a non-200 answer looks like: the browser has given up and will not
   *   retry by itself. `false` is the ordinary dropped connection, which it does retry.
   */
  fail({ fatal }: { fatal: boolean }) {
    this.readyState = fatal ? FakeEventSource.CLOSED : FakeEventSource.CONNECTING
    this.dispatch('error', { type: 'error' })
  }

  private dispatch(type: string, event: unknown) {
    for (const listener of [...(this.listeners.get(type) ?? [])]) {
      listener(event as Event)
    }
  }
}

/** Puts the stand-in in place of the global `EventSource` and forgets earlier instances. */
export function installFakeEventSource(): typeof FakeEventSource {
  FakeEventSource.instances = []
  vi.stubGlobal('EventSource', FakeEventSource)
  return FakeEventSource
}
