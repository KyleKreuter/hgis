import { describe, expect, it } from 'vitest'
import { MutationObserver, QueryClient } from '@tanstack/react-query'
import { stubFetch } from '@/test/render'
import { CLIENT_HEADER, CLIENT_ID } from './events'
import {
  applyProjectPatch,
  projectKeys,
  projectUpdateOptions,
  reconcileProject,
  type ProjectDetail,
  type ProjectPatchContext,
  type UpdateProjectInput,
} from './projects'

const ID = 'p1'

function project(overrides: Partial<ProjectDetail> = {}): ProjectDetail {
  return {
    id: ID,
    name: 'Musterstadt',
    description: null,
    srid: 25832,
    layerCount: 2,
    featureCount: 40,
    lastOpenedAt: null,
    createdAt: '2026-01-01T00:00:00Z',
    basemap: 'osm',
    basemapOpacity: 1,
    center: null,
    zoom: null,
    extent: null,
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

describe('applyProjectPatch', () => {
  it('übernimmt genau die Felder des Patches', () => {
    const patched = applyProjectPatch(project(), { basemap: 'opentopo', zoom: 12 })

    expect(patched).toMatchObject({ basemap: 'opentopo', zoom: 12, name: 'Musterstadt' })
  })

  /**
   * `description: undefined` heißt "nicht Teil dieses Patches", `description: null`
   * heißt "ausdrücklich leer". Ein einfaches Spread verwechselt beides und schriebe
   * das eine über das andere.
   */
  it('lässt Felder in Ruhe, die der Patch nicht nennt', () => {
    const patched = applyProjectPatch(project({ description: 'bleibt' }), {
      description: undefined,
      basemap: 'none',
    })

    expect(patched.description).toBe('bleibt')
    expect(patched.basemap).toBe('none')
  })
})

describe('reconcileProject', () => {
  it('legt noch laufende Patches über die Serverantwort, den jüngsten zuletzt', () => {
    const server = project({ basemap: 'osm-dark' })

    const merged = reconcileProject(server, [{ basemap: 'opentopo' }, { basemap: 'none' }])

    expect(merged.basemap).toBe('none')
  })

  it('gibt die Serverantwort unverändert zurück, wenn nichts mehr läuft', () => {
    const server = project({ basemap: 'osm-dark' })

    expect(reconcileProject(server, [])).toBe(server)
  })
})

function createClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((settle, fail) => {
    resolve = settle
    reject = fail
  })
  return { promise, resolve, reject }
}

/** Lässt die Mikrotasks durch, die `onMutate` und der Mutation-Scope brauchen. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

function observer(client: QueryClient, mutationFn: (input: UpdateProjectInput) => Promise<ProjectDetail>) {
  return new MutationObserver<ProjectDetail, Error, UpdateProjectInput, ProjectPatchContext>(client, {
    ...projectUpdateOptions(client, ID),
    mutationFn,
  })
}

function cached(client: QueryClient): ProjectDetail | undefined {
  return client.getQueryData<ProjectDetail>(projectKeys.detail(ID))
}

describe('projectUpdateOptions', () => {
  /**
   * Der Befund: zwei schnelle Klicks im Hintergrundkarten-Menü. Die Antwort auf den
   * ersten überschrieb den zweiten -- die Karte sprang sichtbar auf die alte
   * Hintergrundkarte zurück, bis die zweite Antwort eintraf.
   */
  it('springt bei zwei schnellen Wechseln nicht auf die ältere Antwort zurück', async () => {
    const client = createClient()
    client.setQueryData(projectKeys.detail(ID), project())

    const first = deferred<ProjectDetail>()
    const second = deferred<ProjectDetail>()
    const started: string[] = []

    const dark = observer(client, (input) => {
      started.push(String(input.basemap))
      return first.promise
    })
    const topo = observer(client, (input) => {
      started.push(String(input.basemap))
      return second.promise
    })

    const runDark = dark.mutate({ basemap: 'osm-dark' })
    const runTopo = topo.mutate({ basemap: 'opentopo' })
    await flush()

    // Ein Auftrag je Projekt: der zweite PATCH geht erst raus, wenn der erste durch
    // ist -- sonst entscheidet auf dem Server die Reihenfolge des Eintreffens.
    expect(started).toEqual(['osm-dark'])
    expect(cached(client)?.basemap).toBe('opentopo')

    first.resolve(project({ basemap: 'osm-dark' }))
    await runDark

    expect(cached(client)?.basemap).toBe('opentopo')

    second.resolve(project({ basemap: 'opentopo' }))
    await runTopo

    expect(started).toEqual(['osm-dark', 'opentopo'])
    expect(cached(client)?.basemap).toBe('opentopo')
  })

  /**
   * Kartenausschnitt und Hintergrundkarte werden von zwei verschiedenen Komponenten
   * geschrieben, die nichts voneinander wissen. Keine der beiden darf die andere
   * verlieren.
   */
  it('verliert eine parallele Ausschnittsspeicherung nicht am Kartenwechsel', async () => {
    const client = createClient()
    client.setQueryData(projectKeys.detail(ID), project())

    const viewportAnswer = deferred<ProjectDetail>()
    const viewport = observer(client, () => viewportAnswer.promise)
    const basemap = observer(client, async () => project({ basemap: 'opentopo', center: [1, 2], zoom: 5 }))

    const runViewport = viewport.mutate({ center: [1, 2], zoom: 5 })
    const runBasemap = basemap.mutate({ basemap: 'opentopo' })
    await flush()

    // Die Antwort auf den Ausschnitt kennt die neue Hintergrundkarte noch nicht.
    viewportAnswer.resolve(project({ center: [1, 2], zoom: 5 }))
    await runViewport

    expect(cached(client)).toMatchObject({ basemap: 'opentopo', center: [1, 2], zoom: 5 })

    await runBasemap
    expect(cached(client)).toMatchObject({ basemap: 'opentopo', center: [1, 2], zoom: 5 })
  })

  it('nimmt beim Fehlschlag nur den eigenen Patch zurück', async () => {
    const client = createClient()
    client.setQueryData(projectKeys.detail(ID), project())

    const failure = deferred<ProjectDetail>()
    const failing = observer(client, () => failure.promise)
    const later = observer(client, async () => project({ basemap: 'opentopo', zoom: 9 }))

    const runFailing = failing.mutate({ zoom: 9 }).catch(() => undefined)
    const runLater = later.mutate({ basemap: 'opentopo' })
    await flush()

    failure.reject(new Error('kaputt'))
    await runFailing

    const rolledBack = cached(client)
    expect(rolledBack?.zoom).toBeNull()
    expect(rolledBack?.basemap).toBe('opentopo')

    await runLater
  })
})

/**
 * `projectUpdateOptions`' own `mutationFn`, not overridden here the way the tests above
 * override it -- what is under test is exactly the one line that changed: does the real
 * PATCH now name this client, the same way `useSaveViewState`'s PUT always has. Without
 * this header `RemoteViewport` (`map/RemoteViewport.tsx`) could never tell this client's
 * own viewport write apart from someone else's when the event comes back.
 */
describe('projectUpdateOptions -- der echte PATCH', () => {
  it('nennt sich über X-Hgis-Client, wie useSaveViewState es tut', async () => {
    const client = createClient()
    client.setQueryData(projectKeys.detail(ID), project())
    const { requests } = stubFetch([{ match: `/api/projects/${ID}`, body: project({ zoom: 9 }) }])

    const mutation = new MutationObserver<ProjectDetail, Error, UpdateProjectInput, ProjectPatchContext>(
      client, projectUpdateOptions(client, ID))
    await mutation.mutate({ zoom: 9 })

    const patch = requests.find((r) => r.url.includes(`/api/projects/${ID}`))
    expect(patch?.init?.headers).toMatchObject({ [CLIENT_HEADER]: CLIENT_ID })
  })
})
