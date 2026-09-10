import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises, enableAutoUnmount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import SettingsPage from './SettingsPage.vue'

enableAutoUnmount(afterEach)
const saved = { 'agent.persona': 'Before', 'agent.max-context-tokens': '200000', 'cron.timezone': 'UTC' }
function result(values = saved) {
  return { success: true, savedSettings: values, validationErrors: {}, settings: {
    'agent.persona': { saved: values['agent.persona'], effective: 'Runtime Persona', source: 'environment:HERALD_AGENT_PERSONA',
      environmentVariable: 'HERALD_AGENT_PERSONA', application: 'configuration-managed', restartRequired: true },
    'cron.timezone': { saved: 'UTC', effective: 'UTC', source: 'configuration:fixture.yaml',
      environmentVariable: 'HERALD_CRON_TIMEZONE', application: 'matches-runtime', restartRequired: false },
  } }
}
const reply = (data: unknown, ok = true) => ({ ok, status: ok ? 200 : 500, json: async () => data })
const persona = '[id="setting-agent.persona"]'
function page() { return mount(SettingsPage, { global: { plugins: [createPinia()], stubs: { NowStripe: true, MeetingsProgress: true } } }) }
describe('SettingsPage', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string) => {
      if (url === '/api/settings') return reply(result())
      if (url === '/api/gws/status') return reply({ installed: true, clientConfigured: false, authenticated: false })
      return reply({}, false)
    }))
  })
  afterEach(() => vi.unstubAllGlobals())
  it('keeps the draft and form after failed PUT and retries the same update', async () => {
    const base = vi.mocked(fetch).getMockImplementation()!
    const bodies: string[] = []
    vi.mocked(fetch).mockImplementation(async (url, options) => {
      if (options?.method === 'PUT') {
        bodies.push(String(options.body))
        return (bodies.length === 1 ? reply({ success: false, message: 'Database busy' }, false)
          : reply(result({ ...saved, 'agent.persona': 'Draft' }))) as Response
      }
      return base(url, options)
    })
    const wrapper = page(); await flushPromises()
    await wrapper.get(persona).setValue('Draft')
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(wrapper.find('form').exists()).toBe(true)
    expect((wrapper.get(persona).element as HTMLInputElement).value).toBe('Draft')
    expect(wrapper.text()).toContain('Database busy')
    expect(wrapper.text()).not.toContain('Failed to load settings')
    await wrapper.findAll('button').find(button => button.text() === 'Retry')!.trigger('click')
    await flushPromises()
    expect(bodies).toEqual(['{"agent.persona":"Draft"}', '{"agent.persona":"Draft"}'])
    expect(wrapper.text()).toContain('Preferences saved; runtime configuration unchanged')
  })
  it('prevents invalid timezone/context submission and shows inline errors', async () => {
    const wrapper = page(); await flushPromises()
    await wrapper.get('[id="setting-cron.timezone"]').setValue('Not/AZone')
    await wrapper.get('[id="setting-agent.max-context-tokens"]').setValue('0')
    expect(wrapper.text()).toContain('Enter a valid timezone')
    expect(wrapper.text()).toContain('Enter a whole number')
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit')
    expect(vi.mocked(fetch).mock.calls.filter(([, options]) => options?.method === 'PUT')).toHaveLength(0)
  })
  it('disables both Retry and Save while retrying a failed draft', async () => {
    const base = vi.mocked(fetch).getMockImplementation()!
    let puts = 0
    let resolve!: (value: Response) => void
    vi.mocked(fetch).mockImplementation((url, options) => {
      if (options?.method !== 'PUT') return base(url, options)
      if (++puts === 1) return Promise.resolve(reply({ success: false, message: 'Busy' }, false) as Response)
      return new Promise<Response>(r => { resolve = r })
    })
    const wrapper = page(); await flushPromises()
    await wrapper.get(persona).setValue('Draft')
    await wrapper.get('form').trigger('submit'); await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === 'Retry')!.trigger('click')
    expect(wrapper.findAll('button').find(button => button.text() === 'Retry')!.attributes('disabled')).toBeDefined()
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    resolve(reply(result({ ...saved, 'agent.persona': 'Draft' })) as Response)
    await flushPromises()
    expect(puts).toBe(2)
  })
  it('blocks duplicate submits while allowing edits made during the save to remain', async () => {
    const base = vi.mocked(fetch).getMockImplementation()!
    let resolve!: (value: Response) => void
    vi.mocked(fetch).mockImplementation((url, options) => options?.method === 'PUT'
      ? new Promise<Response>(r => { resolve = r }) : base(url, options))
    const wrapper = page(); await flushPromises()
    await wrapper.get(persona).setValue('Submitted')
    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    await wrapper.get(persona).setValue('Newer draft')
    resolve(reply(result({ ...saved, 'agent.persona': 'Submitted' })) as Response)
    await flushPromises()
    expect((wrapper.get(persona).element as HTMLInputElement).value).toBe('Newer draft')
    expect(vi.mocked(fetch).mock.calls.filter(([, options]) => options?.method === 'PUT')).toHaveLength(1)
  })
  it('renders saved/effective/source status and accurate configuration and Google instructions', async () => {
    const wrapper = page(); await flushPromises()
    expect(wrapper.text()).toContain('Saved preference: Before')
    expect(wrapper.text()).toContain('Runtime effective: Runtime Persona')
    expect(wrapper.text()).toContain('environment:HERALD_AGENT_PERSONA')
    expect(wrapper.text()).toContain('Restart alone does not apply this saved preference')
    expect(wrapper.text()).toContain('Matches runtime configuration')
    expect(wrapper.text()).toContain('docs/gws-setup.md')
    expect(wrapper.text()).toContain('./run.sh all to reload the environment')
    expect(wrapper.text()).not.toContain('enter Client ID and Secret above')
    expect(wrapper.text()).not.toContain('take effect immediately')
  })
  it('load failure has its own retry and reset discards only by explicit action', async () => {
    vi.mocked(fetch).mockRejectedValueOnce(new Error('Load unavailable'))
    const wrapper = page(); await flushPromises()
    expect(wrapper.text()).toContain('Failed to load settings')
    await wrapper.findAll('button').find(button => button.text() === 'Retry load')!.trigger('click')
    await flushPromises()
    await wrapper.get(persona).setValue('Draft')
    await wrapper.findAll('button').find(button => button.text() === 'Reset')!.trigger('click')
    expect((wrapper.get(persona).element as HTMLInputElement).value).toBe('Before')
  })
})
