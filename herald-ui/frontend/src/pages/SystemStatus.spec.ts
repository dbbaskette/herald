import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import SystemStatus from './SystemStatus.vue'
import { useStatusStore } from '@/stores/status'
import type { SystemStatus as SystemStatusType } from '@/stores/status'

// Stub EventSource globally
vi.stubGlobal('EventSource', vi.fn(function () { return {
  onopen: null,
  onmessage: null,
  onerror: null,
  close: vi.fn(), addEventListener: vi.fn(),
} }))

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

function mockFetch(statusPromise: Promise<unknown> = Promise.resolve(fullStatus)) {
  vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
    if (String(url).includes('/api/approvals')) {
      return Promise.resolve({ ok: true, json: () => Promise.resolve([]) })
    }
    return statusPromise.then((body) => ({ ok: true, json: () => Promise.resolve(body) }))
  }))
}

describe('SystemStatus.vue', () => {
  beforeEach(() => {
    mockFetch()
  })

  it('renders the page title', () => {
    const wrapper = mountPage()
    expect(wrapper.text()).toContain('Status')
  })

  it('shows loading state initially', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      if (String(url).includes('/api/approvals')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve([]) })
      }
      return new Promise(() => {})
    }))
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('Loading status')
    })
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
    expect(wrapper.text()).not.toContain('Bot offline')
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
    expect(wrapper.text()).toContain('ok')
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

  it('does not claim bot offline before status loads', () => {
    const wrapper = mountPage()
    expect(wrapper.text()).not.toContain('Bot offline')
  })

  it('calls connectSSE on mount and disconnects on unmount', async () => {
    const eventSource = vi.fn(function () { return {
      onopen: null, onmessage: null, onerror: null, close: vi.fn(), addEventListener: vi.fn(),
    } })
    vi.stubGlobal('EventSource', eventSource)
    const wrapper = mountPage()
    const store = useStatusStore()
    // connectSSE was called during mount
    await vi.waitFor(() => expect(eventSource).toHaveBeenCalledWith('/api/status/stream'))

    wrapper.unmount()
    // disconnectSSE was called
    expect(store.connected).toBe(false)
  })
})

it('shows cron empty state and retained data with stale last-updated notice', async () => {
  mockFetch(Promise.resolve({ ...fullStatus, cron: [] }))
  const wrapper = mountPage()
  await vi.waitFor(() => expect(wrapper.text()).toContain('No cron jobs configured'))
  const store = useStatusStore()
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
  await store.fetchStatus()
  expect(wrapper.text()).toContain('claude-sonnet-4-20250514')
  expect(wrapper.text()).toContain('Showing stale status'); expect(wrapper.text()).toContain('Last updated'); expect(wrapper.text()).toContain('Retry')
  wrapper.unmount()
})
it('does not present fetch failure as stopped bot or empty configured capabilities', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
  const wrapper = mountPage()
  await vi.waitFor(() => expect(wrapper.text()).toContain('capability state are unknown'))
  expect(wrapper.text()).not.toContain('Bot offline'); expect(wrapper.text()).not.toContain('No MCP servers configured')
  wrapper.unmount()
})

it('distinguishes unknown telemetry, disabled capability and actual failure', async () => {
  mockFetch(Promise.resolve({ ...fullStatus, capabilities: {
    mcp: { state: 'unknown', message: 'Connection telemetry unavailable.' },
    memory: { state: 'failed', message: 'Database could not be read.' },
    skills: { state: 'disabled', message: 'Skills disabled in configuration.' },
    'google-workspace': { state: 'unavailable', message: 'gws CLI is not installed.', setupAction: 'docs/provider-capabilities.md' },
    'provider:openai': { state: 'healthy', message: 'Configured; remote availability has not been probed.' },
  } }))
  const wrapper = mountPage()
  await vi.waitFor(() => expect(wrapper.text()).toContain('Unknown. Connection telemetry unavailable.'))
  expect(wrapper.text()).toContain('Failed. Database could not be read.')
  expect(wrapper.text()).toContain('Disabled. Skills disabled in configuration.')
  expect(wrapper.text()).toContain('google workspace')
  expect(wrapper.text()).toContain('Unavailable')
  expect(wrapper.text()).toContain('openai provider')
  expect(wrapper.text()).toContain('Ready')
  expect(wrapper.text()).not.toContain('No MCP servers configured')
  expect(wrapper.text()).not.toContain('150')
  wrapper.unmount()
})
