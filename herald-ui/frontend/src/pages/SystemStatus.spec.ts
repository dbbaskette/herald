import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import SystemStatus from './SystemStatus.vue'
import { useStatusStore } from '@/stores/status'
import type { SystemStatus as SystemStatusType } from '@/stores/status'

// Stub EventSource globally
vi.stubGlobal('EventSource', vi.fn().mockImplementation(() => ({
  onopen: null,
  onmessage: null,
  onerror: null,
  close: vi.fn(),
})))

const fullStatus: SystemStatusType = {
  healthy: true,
  bot: { running: true, pid: 1234, uptime: '2h 15m', restartCount: 1 },
  model: { name: 'claude-sonnet-4-20250514', requestsToday: 42, estimatedTokenSpend: '$1.23' },
  mcp: [
    { name: 'github', status: 'connected', lastPing: '2026-03-10T10:00:00Z', toolCount: 12 },
    { name: 'slack', status: 'disconnected', lastPing: null, toolCount: 0 },
  ],
  skills: { totalLoaded: 5, lastReload: '2026-03-10T09:00:00Z', parseErrors: ['bad-skill.yaml: invalid syntax'] },
  memory: { entryCount: 150, databaseFileSize: '2.4 MB' },
  cron: [
    { name: 'daily-briefing', nextRun: '2026-03-11T08:00:00Z', lastRun: '2026-03-10T08:00:00Z', lastResult: 'success' },
  ],
  recentActivity: [
    { timestamp: '2026-03-10T10:05:00Z', messagePreview: 'Reviewed PR #42', toolCalls: ['Read', 'Grep'] },
    { timestamp: '2026-03-10T10:03:00Z', messagePreview: 'Checked build status', toolCalls: [] },
  ],
}

function mountPage() {
  return mount(SystemStatus, {
    global: {
      plugins: [createPinia()],
    },
  })
}

describe('SystemStatus.vue', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(fullStatus),
    }))
  })

  it('renders the page title', () => {
    const wrapper = mountPage()
    expect(wrapper.text()).toContain('System Status')
  })

  it('shows loading state initially', () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => new Promise(() => {}))) // never resolves
    const wrapper = mountPage()
    expect(wrapper.text()).toContain('Loading status')
  })

  it('renders all status cards after data loads', async () => {
    const wrapper = mountPage()
    await vi.dynamicImportSettled()
    await wrapper.vm.$nextTick()
    // Wait for fetchStatus to resolve
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('Bot')
    })

    expect(wrapper.text()).toContain('Model')
    expect(wrapper.text()).toContain('Skills')
    expect(wrapper.text()).toContain('Memory')
    expect(wrapper.text()).toContain('MCP Connections')
  })

  it('displays bot status details', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('Running')
    })
    expect(wrapper.text()).toContain('1234')
    expect(wrapper.text()).toContain('2h 15m')
  })

  it('displays model information', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('claude-sonnet-4-20250514')
    })
    expect(wrapper.text()).toContain('42')
    expect(wrapper.text()).toContain('$1.23')
  })

  it('displays MCP connections', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('github')
    })
    expect(wrapper.text()).toContain('connected')
    expect(wrapper.text()).toContain('slack')
    expect(wrapper.text()).toContain('disconnected')
    expect(wrapper.text()).toContain('12 tools')
  })

  it('displays skills with parse errors', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('bad-skill.yaml: invalid syntax')
    })
  })

  it('displays memory stats', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('150')
    })
    expect(wrapper.text()).toContain('2.4 MB')
  })

  it('displays cron jobs table', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('daily-briefing')
    })
    expect(wrapper.text()).toContain('success')
  })

  it('displays recent activity feed', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('Reviewed PR #42')
    })
    expect(wrapper.text()).toContain('Read')
    expect(wrapper.text()).toContain('Grep')
    expect(wrapper.text()).toContain('Checked build status')
  })

  it('shows empty state when no MCP connections', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ ...fullStatus, mcp: [] }),
    }))
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('No MCP servers configured')
    })
  })

  it('shows empty state when no recent activity', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ ...fullStatus, recentActivity: [] }),
    }))
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('No recent activity')
    })
  })

  it('shows disconnected indicator by default', () => {
    const wrapper = mountPage()
    expect(wrapper.text()).toContain('Disconnected')
  })

  it('calls connectSSE on mount and disconnects on unmount', async () => {
    const wrapper = mountPage()
    const store = useStatusStore()
    // connectSSE was called during mount
    expect(EventSource).toHaveBeenCalledWith('/api/status/stream')

    wrapper.unmount()
    // disconnectSSE was called
    expect(store.connected).toBe(false)
  })
})
