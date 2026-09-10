import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useMemoryStore } from './memory'

const sampleEntries = [
  { key: 'user.name', value: 'Alice', lastUpdated: '2026-03-10T10:00:00Z' },
  { key: 'bot.mode', value: 'production', lastUpdated: '2026-03-10T09:00:00Z' },
  { key: 'project.repo', value: 'herald', lastUpdated: '2026-03-09T08:00:00Z' },
]

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('memory store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('fetches entries from API', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(sampleEntries),
    }))

    const store = useMemoryStore()
    await store.fetchEntries()

    expect(store.entries).toHaveLength(3)
    expect(store.entries[0].key).toBe('user.name')
    expect(store.loading).toBe(false)
  })

  it('preserves retained entries and reports a failed legacy load', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('fail')))

    const store = useMemoryStore()
    store.entries = [...sampleEntries]
    expect(await store.fetchEntries()).toBe(false)

    expect(store.entries).toEqual(sampleEntries)
    expect(store.error).toContain('Unable to load legacy')
    expect(store.loading).toBe(false)
  })

  it('filters entries by key name', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(sampleEntries),
    }))

    const store = useMemoryStore()
    await store.fetchEntries()

    store.filter = 'bot'
    expect(store.filteredEntries).toHaveLength(1)
    expect(store.filteredEntries[0].key).toBe('bot.mode')
  })

  it('filter is case-insensitive', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(sampleEntries),
    }))

    const store = useMemoryStore()
    await store.fetchEntries()

    store.filter = 'USER'
    expect(store.filteredEntries).toHaveLength(1)
  })

  it('updates an existing entry', async () => {
    const updated = { key: 'user.name', value: 'Bob', lastUpdated: '2026-03-10T11:00:00Z' }
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce({ ok: true, json: () => Promise.resolve(sampleEntries) })
      .mockResolvedValueOnce({ ok: true, json: () => Promise.resolve(updated) })
    )

    const store = useMemoryStore()
    await store.fetchEntries()
    const ok = await store.updateEntry('user.name', 'Bob')

    expect(ok).toBe(true)
    expect(store.entries.find(e => e.key === 'user.name')?.value).toBe('Bob')
  })

  it('adds a new entry when key does not exist', async () => {
    const newEntry = { key: 'new.key', value: 'val', lastUpdated: '2026-03-10T12:00:00Z' }
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce({ ok: true, json: () => Promise.resolve([]) })
      .mockResolvedValueOnce({ ok: true, json: () => Promise.resolve(newEntry) })
    )

    const store = useMemoryStore()
    await store.fetchEntries()
    const ok = await store.addEntry('new.key', 'val')

    expect(ok).toBe(true)
    expect(store.entries[0].key).toBe('new.key')
  })

  it('deletes an entry', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce({ ok: true, json: () => Promise.resolve(sampleEntries) })
      .mockResolvedValueOnce({ ok: true })
    )

    const store = useMemoryStore()
    await store.fetchEntries()
    const ok = await store.deleteEntry('user.name')

    expect(ok).toBe(true)
    expect(store.entries.find(e => e.key === 'user.name')).toBeUndefined()
    expect(store.entries).toHaveLength(2)
  })

  it('returns false on delete failure', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce({ ok: true, json: () => Promise.resolve(sampleEntries) })
      .mockResolvedValueOnce({ ok: false, statusText: 'Not Found' })
    )

    const store = useMemoryStore()
    await store.fetchEntries()
    const ok = await store.deleteEntry('nonexistent')

    expect(ok).toBe(false)
    expect(store.entries).toHaveLength(3)
  })

  it('returns false on update failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network error')))

    const store = useMemoryStore()
    const ok = await store.updateEntry('key', 'val')

    expect(ok).toBe(false)
  })
})

it('exports a fresh unfiltered legacy-only backup and restores its exact keys without touching files', async () => {
  setActivePinia(createPinia())
  const fixture = JSON.parse((await import('../../tests/fixtures/legacy-memory.json?raw')).default) as Record<string, string>
  const entries = Object.entries(fixture).map(([key, value]) => ({ key, value, lastUpdated: 'fixture-time' }))
  const fetcher = vi.fn(async (url: string, options?: RequestInit) => {
    expect(url.startsWith('/api/memory')).toBe(true)
    expect(url).not.toContain('/files')
    if (options?.method === 'PUT') {
      const key = decodeURIComponent(url.slice('/api/memory/'.length))
      return new Response(JSON.stringify({ key, ...JSON.parse(String(options.body)), lastUpdated: 'restored-time' }))
    }
    expect(url).toBe('/api/memory')
    return new Response(JSON.stringify(entries))
  })
  vi.stubGlobal('fetch', fetcher)
  let blob: Blob | undefined, filename = ''
  vi.stubGlobal('URL', {
    createObjectURL: vi.fn((value: Blob) => { blob = value; return 'blob:fixture' }),
    revokeObjectURL: vi.fn(),
  })
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) { filename = this.download })
  const store = useMemoryStore(); store.filter = 'user'; store.entries = []
  expect(await store.exportJson()).toBe(true)
  expect(filename).toBe('herald-legacy-memory-backup.json')
  const text = await new Promise<string>(resolve => { const reader = new FileReader(); reader.onload = () => resolve(String(reader.result)); reader.readAsText(blob!) })
  expect(JSON.parse(text)).toEqual(fixture)
  const restored = await store.importJson({ text: async () => text } as File)
  expect(restored).toEqual({ imported: 3, errors: [] })
  expect(fetcher.mock.calls.filter(call => call[1]?.method === 'DELETE')).toHaveLength(0)
  vi.restoreAllMocks(); vi.unstubAllGlobals()
})

it('does not export stale or empty data when the fresh legacy backup read fails', async () => {
  setActivePinia(createPinia())
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
  const download = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  const store = useMemoryStore(); store.entries = [...sampleEntries]
  expect(await store.exportJson()).toBe(false)
  expect(download).not.toHaveBeenCalled()
  expect(store.entries).toEqual(sampleEntries)
  expect(store.error).toContain('Retry')
  vi.restoreAllMocks(); vi.unstubAllGlobals()
})
