import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useCronStore } from './cron'

const sampleJobs = [
  {
    id: '1', name: 'Morning Briefing', expression: '0 9 * * *', enabled: true,
    builtIn: true, promptText: 'Run morning briefing', status: 'success',
    lastRun: '2026-03-10T09:00:00Z', nextRun: '2026-03-11T09:00:00Z',
    lastRunLog: 'Briefing completed successfully',
  },
  {
    id: '2', name: 'Weekly Review', expression: '0 10 * * 1', enabled: false,
    builtIn: true, promptText: 'Run weekly review', status: 'idle',
    lastRun: null, nextRun: '2026-03-16T10:00:00Z', lastRunLog: null,
  },
]

describe('useCronStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  it('has correct initial state', () => {
    const store = useCronStore()
    expect(store.jobs).toEqual([])
    expect(store.loading).toBe(false)
  })

  it('fetchJobs populates jobs on success', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(sampleJobs),
    }))
    const store = useCronStore()
    await store.fetchJobs()
    expect(store.jobs).toHaveLength(2)
    expect(store.jobs[0].name).toBe('Morning Briefing')
    expect(store.loading).toBe(false)
  })

  it('fetchJobs resets to empty on error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('fail')))
    const store = useCronStore()
    await store.fetchJobs()
    expect(store.jobs).toEqual([])
    expect(store.loading).toBe(false)
  })

  it('toggleJob sends PATCH and updates job', async () => {
    const updatedJob = { ...sampleJobs[0], enabled: false }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(updatedJob),
    }))
    const store = useCronStore()
    store.jobs = [...sampleJobs]

    const ok = await store.toggleJob('1', false)
    expect(ok).toBe(true)
    expect(store.jobs[0].enabled).toBe(false)
    expect(fetch).toHaveBeenCalledWith('/api/cron/1', expect.objectContaining({
      method: 'PATCH',
    }))
  })

  it('toggleJob returns false on error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('fail')))
    const store = useCronStore()
    store.jobs = [...sampleJobs]
    const ok = await store.toggleJob('1', false)
    expect(ok).toBe(false)
  })

  it('saveJob creates new job with POST', async () => {
    const newJob = {
      id: '3', name: 'Custom Job', expression: '*/5 * * * *', enabled: true,
      builtIn: false, promptText: 'test', status: 'idle',
      lastRun: null, nextRun: null, lastRunLog: null,
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(newJob),
    }))
    const store = useCronStore()
    store.jobs = [...sampleJobs]

    const ok = await store.saveJob({ name: 'Custom Job', expression: '*/5 * * * *', promptText: 'test' })
    expect(ok).toBe(true)
    expect(store.jobs).toHaveLength(3)
    expect(fetch).toHaveBeenCalledWith('/api/cron', expect.objectContaining({ method: 'POST' }))
  })

  it('saveJob updates existing job with PUT', async () => {
    const updatedJob = { ...sampleJobs[0], name: 'Updated Briefing' }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(updatedJob),
    }))
    const store = useCronStore()
    store.jobs = [...sampleJobs]

    const ok = await store.saveJob({ id: '1', name: 'Updated Briefing', expression: '0 9 * * *', promptText: 'test' })
    expect(ok).toBe(true)
    expect(store.jobs[0].name).toBe('Updated Briefing')
    expect(fetch).toHaveBeenCalledWith('/api/cron/1', expect.objectContaining({ method: 'PUT' }))
  })

  it('deleteJob removes job from list', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true }))
    const store = useCronStore()
    store.jobs = [{ ...sampleJobs[0], builtIn: false, id: '3' }]

    const ok = await store.deleteJob('3')
    expect(ok).toBe(true)
    expect(store.jobs).toHaveLength(0)
  })

  it('deleteJob returns false on error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('fail')))
    const store = useCronStore()
    store.jobs = [...sampleJobs]
    const ok = await store.deleteJob('1')
    expect(ok).toBe(false)
    expect(store.jobs).toHaveLength(2)
  })

  it('runJob calls POST /api/cron/:id/run and refreshes', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce({ ok: true })
      .mockResolvedValueOnce({ ok: true, json: () => Promise.resolve(sampleJobs) }))
    const store = useCronStore()

    const ok = await store.runJob('1')
    expect(ok).toBe(true)
    expect(fetch).toHaveBeenCalledWith('/api/cron/1/run', expect.objectContaining({ method: 'POST' }))
  })

  it('runJob returns false on error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('fail')))
    const store = useCronStore()
    const ok = await store.runJob('1')
    expect(ok).toBe(false)
  })

  it('preserves last good jobs and actionable errors on refresh failure', async () => {
    const store = useCronStore(); store.jobs = [{ ...sampleJobs[0] }]
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 503, json: async () => ({ message: 'Bot unavailable' }) }))
    await store.fetchJobs()
    expect(store.jobs).toHaveLength(1)
    expect(store.error).toBe('Bot unavailable')
  })
  it('run acceptance is queued until a later refresh reports completion', async () => {
    const store = useCronStore(); store.jobs = [{ ...sampleJobs[0] }]
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ status: 'pending' }) }))
    expect(await store.runJob('1')).toBe(true)
    expect(store.jobs[0].status).toBe('queued')
    vi.mocked(fetch).mockResolvedValue({ ok: true, json: async () => [{ ...sampleJobs[0], status: 'running' }] } as Response)
    await store.fetchJobs(); expect(store.jobs[0].status).toBe('running')
    vi.mocked(fetch).mockResolvedValue({ ok: true, json: async () => [{ ...sampleJobs[0], status: 'failed' }] } as Response)
    await store.fetchJobs(); expect(store.jobs[0].status).toBe('failed')
  })
  it('ignores an older list response after a toggle', async () => {
    let resolve!: (response: Response) => void
    vi.stubGlobal('fetch', vi.fn().mockImplementationOnce(() => new Promise(r => { resolve = r })))
    const store = useCronStore(); store.jobs = [{ ...sampleJobs[0], enabled: false }]
    const pending = store.fetchJobs()
    vi.mocked(fetch).mockResolvedValueOnce({ ok: true, json: async () => ({ ...sampleJobs[0], enabled: true }) } as Response)
    await store.toggleJob('1', true)
    resolve({ ok: true, json: async () => [{ ...sampleJobs[0], enabled: false }] } as Response); await pending
    expect(store.jobs[0].enabled).toBe(true)
  })
})
