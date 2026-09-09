import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import MeetingsProgress from './MeetingsProgress.vue'
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })
it('shows actual outcomes and retries only failed meetings', async () => {
  vi.useFakeTimers()
  const fetcher = vi.fn().mockResolvedValue({ ok: true, json: async () => [
    { meetingId: 'a', title: 'Saved fixture', state: 'succeeded', attempts: 1 },
    { meetingId: 'b /', title: 'Failed fixture', state: 'failed', attempts: 1, error: 'Delivery failed' },
  ] })
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mount(MeetingsProgress)
  await flushPromises()
  expect(wrapper.text()).toContain('1 succeeded · 1 failed')
  expect(wrapper.findAll('button')).toHaveLength(1)
  await wrapper.get('button').trigger('click'); await flushPromises()
  expect(fetcher).toHaveBeenCalledWith('/api/meetings/retry?id=b%20%2F', { method: 'POST' })
  wrapper.unmount()
  const calls = fetcher.mock.calls.length
  await vi.advanceTimersByTimeAsync(10000)
  expect(fetcher).toHaveBeenCalledTimes(calls)
})
