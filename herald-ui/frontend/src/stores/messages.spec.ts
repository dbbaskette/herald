import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useMessagesStore } from './messages'

const samplePage = {
  content: [
    {
      id: 'msg-1',
      role: 'assistant',
      content: 'Here is the briefing',
      timestamp: '2026-03-10T10:00:00Z',
      toolCalls: [{ name: 'read_file', inputs: { path: '/tmp/a.txt' }, outputs: 'file contents' }],
      subagentCalls: [],
    },
    {
      id: 'msg-2',
      role: 'user',
      content: 'Thanks',
      timestamp: '2026-03-10T09:00:00Z',
      toolCalls: [],
      subagentCalls: [],
    },
  ],
  totalPages: 3,
  totalElements: 120,
  number: 0,
}

describe('messages store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('fetches messages from API', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(samplePage),
    }))

    const store = useMessagesStore()
    await store.fetchMessages()

    expect(store.messages).toHaveLength(2)
    expect(store.messages[0].id).toBe('msg-1')
    expect(store.currentPage).toBe(0)
    expect(store.totalPages).toBe(3)
    expect(store.totalElements).toBe(120)
    expect(store.loading).toBe(false)
  })

  it('sets messages to empty on fetch error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('fail')))

    const store = useMessagesStore()
    await store.fetchMessages()

    expect(store.messages).toEqual([])
    expect(store.totalPages).toBe(0)
    expect(store.loading).toBe(false)
  })

  it('passes search and date params to API', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ ...samplePage, content: [] }),
    })
    vi.stubGlobal('fetch', mockFetch)

    const store = useMessagesStore()
    store.search = 'briefing'
    store.startDate = '2026-03-01'
    store.endDate = '2026-03-10'
    await store.applyFilters()

    const url = mockFetch.mock.calls[0][0] as string
    expect(url).toContain('search=briefing')
    expect(url).toContain('startDate=2026-03-01')
    expect(url).toContain('endDate=2026-03-10')
    expect(url).toContain('page=0')
  })

  it('clears filters and refetches', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(samplePage),
    }))

    const store = useMessagesStore()
    store.search = 'test'
    store.startDate = '2026-01-01'
    store.endDate = '2026-12-31'
    await store.clearFilters()

    expect(store.search).toBe('')
    expect(store.startDate).toBe('')
    expect(store.endDate).toBe('')
  })

  it('navigates to next page', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(samplePage),
    })
    vi.stubGlobal('fetch', mockFetch)

    const store = useMessagesStore()
    await store.fetchMessages()

    // On page 0 of 3, should have next page
    expect(store.hasNextPage).toBe(true)
    store.nextPage()

    expect(mockFetch).toHaveBeenCalledTimes(2)
    const url = mockFetch.mock.calls[1][0] as string
    expect(url).toContain('page=1')
  })

  it('does not navigate past last page', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ ...samplePage, number: 2, totalPages: 3 }),
    }))

    const store = useMessagesStore()
    await store.fetchMessages()

    expect(store.hasNextPage).toBe(false)
  })

  it('navigates to previous page', async () => {
    const page1 = { ...samplePage, number: 1 }
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(page1),
    })
    vi.stubGlobal('fetch', mockFetch)

    const store = useMessagesStore()
    await store.fetchMessages(1)

    expect(store.hasPrevPage).toBe(true)
    store.prevPage()

    const url = mockFetch.mock.calls[1][0] as string
    expect(url).toContain('page=0')
  })

  it('clears history via DELETE', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce({ ok: true, json: () => Promise.resolve(samplePage) })
      .mockResolvedValueOnce({ ok: true })
    )

    const store = useMessagesStore()
    await store.fetchMessages()
    expect(store.messages).toHaveLength(2)

    const ok = await store.clearHistory()
    expect(ok).toBe(true)
    expect(store.messages).toEqual([])
    expect(store.totalPages).toBe(0)
  })

  it('returns false on clear failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, statusText: 'Error' }))

    const store = useMessagesStore()
    const ok = await store.clearHistory()

    expect(ok).toBe(false)
  })
})
