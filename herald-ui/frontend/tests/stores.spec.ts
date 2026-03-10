import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useStatusStore } from '../src/stores/status'
import { useSkillsStore } from '../src/stores/skills'
import { useCronStore } from '../src/stores/cron'

describe('stores', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  describe('useStatusStore', () => {
    it('has correct initial state', () => {
      const store = useStatusStore()
      expect(store.healthy).toBe(false)
      expect(store.loading).toBe(false)
      expect(store.connected).toBe(false)
      expect(store.status.bot.running).toBe(false)
      expect(store.status.mcp).toEqual([])
      expect(store.status.recentActivity).toEqual([])
    })

    it('fetchStatus populates full status on success', async () => {
      const mockStatus = {
        healthy: true,
        bot: { running: true, pid: 1234, uptime: '2h 30m', restartCount: 1 },
        model: { name: 'claude-sonnet-4-20250514', requestsToday: 42, estimatedTokenSpend: '$1.23' },
        mcp: [{ name: 'github', status: 'connected', lastPing: '2026-03-10T12:00:00Z', toolCount: 5 }],
        skills: { totalLoaded: 8, lastReload: '2026-03-10T11:00:00Z', parseErrors: [] },
        memory: { entryCount: 15, databaseFileSize: '2.1 MB' },
        cron: [{ name: 'briefing', nextRun: '2026-03-10T13:00:00Z', lastRun: '2026-03-10T12:00:00Z', lastResult: 'success' }],
        recentActivity: [{ timestamp: '2026-03-10T12:00:00Z', messagePreview: 'Hello', toolCalls: ['Read'] }],
      }
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(mockStatus),
      }))
      const store = useStatusStore()
      await store.fetchStatus()
      expect(store.healthy).toBe(true)
      expect(store.status.bot.running).toBe(true)
      expect(store.status.bot.pid).toBe(1234)
      expect(store.status.model.name).toBe('claude-sonnet-4-20250514')
      expect(store.status.mcp).toHaveLength(1)
      expect(store.status.recentActivity).toHaveLength(1)
      expect(store.loading).toBe(false)
    })

    it('fetchStatus resets to defaults on network error', async () => {
      vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Network error')))
      const store = useStatusStore()
      await store.fetchStatus()
      expect(store.healthy).toBe(false)
      expect(store.status.bot.running).toBe(false)
      expect(store.loading).toBe(false)
    })

    it('fetchStatus resets to defaults on non-ok response', async () => {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
        ok: false,
        statusText: 'Internal Server Error',
      }))
      const store = useStatusStore()
      await store.fetchStatus()
      expect(store.healthy).toBe(false)
      expect(store.loading).toBe(false)
    })

    it('fetchStatus handles partial data gracefully', async () => {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve({ healthy: true }),
      }))
      const store = useStatusStore()
      await store.fetchStatus()
      expect(store.healthy).toBe(true)
      expect(store.status.bot.running).toBe(false)
      expect(store.status.mcp).toEqual([])
    })

    it('connectSSE creates EventSource and sets connected', () => {
      const mockEventSource = {
        onopen: null as (() => void) | null,
        onmessage: null as ((event: MessageEvent) => void) | null,
        onerror: null as (() => void) | null,
        close: vi.fn(),
      }
      vi.stubGlobal('EventSource', vi.fn().mockImplementation(() => mockEventSource))

      const store = useStatusStore()
      store.connectSSE()

      expect(EventSource).toHaveBeenCalledWith('/api/status/stream')

      // Simulate connection open
      mockEventSource.onopen!()
      expect(store.connected).toBe(true)
    })

    it('SSE onmessage updates status data', () => {
      const mockEventSource = {
        onopen: null as (() => void) | null,
        onmessage: null as ((event: MessageEvent) => void) | null,
        onerror: null as (() => void) | null,
        close: vi.fn(),
      }
      vi.stubGlobal('EventSource', vi.fn().mockImplementation(() => mockEventSource))

      const store = useStatusStore()
      store.connectSSE()

      const update = {
        healthy: true,
        bot: { running: true, pid: 5678, uptime: '1h', restartCount: 0 },
      }
      mockEventSource.onmessage!({ data: JSON.stringify(update) } as MessageEvent)

      expect(store.status.bot.running).toBe(true)
      expect(store.status.bot.pid).toBe(5678)
    })

    it('disconnectSSE closes EventSource', () => {
      const mockEventSource = {
        onopen: null as (() => void) | null,
        onmessage: null as ((event: MessageEvent) => void) | null,
        onerror: null as (() => void) | null,
        close: vi.fn(),
      }
      vi.stubGlobal('EventSource', vi.fn().mockImplementation(() => mockEventSource))

      const store = useStatusStore()
      store.connectSSE()
      store.disconnectSSE()

      expect(mockEventSource.close).toHaveBeenCalled()
      expect(store.connected).toBe(false)
    })
  })

  describe('useSkillsStore', () => {
    it('has correct initial state', () => {
      const store = useSkillsStore()
      expect(store.skillNames).toEqual([])
      expect(store.loading).toBe(false)
      expect(store.selectedName).toBeNull()
      expect(store.editorContent).toBe('')
    })

    it('fetchSkills populates skillNames on success', async () => {
      const mockNames = ['greeting', 'weather']
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(mockNames),
      }))
      const store = useSkillsStore()
      await store.fetchSkills()
      expect(store.skillNames).toEqual(mockNames)
    })

    it('fetchSkills resets to empty on error', async () => {
      vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('fail')))
      const store = useSkillsStore()
      await store.fetchSkills()
      expect(store.skillNames).toEqual([])
    })
  })

  describe('useCronStore', () => {
    it('has correct initial state', () => {
      const store = useCronStore()
      expect(store.jobs).toEqual([])
      expect(store.loading).toBe(false)
    })

    it('fetchJobs populates jobs on success', async () => {
      const mockJobs = [{
        id: '1', name: 'job', expression: '0 * * * *', enabled: true,
        builtIn: false, promptText: '', status: 'idle',
        lastRun: null, nextRun: null, lastRunLog: null,
      }]
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(mockJobs),
      }))
      const store = useCronStore()
      await store.fetchJobs()
      expect(store.jobs).toEqual(mockJobs)
    })

    it('fetchJobs resets to empty on error', async () => {
      vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('fail')))
      const store = useCronStore()
      await store.fetchJobs()
      expect(store.jobs).toEqual([])
    })
  })
})
