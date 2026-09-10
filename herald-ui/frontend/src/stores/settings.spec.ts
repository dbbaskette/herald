import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useSettingsStore, validateSettings } from './settings'

const success = { success: true, savedSettings: { 'agent.persona': 'Saved' }, settings: {}, validationErrors: {}, error: null, message: 'Saved' }
describe('settings store', () => {
  beforeEach(() => setActivePinia(createPinia()))
  afterEach(() => vi.unstubAllGlobals())
  it('returns failure while preserving saved state and distinguishing save errors', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, json: async () => ({ success: false, message: 'Database busy' }) }))
    const store = useSettingsStore()
    store.settings = { 'agent.persona': 'Before' }
    expect(await store.saveSettings({ 'agent.persona': 'Draft' })).toEqual({ success: false, reason: 'request' })
    expect(store.settings['agent.persona']).toBe('Before')
    expect(store.loadError).toBeNull()
    expect(store.saveError).toBe('Database busy')
  })
  it('rejects concurrent submissions and can retry after failure', async () => {
    let reject!: (reason: Error) => void
    const fetch = vi.fn().mockImplementationOnce(() => new Promise((_, r) => { reject = r }))
      .mockResolvedValue({ ok: true, json: async () => success })
    vi.stubGlobal('fetch', fetch)
    const store = useSettingsStore()
    const first = store.saveSettings({ 'agent.persona': 'Saved' })
    expect(await store.saveSettings({ 'agent.persona': 'Duplicate' })).toEqual({ success: false, reason: 'busy' })
    expect(fetch).toHaveBeenCalledTimes(1)
    reject(new Error('Offline'))
    expect((await first).success).toBe(false)
    expect((await store.saveSettings({ 'agent.persona': 'Saved' })).success).toBe(true)
    expect(store.settings['agent.persona']).toBe('Saved')
    expect(store.saveError).toBeNull()
  })
  it('validates malformed and out-of-range context and timezone without a request', async () => {
    vi.stubGlobal('fetch', vi.fn())
    const store = useSettingsStore()
    for (const value of ['', '0', '-1', '1.5', '2000001', 'bad']) {
      expect((await store.saveSettings({ 'agent.max-context-tokens': value })).success).toBe(false)
    }
    expect((await store.saveSettings({ 'cron.timezone': 'Bad/Zone' })).success).toBe(false)
    expect(fetch).not.toHaveBeenCalled()
    expect(validateSettings({ 'cron.timezone': 'UTC', 'agent.max-context-tokens': '2000000' })).toEqual({})
  })
  it('keeps load failure separate from save failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Load unavailable')))
    const store = useSettingsStore()
    expect(await store.fetchSettings()).toBe(false)
    expect(store.loadError).toBe('Load unavailable')
    expect(store.saveError).toBeNull()
  })
})
