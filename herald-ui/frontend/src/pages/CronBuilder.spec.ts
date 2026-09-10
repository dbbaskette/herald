import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, enableAutoUnmount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import CronBuilder from './CronBuilder.vue'

enableAutoUnmount(afterEach)

const sampleJobs = [
  {
    id: '1', name: 'Morning Briefing', expression: '0 9 * * *', enabled: true,
    builtIn: true, promptText: 'Run morning briefing', status: 'success',
    lastRun: '2026-03-10T09:00:00Z', nextRun: '2026-03-11T09:00:00Z',
    lastRunLog: 'Briefing completed successfully',
  },
  {
    id: '2', name: 'Weekly Review', expression: '0 10 * * 1', enabled: false,
    builtIn: false, promptText: 'Run weekly review', status: 'idle',
    lastRun: null, nextRun: '2026-03-16T10:00:00Z', lastRunLog: null,
  },
]

function mountPage() {
  return mount(CronBuilder, {
    global: {
      plugins: [createPinia()],
      stubs: { NowStripe: true },
    },
  })
}

describe('CronBuilder.vue', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(sampleJobs),
    }))
  })

  it('renders the page title', () => {
    const wrapper = mountPage()
    expect(wrapper.text()).toContain('Cron')
  })

  it('shows loading state initially', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => new Promise(() => {})))
    const wrapper = mountPage()
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('Loading cron jobs…')
  })

  it('displays cron jobs after data loads', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('Morning Briefing')
    })
    expect(wrapper.text()).toContain('Weekly Review')
    expect(wrapper.text()).toContain('0 9 * * *')
  })

  it('shows human-readable schedule', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('daily at 09:00')
    })
  })

  it('has New Job button', () => {
    const wrapper = mountPage()
    expect(wrapper.text()).toContain('+ New job')
  })

  it('opens edit panel when New Job is clicked', async () => {
    const wrapper = mountPage()
    const newJobBtn = wrapper.findAll('button').find(b => b.text() === '+ New job')!
    await newJobBtn.trigger('click')
    expect(wrapper.text()).toContain('Name')
    expect(wrapper.text()).toContain('Schedule')
    expect(wrapper.text()).toContain('Prompt')
  })

  it('opens edit panel when edit button is clicked', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('Morning Briefing')
    })
    const editBtn = wrapper.findAll('button').find(button => button.text() === 'edit')!
    await editBtn.trigger('click')
    expect(wrapper.text()).toContain('EDIT JOB')
  })

  it('shows enable/disable toggles', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('Morning Briefing')
    })
    const toggleBtns = wrapper.findAll('button[title="Disable"], button[title="Enable"]')
    expect(toggleBtns.length).toBeGreaterThanOrEqual(2)
  })

  it('has Run Now buttons', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('Morning Briefing')
    })
    expect(wrapper.text()).toContain('run')
  })

  it('does not show delete button for built-in jobs', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('Morning Briefing')
    })
    // Find delete buttons - only non-built-in jobs should have them
    const deleteBtns = wrapper.findAll('button').filter(button => button.text() === 'del')
    expect(deleteBtns).toHaveLength(1) // Only Weekly Review (not built-in)
  })

  it('shows expand button for jobs with logs', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('Morning Briefing')
    })
    const expandBtn = wrapper.findAll('button').find(button => button.text() === 'log')!
    expect(expandBtn.exists()).toBe(true)
  })

  it('expands log row on click', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('Morning Briefing')
    })
    const expandBtn = wrapper.findAll('button').find(button => button.text() === 'log')!
    await expandBtn.trigger('click')
    expect(wrapper.text()).toContain('LAST RUN OUTPUT')
    expect(wrapper.text()).toContain('Briefing completed successfully')
  })

  it('visual cron builder generates valid expression', async () => {
    const wrapper = mountPage()
    const newJobBtn = wrapper.findAll('button').find(b => b.text() === '+ New job')!
    await newJobBtn.trigger('click')

    // Default should show an expression
    expect(wrapper.text()).toContain('0 9 * * *')
  })

  it('shows empty state when no jobs', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve([]),
    }))
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('No cron jobs configured.')
    })
  })

  it('shows delete confirmation for non-built-in jobs', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('Weekly Review')
    })
    const deleteBtn = wrapper.findAll('button').find(button => button.text() === 'del')!
    await deleteBtn.trigger('click')
    expect(wrapper.text()).toContain('delete')
  })

  it('preserves six-field steps ranges and lists when saving an existing job', async () => {
    const expression = '15 */10 9-17 * * MON,WED'
    const advanced = { ...sampleJobs[1], expression }
    vi.stubGlobal('fetch', vi.fn().mockImplementation((_url: string, init?: RequestInit) => Promise.resolve({
      ok: true, json: async () => init?.method === 'PUT' ? advanced : [advanced],
    })))
    const wrapper = mountPage()
    await vi.waitFor(() => expect(wrapper.text()).toContain('Weekly Review'))
    await wrapper.findAll('button').find(b => b.text() === 'edit')!.trigger('click')
    expect((wrapper.find('#cron-expression').element as HTMLInputElement).value).toBe(expression)
    await wrapper.findAll('button').find(b => b.text() === 'Save')!.trigger('click')
    await vi.waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/cron/2', expect.objectContaining({
      method: 'PUT', body: JSON.stringify({ name: advanced.name, expression, promptText: advanced.promptText, enabled: false }),
    })))
    wrapper.unmount()
  })

  it('keeps draft and shows backend validation errors', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => expect(wrapper.text()).toContain('Weekly Review'))
    await wrapper.findAll('button').find(b => b.text() === 'edit')!.trigger('click')
    await wrapper.find('#cron-expression').setValue('999 * * * *')
    vi.mocked(fetch).mockResolvedValue({ ok: false, status: 400, json: async () => ({ message: 'Invalid cron expression: minute must be 0-59' }) } as Response)
    await wrapper.findAll('button').find(b => b.text() === 'Save')!.trigger('click')
    await vi.waitFor(() => expect(wrapper.find('[role="alert"]').text()).toContain('minute must be 0-59'))
    expect((wrapper.find('#cron-expression').element as HTMLInputElement).value).toBe('999 * * * *')
    wrapper.unmount()
  })

  it('refreshes queued execution and stops polling when unmounted', async () => {
    vi.useFakeTimers()
    try {
      const queued = { ...sampleJobs[1], status: 'queued' }
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => [queued] }))
      const wrapper = mountPage()
      await vi.advanceTimersByTimeAsync(0)
      expect(wrapper.text()).toContain('queued')
      vi.mocked(fetch).mockResolvedValue({ ok: true, json: async () => [{ ...queued, status: 'completed' }] } as Response)
      await vi.advanceTimersByTimeAsync(5000)
      expect(wrapper.text()).toContain('completed')
      wrapper.unmount()
      const calls = vi.mocked(fetch).mock.calls.length
      await vi.advanceTimersByTimeAsync(15000)
      expect(fetch).toHaveBeenCalledTimes(calls)
    } finally { vi.useRealTimers() }
  })
})
