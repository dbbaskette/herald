import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import CronBuilder from './CronBuilder.vue'

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
    expect(wrapper.text()).toContain('Cron Builder')
  })

  it('shows loading state initially', () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => new Promise(() => {})))
    const wrapper = mountPage()
    expect(wrapper.text()).toContain('Loading cron jobs')
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
      expect(wrapper.text()).toContain('Daily at 09:00')
    })
  })

  it('has New Job button', () => {
    const wrapper = mountPage()
    expect(wrapper.text()).toContain('New Job')
  })

  it('opens edit panel when New Job is clicked', async () => {
    const wrapper = mountPage()
    const newJobBtn = wrapper.find('button')
    await newJobBtn.trigger('click')
    expect(wrapper.text()).toContain('Job Name')
    expect(wrapper.text()).toContain('Schedule')
    expect(wrapper.text()).toContain('Prompt Text')
  })

  it('opens edit panel when edit button is clicked', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('Morning Briefing')
    })
    const editBtn = wrapper.find('button[title="Edit job"]')
    await editBtn.trigger('click')
    expect(wrapper.text()).toContain('Edit Job')
  })

  it('shows enable/disable toggles', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('Morning Briefing')
    })
    const toggleBtns = wrapper.findAll('button[title="Disable job"], button[title="Enable job"]')
    expect(toggleBtns.length).toBeGreaterThanOrEqual(2)
  })

  it('has Run Now buttons', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('Morning Briefing')
    })
    expect(wrapper.text()).toContain('Run Now')
  })

  it('does not show delete button for built-in jobs', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('Morning Briefing')
    })
    // Find delete buttons - only non-built-in jobs should have them
    const deleteBtns = wrapper.findAll('button[title="Delete job"]')
    expect(deleteBtns).toHaveLength(1) // Only Weekly Review (not built-in)
  })

  it('shows expand button for jobs with logs', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('Morning Briefing')
    })
    const expandBtn = wrapper.find('button[title="Show last run log"]')
    expect(expandBtn.exists()).toBe(true)
  })

  it('expands log row on click', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('Morning Briefing')
    })
    const expandBtn = wrapper.find('button[title="Show last run log"]')
    await expandBtn.trigger('click')
    expect(wrapper.text()).toContain('Last Run Output')
    expect(wrapper.text()).toContain('Briefing completed successfully')
  })

  it('visual cron builder generates valid expression', async () => {
    const wrapper = mountPage()
    const newJobBtn = wrapper.find('button')
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
      expect(wrapper.text()).toContain('No cron jobs configured yet')
    })
  })

  it('shows delete confirmation for non-built-in jobs', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('Weekly Review')
    })
    const deleteBtn = wrapper.find('button[title="Delete job"]')
    await deleteBtn.trigger('click')
    expect(wrapper.text()).toContain('Confirm')
  })
})
