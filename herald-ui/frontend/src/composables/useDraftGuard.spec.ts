import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'
import { useDraftGuard } from './useDraftGuard'
import DraftDialog from '@/components/DraftDialog.vue'
afterEach(() => vi.restoreAllMocks())
it('guards route navigation, failed saves, discard and browser unload', async () => {
  HTMLDialogElement.prototype.showModal = function () { this.open = true }
  HTMLDialogElement.prototype.close = function () { this.open = false }
  const dirty = ref(true), save = vi.fn().mockResolvedValue(false)
  const Page = defineComponent({ components: { DraftDialog },
    setup() { return { guard: useDraftGuard(() => dirty.value, save, () => { dirty.value = false }) } },
    template: '<DraftDialog :open="guard.open.value" :busy="guard.busy.value" @choose="guard.choose" />' })
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/', component: Page }, { path: '/other', component: { template: '<p>Other</p>' } }] })
  await router.push('/')
  const wrapper = mount(RouterView, { global: { plugins: [router] } })
  await router.isReady(); await flushPromises()
  const unload = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(unload); expect(unload.defaultPrevented).toBe(true)
  let navigation = router.push('/other'); await flushPromises()
  expect(wrapper.find('dialog').attributes('open')).toBeDefined()
  await wrapper.findAll('button')[2]!.trigger('click'); await navigation
  expect(router.currentRoute.value.path).toBe('/')
  navigation = router.push('/other'); await flushPromises()
  await wrapper.findAll('button')[0]!.trigger('click'); await navigation
  expect(save).toHaveBeenCalledOnce(); expect(router.currentRoute.value.path).toBe('/')
  expect(dirty.value).toBe(true)
  navigation = router.push('/other'); await flushPromises()
  await wrapper.findAll('button')[1]!.trigger('click'); await navigation
  expect(router.currentRoute.value.path).toBe('/other'); wrapper.unmount()
})
