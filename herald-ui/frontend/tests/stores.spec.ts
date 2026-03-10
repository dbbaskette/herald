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
    })

    it('fetchStatus sets healthy on success', async () => {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve({ healthy: true }),
      }))
      const store = useStatusStore()
      await store.fetchStatus()
      expect(store.healthy).toBe(true)
      expect(store.loading).toBe(false)
    })

    it('fetchStatus sets healthy false on network error', async () => {
      vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Network error')))
      const store = useStatusStore()
      await store.fetchStatus()
      expect(store.healthy).toBe(false)
      expect(store.loading).toBe(false)
    })

    it('fetchStatus sets healthy false on non-ok response', async () => {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
        ok: false,
        statusText: 'Internal Server Error',
      }))
      const store = useStatusStore()
      await store.fetchStatus()
      expect(store.healthy).toBe(false)
      expect(store.loading).toBe(false)
    })
  })

  describe('useSkillsStore', () => {
    it('has correct initial state', () => {
      const store = useSkillsStore()
      expect(store.skills).toEqual([])
      expect(store.loading).toBe(false)
    })

    it('fetchSkills populates skills on success', async () => {
      const mockSkills = [{ id: '1', name: 'test', enabled: true }]
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(mockSkills),
      }))
      const store = useSkillsStore()
      await store.fetchSkills()
      expect(store.skills).toEqual(mockSkills)
    })

    it('fetchSkills resets to empty on error', async () => {
      vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('fail')))
      const store = useSkillsStore()
      await store.fetchSkills()
      expect(store.skills).toEqual([])
    })
  })

  describe('useCronStore', () => {
    it('has correct initial state', () => {
      const store = useCronStore()
      expect(store.jobs).toEqual([])
      expect(store.loading).toBe(false)
    })

    it('fetchJobs populates jobs on success', async () => {
      const mockJobs = [{ id: '1', name: 'job', expression: '0 * * * *', enabled: true }]
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
