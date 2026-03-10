import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useMemoryStore } from './memory'

const sampleEntries = [
  { key: 'user.name', value: 'Alice', lastUpdated: '2026-03-10T10:00:00Z' },
  { key: 'bot.mode', value: 'production', lastUpdated: '2026-03-10T09:00:00Z' },
  { key: 'project.repo', value: 'herald', lastUpdated: '2026-03-09T08:00:00Z' },
]

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

  it('sets entries to empty array on fetch error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('fail')))

    const store = useMemoryStore()
    await store.fetchEntries()

    expect(store.entries).toEqual([])
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
