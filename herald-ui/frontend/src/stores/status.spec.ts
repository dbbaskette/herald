import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useStatusStore } from './status'
import type { SystemStatus } from './status'

const fullStatus: SystemStatus = {
  healthy: true,
  bot: { running: true, pid: 1234, uptime: '2h 15m', restartCount: 1 },
  model: { name: 'claude-sonnet-4-20250514', requestsToday: 42, estimatedTokenSpend: '$1.23' },
  mcp: [
    { name: 'github', status: 'connected', lastPing: '2026-03-10T10:00:00Z', toolCount: 12 },
    { name: 'slack', status: 'disconnected', lastPing: null, toolCount: 0 },
  ],
  skills: { totalLoaded: 5, lastReload: '2026-03-10T09:00:00Z', parseErrors: [] },
  memory: { entryCount: 150, databaseFileSize: '2.4 MB' },
  cron: [
    { name: 'daily-briefing', nextRun: '2026-03-11T08:00:00Z', lastRun: '2026-03-10T08:00:00Z', lastResult: 'success' },
  ],
  recentActivity: [
    { timestamp: '2026-03-10T10:05:00Z', messagePreview: 'Reviewed PR #42', toolCalls: ['Read', 'Grep'] },
  ],
}

describe('useStatusStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('has correct default state', () => {
    const store = useStatusStore()
    expect(store.status.healthy).toBe(false)
    expect(store.status.bot.running).toBe(false)
    expect(store.status.mcp).toEqual([])
    expect(store.status.recentActivity).toEqual([])
    expect(store.loading).toBe(false)
    expect(store.connected).toBe(false)
  })

  it('fetches status from API', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(fullStatus),
    }))

    const store = useStatusStore()
    await store.fetchStatus()

    expect(store.status.healthy).toBe(true)
    expect(store.status.bot.running).toBe(true)
    expect(store.status.bot.pid).toBe(1234)
    expect(store.status.model.name).toBe('claude-sonnet-4-20250514')
    expect(store.status.mcp).toHaveLength(2)
    expect(store.status.skills.totalLoaded).toBe(5)
    expect(store.status.memory.entryCount).toBe(150)
    expect(store.status.cron).toHaveLength(1)
    expect(store.status.recentActivity).toHaveLength(1)
    expect(store.loading).toBe(false)
  })

  it('resets to defaults on fetch error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Network error')))

    const store = useStatusStore()
    await store.fetchStatus()

    expect(store.status.healthy).toBe(false)
    expect(store.status.bot.running).toBe(false)
    expect(store.loading).toBe(false)
  })

  it('resets to defaults on non-ok response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      statusText: 'Internal Server Error',
    }))

    const store = useStatusStore()
    await store.fetchStatus()

    expect(store.status.healthy).toBe(false)
    expect(store.loading).toBe(false)
  })

  it('connects and disconnects SSE', () => {
    const closeFn = vi.fn()
    const mockEventSource = vi.fn(function () { return {
      onopen: null,
      onmessage: null,
      onerror: null,
      close: closeFn, addEventListener: vi.fn(),
    } })
    vi.stubGlobal('EventSource', mockEventSource)

    const store = useStatusStore()
    store.connectSSE()

    expect(mockEventSource).toHaveBeenCalledWith('/api/status/stream')

    store.disconnectSSE()
    expect(closeFn).toHaveBeenCalled()
    expect(store.connected).toBe(false)
  })

  it('updates status on SSE message', () => {
    let esInstance: any = null
    vi.stubGlobal('EventSource', vi.fn(function () {
      esInstance = {
        onopen: null as any,
        onmessage: null as any,
        onerror: null as any,
        close: vi.fn(), addEventListener: vi.fn(),
      }
      return esInstance
    }))

    const store = useStatusStore()
    store.connectSSE()

    // Simulate onopen
    esInstance.onopen()
    expect(store.connected).toBe(true)

    // Simulate SSE message
    esInstance.onmessage({ data: JSON.stringify(fullStatus) })
    expect(store.status.healthy).toBe(true)
    expect(store.status.bot.pid).toBe(1234)

    store.disconnectSSE()
  })

  it('handles malformed SSE message gracefully', () => {
    let esInstance: any = null
    vi.stubGlobal('EventSource', vi.fn(function () {
      esInstance = {
        onopen: null as any,
        onmessage: null as any,
        onerror: null as any,
        close: vi.fn(), addEventListener: vi.fn(),
      }
      return esInstance
    }))

    const store = useStatusStore()
    store.connectSSE()
    esInstance.onopen()

    // Should not throw
    esInstance.onmessage({ data: 'not-json' })
    expect(store.status.healthy).toBe(false) // unchanged from default

    store.disconnectSSE()
  })

  it('healthy computed reflects status', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(fullStatus),
    }))

    const store = useStatusStore()
    expect(store.healthy).toBe(false)

    await store.fetchStatus()
    expect(store.healthy).toBe(true)
  })
})

