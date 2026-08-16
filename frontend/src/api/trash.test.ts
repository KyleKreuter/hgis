import { describe, expect, it } from 'vitest'
import { MutationObserver, QueryClient } from '@tanstack/react-query'
import {
  purgeLayerOptions,
  restoreLayerOptions,
  trashKeys,
  type TrashEntry,
} from './trash'

function entry(overrides: Partial<TrashEntry> = {}): TrashEntry {
  return {
    id: 'l-1',
    name: 'Gebäude',
    deletedAt: '2026-08-16T00:00:00Z',
    deletedBy: 'M. Mustermann',
    featureCount: 12,
    ...overrides,
  }
}

function createClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
}

/** Lässt die Mikrotasks durch, die `onSuccess` braucht. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

describe('restoreLayerOptions und purgeLayerOptions', () => {
  /**
   * Prüfstand-Befund #2: `usePurgeLayer` entfernte seine Zeile bisher sofort aus dem
   * Zwischenspeicher, `useRestoreLayer` wartete nur auf ein Invalidate + Refetch -- in
   * genau diesem Zeitfenster blieb die Zeile mit einem weiterhin bedienbaren
   * Wiederherstellen-Knopf stehen. Gegen einen echten `QueryClient` statt gegen eine
   * gerenderte Komponente geprüft: `invalidateQueries` würde in einer Komponente sofort
   * einen Refetch auslösen, der beim gemockten `fetch` denselben (nicht wirklich
   * gelöschten) Eintrag zurückgibt und den Zustand direkt danach wieder auffüllt -- ein
   * DOM-Assert hinge dann vom Zufall der Poll-Zeitpunkte ab. Hier ist `onSuccess` die
   * einzige Mutation, die stattfindet.
   */
  it('entfernt den Eintrag sofort aus dem Zwischenspeicher, wenn Wiederherstellen glückt', async () => {
    const client = createClient()
    client.setQueryData(trashKeys.list('p-1'), [entry()])

    const observer = new MutationObserver(client, {
      ...restoreLayerOptions(client, 'p-1'),
      mutationFn: () => Promise.resolve(undefined),
    })
    observer.mutate('l-1')
    await flush()

    expect(client.getQueryData(trashKeys.list('p-1'))).toEqual([])
  })

  it('entfernt den Eintrag sofort aus dem Zwischenspeicher, wenn endgültiges Löschen glückt', async () => {
    const client = createClient()
    client.setQueryData(trashKeys.list('p-1'), [entry()])

    const observer = new MutationObserver(client, {
      ...purgeLayerOptions(client, 'p-1'),
      mutationFn: () => Promise.resolve(undefined),
    })
    observer.mutate('l-1')
    await flush()

    expect(client.getQueryData(trashKeys.list('p-1'))).toEqual([])
  })

  it('lässt andere Einträge unangetastet', async () => {
    const client = createClient()
    client.setQueryData(trashKeys.list('p-1'), [entry({ id: 'l-1' }), entry({ id: 'l-2', name: 'Straßen' })])

    const observer = new MutationObserver(client, {
      ...restoreLayerOptions(client, 'p-1'),
      mutationFn: () => Promise.resolve(undefined),
    })
    observer.mutate('l-1')
    await flush()

    expect(client.getQueryData<TrashEntry[]>(trashKeys.list('p-1'))).toEqual([
      entry({ id: 'l-2', name: 'Straßen' }),
    ])
  })
})
