import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ConversationHistory from './ConversationHistory.vue'

const samplePage = {
  content: [
    {
      id: 'msg-1',
      role: 'assistant',
      content: 'Good morning briefing',
      timestamp: '2026-03-10T10:00:00Z',
      toolCalls: [
        { name: 'read_file', inputs: { path: '/tmp/a.txt' }, outputs: 'file contents here' },
      ],
      subagentCalls: [
        { name: 'research-agent', toolCalls: [], result: 'research complete' },
      ],
    },
    {
      id: 'msg-2',
      role: 'user',
      content: 'Thanks for the update',
      timestamp: '2026-03-10T09:00:00Z',
      toolCalls: [],
      subagentCalls: [],
    },
  ],
  totalPages: 2,
  totalElements: 75,
  number: 0,
}

function mountPage() {
  return mount(ConversationHistory, {
    global: {
      plugins: [createPinia()],
    },
  })
}

describe('ConversationHistory.vue', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(samplePage),
    }))
  })

  it('renders the page title', () => {
    const wrapper = mountPage()
    expect(wrapper.text()).toContain('Conversation History')
  })

  it('shows loading state while fetching', async () => {
    let resolveFetch: (v: unknown) => void
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => new Promise(r => { resolveFetch = r })))
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('Loading messages')
    })
    resolveFetch!({ ok: true, json: () => Promise.resolve({ content: [], totalPages: 0, totalElements: 0, number: 0 }) })
  })

  it('displays messages after data loads', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('Good morning briefing')
    })
    expect(wrapper.text()).toContain('Thanks for the update')
    expect(wrapper.text()).toContain('assistant')
    expect(wrapper.text()).toContain('user')
  })

  it('shows empty state when no messages', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ content: [], totalPages: 0, totalElements: 0, number: 0 }),
    }))
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('No conversation history yet')
    })
  })

  it('shows tool call count and expands on click', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('Tool Calls (1)')
    })

    // Tool name should be visible as button text
    expect(wrapper.text()).toContain('read_file')

    // Click to expand
    const toolBtn = wrapper.find('button.w-full')
    await toolBtn.trigger('click')

    expect(wrapper.text()).toContain('Inputs')
    expect(wrapper.text()).toContain('/tmp/a.txt')
    expect(wrapper.text()).toContain('Outputs')
    expect(wrapper.text()).toContain('file contents here')
  })

  it('shows subagent calls and expands on click', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('Subagent Calls (1)')
    })

    expect(wrapper.text()).toContain('research-agent')

    // Click to expand subagent
    const subBtn = wrapper.findAll('button.w-full')[1]
    await subBtn.trigger('click')

    expect(wrapper.text()).toContain('Result')
    expect(wrapper.text()).toContain('research complete')
  })

  it('shows pagination controls', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('Page 1 of 2')
    })
    expect(wrapper.text()).toContain('75 messages')
    expect(wrapper.text()).toContain('Previous')
    expect(wrapper.text()).toContain('Next')
  })

  it('has search input', () => {
    const wrapper = mountPage()
    const input = wrapper.find('input[placeholder="Search messages…"]')
    expect(input.exists()).toBe(true)
  })

  it('has date range inputs', () => {
    const wrapper = mountPage()
    const dateInputs = wrapper.findAll('input[type="date"]')
    expect(dateInputs).toHaveLength(2)
  })

  it('has clear history button with confirmation', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('Good morning briefing')
    })

    expect(wrapper.text()).toContain('Clear History')

    // Click Clear History
    const clearBtn = wrapper.findAll('button').find(b => b.text() === 'Clear History')!
    await clearBtn.trigger('click')

    // Should show confirmation
    expect(wrapper.text()).toContain('Clear all history?')
    expect(wrapper.text()).toContain('Confirm')
  })

  it('shows search and clear filters buttons', () => {
    const wrapper = mountPage()
    expect(wrapper.text()).toContain('Search')
    expect(wrapper.text()).toContain('Clear Filters')
  })
})
