import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import MemoryViewer from './MemoryViewer.vue'

const sampleEntries = [
  { key: 'user.name', value: 'Alice', lastUpdated: '2026-03-10T10:00:00Z' },
  { key: 'bot.mode', value: 'production', lastUpdated: '2026-03-10T09:00:00Z' },
]

function mountPage() {
  return mount(MemoryViewer, {
    global: {
      plugins: [createPinia()],
    },
  })
}

describe('MemoryViewer.vue', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(sampleEntries),
    }))
  })

  it('renders the page title', () => {
    const wrapper = mountPage()
    expect(wrapper.text()).toContain('Memory Viewer')
  })

  it('shows loading state initially', () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => new Promise(() => {})))
    const wrapper = mountPage()
    expect(wrapper.text()).toContain('Loading memory entries')
  })

  it('displays memory entries after data loads', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('user.name')
    })
    expect(wrapper.text()).toContain('Alice')
    expect(wrapper.text()).toContain('bot.mode')
    expect(wrapper.text()).toContain('production')
  })

  it('shows empty state when no entries', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve([]),
    }))
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('No memory entries yet')
    })
  })

  it('has filter input', () => {
    const wrapper = mountPage()
    const input = wrapper.find('input[placeholder="Filter by key name…"]')
    expect(input.exists()).toBe(true)
  })

  it('has export and import buttons', () => {
    const wrapper = mountPage()
    expect(wrapper.text()).toContain('Export JSON')
    expect(wrapper.text()).toContain('Import JSON')
  })

  it('has new entry input fields', () => {
    const wrapper = mountPage()
    const keyInput = wrapper.find('input[placeholder="New key"]')
    const valueInput = wrapper.find('input[placeholder="Value"]')
    expect(keyInput.exists()).toBe(true)
    expect(valueInput.exists()).toBe(true)
  })

  it('shows inline edit on value click', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('Alice')
    })

    // Click the value span to edit
    const valueSpan = wrapper.find('span.cursor-pointer')
    await valueSpan.trigger('click')

    // Should show Save and Cancel buttons
    expect(wrapper.text()).toContain('Save')
    expect(wrapper.text()).toContain('Cancel')
  })

  it('shows delete confirmation on trash click', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('user.name')
    })

    // Click the delete button (first trash icon)
    const deleteBtn = wrapper.find('button[title="Delete entry"]')
    await deleteBtn.trigger('click')

    expect(wrapper.text()).toContain('Confirm')
  })

  it('filters entries by key name', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('user.name')
    })

    const filterInput = wrapper.find('input[placeholder="Filter by key name…"]')
    await filterInput.setValue('bot')

    expect(wrapper.text()).toContain('bot.mode')
    expect(wrapper.text()).not.toContain('user.name')
  })
})
