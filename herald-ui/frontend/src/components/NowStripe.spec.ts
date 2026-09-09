import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import NowStripe from './NowStripe.vue'
import { useStatusStore } from '@/stores/status'

afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })

it('shares one stream between mounted stripes and cannot restart after unmount during fetch', async () => {
  vi.useFakeTimers()
  let complete!: (value: unknown) => void
  const close = vi.fn()
  const source = { onerror: null as any, onmessage: null, onopen: null, close, addEventListener: vi.fn() }
  const construct = vi.fn(function () { return source })
  vi.stubGlobal('EventSource', construct)
  vi.stubGlobal('fetch', vi.fn(() => new Promise(resolve => { complete = resolve })))
  const pinia = createPinia(), options = { global: { plugins: [pinia] } }
  const first = mount(NowStripe, options), second = mount(NowStripe, options)
  expect(construct).toHaveBeenCalledTimes(1)
  expect(second.text()).toContain('Loading status')
  expect(second.text()).not.toContain('Bot offline')
  first.unmount()
  expect(close).not.toHaveBeenCalled()
  source.onerror()
  second.unmount()
  complete({ ok: true, json: async () => ({}) })
  await flushPromises()
  vi.runAllTimers()
  expect(construct).toHaveBeenCalledTimes(1)
  expect(useStatusStore(pinia).lastUpdated).toBeNull()
})

it('keeps last updated visible while reconnecting and waits for a fresh snapshot', async () => {
  const pinia = createPinia()
  vi.stubGlobal('EventSource', vi.fn(function () { return { onerror: null, onmessage: null, onopen: null, close: vi.fn(), addEventListener: vi.fn() } }))
  vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})))
  const wrapper = mount(NowStripe, { global: { plugins: [pinia] } })
  const store = useStatusStore(pinia)
  store.lastUpdated = '2026-09-09T10:00:00Z'
  store.connectionState = 'reconnecting'
  await wrapper.vm.$nextTick()
  expect(wrapper.text()).toContain('Reconnecting')
  expect(wrapper.text()).toContain('Last updated')
  expect(wrapper.text()).not.toContain('Bot offline')
  store.connectionState = 'live'
  await wrapper.vm.$nextTick()
  expect(wrapper.text()).toContain('Stale status')
  wrapper.unmount()
})
