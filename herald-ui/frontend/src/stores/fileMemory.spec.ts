import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useFileMemoryStore, TYPE_ORDER, TYPE_LABEL } from './fileMemory'

const sampleGrouped = {
  user: [
    {
      path: 'user_profile.md',
      name: 'user_profile',
      description: 'Dan, backend engineer',
      type: 'user',
      size: 128,
      lastModified: '2026-04-22T10:00:00Z',
    },
  ],
  feedback: [],
  project: [],
  reference: [],
  concept: [
    {
      path: 'concepts/hot_path.md',
      name: 'hot_path',
      description: 'Request/response flow',
      type: 'concept',
      size: 512,
      lastModified: '2026-04-22T11:00:00Z',
    },
  ],
  entity: [],
  source: [],
  unknown: [],
}

describe('fileMemory store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('exposes the full type order with user-friendly labels', () => {
    expect(TYPE_ORDER).toContain('concept')
    expect(TYPE_ORDER).toContain('entity')
    expect(TYPE_ORDER).toContain('source')
    expect(TYPE_LABEL.concept).toBe('Concepts')
    expect(TYPE_LABEL.entity).toBe('Entities')
    expect(TYPE_LABEL.source).toBe('Sources')
  })

  it('fetches grouped pages and clears loading', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve(sampleGrouped) }),
    )

    const store = useFileMemoryStore()
    await store.fetchPages()

    expect(store.loading).toBe(false)
    expect(store.grouped.user).toHaveLength(1)
    expect(store.grouped.concept[0].path).toBe('concepts/hot_path.md')
    expect(store.error).toBeNull()
  })

  it('captures errors on fetch failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, statusText: 'boom' }))

    const store = useFileMemoryStore()
    await store.fetchPages()

    expect(store.error).toBe('boom')
    expect(store.grouped).toEqual({})
  })

  it('loads a page content and can clear it', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve({
          path: 'user_profile.md',
          content: '# Profile\n\nBackend engineer.',
          size: 32,
        }),
      }),
    )

    const store = useFileMemoryStore()
    await store.openPage('user_profile.md')

    expect(store.selected?.path).toBe('user_profile.md')
    expect(store.selected?.content).toContain('Backend engineer')

    store.clearSelected()
    expect(store.selected).toBeNull()
  })
})