describe('snapshot recovery and ownership', () => {
  let sources: any[]
  beforeEach(() => {
    vi.useFakeTimers(); setActivePinia(createPinia()); sources = []
    vi.stubGlobal('EventSource', vi.fn(function () {
      const source = { onopen: null, onmessage: null, onerror: null, close: vi.fn(), addEventListener: vi.fn() }
      sources.push(source); return source
    }))
  })
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })
  it('retains snapshots across nested malformed messages and fetch failure then recovers', async () => {
    const store = useStatusStore(); store.connectSSE(); sources[0].onopen()
    sources[0].onmessage({ data: JSON.stringify(fullStatus) })
    const updated = store.lastUpdated
    for (const payload of [null, { ...fullStatus, bot: { running: 'yes' } }, { ...fullStatus, skills: { ...fullStatus.skills, parseErrors: [3] } }, { ...fullStatus, mcp: [{ status: 'bogus' }] }]) {
      sources[0].onmessage({ data: JSON.stringify(payload) })
      expect(store.status.bot.pid).toBe(1234); expect(store.lastUpdated).toBe(updated); expect(store.stale).toBe(true)
    }
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('down')))
    await store.fetchStatus(); expect(store.status.model.name).toBe(fullStatus.model.name)
    sources[0].onerror(); expect(store.connectionLabel).toContain('Reconnecting'); vi.advanceTimersByTime(1000)
    sources[1].onopen(); sources[1].onmessage({ data: JSON.stringify({ ...fullStatus, bot: { ...fullStatus.bot, pid: 5678 } }) })
    sources[0].onmessage({ data: JSON.stringify(fullStatus) })
    expect(store.status.bot.pid).toBe(5678); expect(store.stale).toBe(false); store.disconnectSSE()
  })
  it('keeps other consumers connected and cancels retry after final release', () => {
    const store = useStatusStore(); store.connectSSE(); store.connectSSE()
    expect(sources).toHaveLength(1); sources[0].onopen(); store.disconnectSSE()
    expect(store.connected).toBe(true); sources[0].onerror(); store.disconnectSSE(); vi.runAllTimers()
    expect(sources).toHaveLength(1)
  })
  it('caps activity and ignores HTTP completion older than a stream snapshot', async () => {
    const store = useStatusStore(); let finish!: (value: unknown) => void
    vi.stubGlobal('fetch', vi.fn(() => new Promise(resolve => { finish = resolve })))
    const pending = store.fetchStatus(); store.connectSSE()
    sources[0].onmessage({ data: JSON.stringify({ ...fullStatus, recentActivity: Array.from({ length: 25 }, (_, i) => ({ timestamp: String(i), messagePreview: 'turn', toolCalls: [] })) }) })
    finish({ ok: true, json: async () => ({ ...fullStatus, healthy: false }) }); await pending
    expect(store.status.healthy).toBe(true); expect(store.status.recentActivity).toHaveLength(20); store.disconnectSSE()
  })
})

it('consumes the named status event actually emitted by the server', () => {
  setActivePinia(createPinia())
  const addEventListener = vi.fn()
  vi.stubGlobal('EventSource', vi.fn(function () { return { onopen: null, onerror: null, onmessage: null, close: vi.fn(), addEventListener } }))
  const store = useStatusStore(); store.connectSSE()
  const listener = addEventListener.mock.calls.find(([name]) => name === 'status')![1]
  listener({ data: JSON.stringify(fullStatus) })
  expect(store.status.bot.pid).toBe(1234)
  store.disconnectSSE(); vi.unstubAllGlobals()
})
