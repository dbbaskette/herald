import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import PromptsEditor from './PromptsEditor.vue'
const detail = (name: string, content: string) => ({ name, displayName: name, content, defaultContent: '', overridden: false })
afterEach(() => vi.unstubAllGlobals())
it('keeps dirty prompt on failed save and external conflict, then explicitly reconciles', async () => {
  HTMLDialogElement.prototype.showModal = function () { this.open = true }
  HTMLDialogElement.prototype.close = function () { this.open = false }
  const fetcher = vi.fn(async (url: string, options?: RequestInit) => {
    if (options?.method === 'PUT') return new Response(JSON.stringify({ content: 'external', version: '"two"', error: 'Changed externally' }), { status: 412 })
    if (url === '/api/prompts') return new Response(JSON.stringify([detail('CONTEXT.md', 'original'), detail('other', 'other')]))
    return new Response(JSON.stringify(detail('CONTEXT.md', 'original')), { headers: { ETag: '"one"' } })
  })
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mount(PromptsEditor, { global: { stubs: { NowStripe: true, DiffEditor: true } } })
  await flushPromises()
  await wrapper.find('textarea').setValue('my draft')
  await wrapper.findAll('.prompt-list-item')[1]!.trigger('click'); await flushPromises()
  expect(wrapper.find('dialog').exists()).toBe(true)
  await wrapper.findAll('dialog button')[2]!.trigger('click'); await flushPromises()
  expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('my draft')
  await wrapper.findAll('.prompt-list-item')[1]!.trigger('click'); await flushPromises()
  await wrapper.findAll('dialog button')[0]!.trigger('click'); await flushPromises()
  expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('my draft')
  expect(wrapper.text()).toContain('This file changed externally')
  expect(fetcher.mock.calls.find(c => c[1]?.method === 'PUT')?.[1]?.headers).toMatchObject({ 'If-Match': '"one"' })
  await wrapper.findAll('.conflict button')[1]!.trigger('click')
  expect(wrapper.find('.conflict').exists()).toBe(false)
  expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('my draft')
  wrapper.unmount()
})
