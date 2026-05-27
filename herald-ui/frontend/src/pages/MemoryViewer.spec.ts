import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import MemoryViewer from './MemoryViewer.vue'

const sampleEntries = [
  { key: 'user.name', value: 'Alice', lastUpdated: '2026-03-10T10:00:00Z' },
  { key: 'bot.mode', value: 'production', lastUpdated: '2026-03-10T09:00:00Z' },
]

const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/memory', component: MemoryViewer }],
})

async function mountPage() {
  router.push('/memory')
  await router.isReady()
  return mount(MemoryViewer, {
    global: {
      plugins: [createPinia(), router],
    },
  })
}

async function switchToKvTab(wrapper: ReturnType<typeof mount>) {
  const kvTab = wrapper.findAll('.tab-btn').find((b) => b.text() === 'Key-Value Store')
  await kvTab!.trigger('click')
}

describe('MemoryViewer.vue', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      if (String(url).includes('/api/memory/files')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({}) })
      }
      if (String(url).includes('/api/obsidian')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve([]) })
      }
      return Promise.resolve({ ok: true, json: () => Promise.resolve(sampleEntries) })
    }))
  })

  it('renders the page title', async () => {
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain('Memory Viewer')
  })

  it('shows tab labels', async () => {
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain('Wiki Memory')
    expect(wrapper.text()).toContain('Key-Value Store')
    expect(wrapper.text()).toContain('Obsidian')
  })

  it('shows loading state on kv tab initially', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      if (String(url).includes('/api/memory') && !String(url).includes('/files')) {
        return new Promise(() => {})
      }
      return Promise.resolve({ ok: true, json: () => Promise.resolve([]) })
    }))
    const wrapper = await mountPage()
    await switchToKvTab(wrapper)
    expect(wrapper.text()).toContain('Loading memory entries')
  })

  it('displays memory entries after data loads on kv tab', async () => {
    const wrapper = await mountPage()
    await switchToKvTab(wrapper)
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('user.name')
    })
    expect(wrapper.text()).toContain('Alice')
  })

  it('shows empty state when no entries on kv tab', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      if (String(url).includes('/api/memory') && !String(url).includes('/files')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve([]) })
      }
      return Promise.resolve({ ok: true, json: () => Promise.resolve([]) })
    }))
    const wrapper = await mountPage()
    await switchToKvTab(wrapper)
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('No memory entries yet')
    })
  })

  it('has filter input on kv tab', async () => {
    const wrapper = await mountPage()
    await switchToKvTab(wrapper)
    const input = wrapper.find('input[placeholder="Filter by key name…"]')
    expect(input.exists()).toBe(true)
  })

  it('has export and import buttons on kv tab', async () => {
    const wrapper = await mountPage()
    await switchToKvTab(wrapper)
    expect(wrapper.text()).toContain('Export JSON')
    expect(wrapper.text()).toContain('Import JSON')
  })
})
