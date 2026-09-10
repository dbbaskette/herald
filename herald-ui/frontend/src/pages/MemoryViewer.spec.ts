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
  const kvTab = wrapper.findAll('.tab-btn').find((b) => b.text() === 'Legacy Key·Value')
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
    expect(wrapper.text()).toContain('Memory')
  })

  it('shows tab labels', async () => {
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain('File Memory')
    expect(wrapper.text()).toContain('Key·Value')
    expect(wrapper.findAll('.tab-btn').map(b => b.text())).not.toContain('Obsidian')
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
    expect(wrapper.text()).toContain('Loading…')
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
      expect(wrapper.text()).toContain('no legacy entries retained')
    })
  })

  it('has filter input on kv tab', async () => {
    const wrapper = await mountPage()
    await switchToKvTab(wrapper)
    const input = wrapper.find('input[placeholder="filter by key…"]')
    expect(input.exists()).toBe(true)
  })

  it('has export and import buttons on kv tab', async () => {
    const wrapper = await mountPage()
    await switchToKvTab(wrapper)
    expect(wrapper.text()).toContain('Export legacy backup')
    expect(wrapper.text()).toContain('Import legacy JSON')
  })
})

describe('wiki editor', () => {
  it('requires confirmation before a recoverable deletion', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ok:true,json:async()=>({})}))
    const wrapper = await mountPage()
    const {useFileMemoryStore} = await import('@/stores/fileMemory')
    const store = useFileMemoryStore()
    store.selected={path:'a.md',content:'A',version:'v1',size:1};store.draft='A'
    await wrapper.vm.$nextTick()
    const remove=vi.spyOn(store,'deleteSelected').mockResolvedValue(true)
    await wrapper.findAll('button').find(b=>b.text()==='Delete')!.trigger('click')
    expect(remove).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Move memory to trash?')
    await wrapper.findAll('button').find(b=>b.text()==='Move to trash')!.trigger('click')
    expect(remove).toHaveBeenCalledOnce()
    wrapper.unmount()
  })
  it('shows attribution and preserves unsaved editor text', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ok:true,json:async()=>({})}))
    const wrapper=await mountPage()
    const {useFileMemoryStore}=await import('@/stores/fileMemory');const store=useFileMemoryStore()
    store.grouped={user:[{path:'a.md',name:null,description:null,type:'user',size:1,lastModified:'today',conversationId:'web-test'}]}
    store.selected={path:'a.md',content:'A',version:'v1',size:1};store.draft='A'
    await wrapper.vm.$nextTick()
    expect(wrapper.find('a[href="/history?conversationId=web-test"]').exists()).toBe(true)
    await wrapper.find('textarea[aria-label="Memory Markdown editor"]').setValue('Draft')
    await wrapper.findAll('button').find(b=>b.text()==='← Back')!.trigger('click')
    expect(store.draft).toBe('Draft');expect(store.selected?.path).toBe('a.md')
    expect(wrapper.text()).toContain('Unsaved changes')
    wrapper.unmount()
  })
})

it('distinguishes canonical learned files from legacy manual references and scopes backup controls', async () => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => Promise.resolve({
    ok: true, json: async () => url === '/api/memory' ? sampleEntries : {},
  })))
  const wrapper = await mountPage()
  expect(wrapper.find('.hint').text()).toContain('Canonical learned memory')
  expect(wrapper.findAll('.header-actions button')).toHaveLength(0)
  await switchToKvTab(wrapper)
  expect(wrapper.find('.hint').text()).toContain('not automatically learned')
  expect(wrapper.find('.legacy-ownership').text()).toContain('do not update learned Markdown notes or runtime settings')
  expect(wrapper.find('.legacy-ownership').text()).toContain('overwrites matching keys')
  expect(wrapper.findAll('.header-actions button').map(button => button.text()))
    .toEqual(['Export legacy backup', 'Import legacy JSON'])
  wrapper.unmount()
})
