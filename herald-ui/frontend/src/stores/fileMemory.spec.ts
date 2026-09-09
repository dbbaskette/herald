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

describe('memory editing safety', () => {
  beforeEach(() => setActivePinia(createPinia()))
  it('ignores an older selection response', async () => {
    let resolveA!: (value: unknown) => void
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => url.includes('a.md') ? new Promise(r => {resolveA = r}) : Promise.resolve({ok:true,json:async()=>({path:'b.md',content:'B',version:'b'})})))
    const store = useFileMemoryStore()
    const a = store.openPage('a.md'); await store.openPage('b.md')
    resolveA({ok:true,json:async()=>({path:'a.md',content:'A'})}); await a
    expect(store.selected?.path).toBe('b.md')
  })
  it('preserves a conflicting draft and prevents navigation', async () => {
    const store = useFileMemoryStore()
    store.selected = {path:'a.md',content:'old',size:3,version:'v1'}; store.draft='edited'
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ok:false,status:409,json:async()=>({message:'Changed on disk'})}))
    expect(await store.save()).toBe(false)
    expect(store.draft).toBe('edited'); expect(store.error).toBe('Changed on disk')
    await store.openPage('b.md'); store.clearSelected()
    expect(store.selected?.path).toBe('a.md')
  })
  it('saves a versioned draft and restores a trashed file', async () => {
    const store = useFileMemoryStore()
    store.selected={path:'a.md',content:'old',size:3,version:'v1'};store.draft='new'
    const fetcher=vi.fn().mockResolvedValueOnce({ok:true,json:async()=>({path:'a.md',content:'new',size:3,version:'v2'})}).mockResolvedValueOnce({ok:true,json:async()=>({})})
      .mockResolvedValueOnce({ok:true,json:async()=>({token:'token',trashPath:'.memory-trash/token/memory.md',originalPath:'a.md'})}).mockResolvedValueOnce({ok:true,json:async()=>({})})
      .mockResolvedValueOnce({ok:true,json:async()=>({})}).mockResolvedValueOnce({ok:true,json:async()=>({})})
    vi.stubGlobal('fetch',fetcher)
    expect(await store.save()).toBe(true);expect(store.dirty).toBe(false)
    expect(JSON.parse(fetcher.mock.calls[0][1].body)).toMatchObject({version:'v1',content:'new'})
    expect(await store.deleteSelected()).toBe(true);expect(store.trash?.trashPath).toContain('.memory-trash/')
    expect(await store.restore()).toBe(true);expect(store.trash).toBeNull()
  })
})
