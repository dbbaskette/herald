import { defineStore } from 'pinia'
import { ref, computed, onScopeDispose } from 'vue'
import { createSseConnection, type ConnectionState } from '@/lib/sse'

export interface BotStatus {
  running: boolean
  pid: number | null
  uptime: string
  restartCount: number
}

export interface ModelStatus {
  name: string
  requestsToday: number
  estimatedTokenSpend: string
}

export interface McpConnection {
  name: string
  status: 'connected' | 'disconnected' | 'error'
  lastPing: string | null
  toolCount: number
}

export interface SkillsStatus {
  totalLoaded: number
  lastReload: string | null
  parseErrors: string[]
}

export interface MemoryStatus {
  entryCount: number
  databaseFileSize: string
}

export interface CronJobStatus {
  name: string
  nextRun: string | null
  lastRun: string | null
  lastResult: string | null
}

export interface ActivityEntry {
  timestamp: string
  messagePreview: string
  toolCalls: string[]
}

export interface CapabilityStatus {
  state: 'available' | 'disabled' | 'failed' | 'unknown'
  message: string
}
export interface SystemStatus {
  capabilities?: Partial<Record<'mcp' | 'memory' | 'skills' | 'cron', CapabilityStatus>>
  healthy: boolean
  bot: BotStatus
  model: ModelStatus
  mcp: McpConnection[]
  skills: SkillsStatus
  memory: MemoryStatus
  cron: CronJobStatus[]
  recentActivity: ActivityEntry[]
}

const defaultStatus: SystemStatus = {
  healthy: false,
  bot: { running: false, pid: null, uptime: '—', restartCount: 0 },
  model: { name: '—', requestsToday: 0, estimatedTokenSpend: '—' },
  mcp: [],
  skills: { totalLoaded: 0, lastReload: null, parseErrors: [] },
  memory: { entryCount: 0, databaseFileSize: '—' },
  cron: [],
  recentActivity: [],
}

// Validate complete snapshots before replacing any last-good data. Extra backend fields
// are discarded, and nested objects are reconstructed to avoid sharing input references.
export function parseStatus(value: unknown): SystemStatus {
  const object = (v: unknown): Record<string, any> => {
    if (!v || typeof v !== 'object' || Array.isArray(v)) throw new Error('Invalid status object')
    return v as Record<string, any>
  }
  const str = (v: unknown): string => { if (typeof v !== 'string' || v.length > 20000) throw new Error('Invalid status text'); return v }
  const nullable = (v: unknown) => v === null ? null : str(v)
  const num = (v: unknown): number => { if (typeof v !== 'number' || !Number.isSafeInteger(v) || v < 0) throw new Error('Invalid status count'); return v }
  const bool = (v: unknown): boolean => { if (typeof v !== 'boolean') throw new Error('Invalid status flag'); return v }
  const array = <T>(v: unknown, parse: (item: any) => T): T[] => { if (!Array.isArray(v) || v.length > 10000) throw new Error('Invalid status list'); return v.map(parse) }
  const d = object(value), b = object(d.bot), m = object(d.model), s = object(d.skills), memory = object(d.memory)
  const capabilities: SystemStatus['capabilities'] = {}
  if (d.capabilities !== undefined) {
    const raw = object(d.capabilities)
    for (const key of ['mcp', 'memory', 'skills', 'cron'] as const) {
      if (raw[key] === undefined) continue
      const v = object(raw[key])
      if (!['available', 'disabled', 'failed', 'unknown'].includes(v.state)) throw new Error('Invalid capability state')
      capabilities[key] = { state: v.state, message: str(v.message) }
    }
  }
  return {
    capabilities,
    healthy: bool(d.healthy),
    bot: { running: bool(b.running), pid: b.pid === null ? null : num(b.pid), uptime: str(b.uptime), restartCount: num(b.restartCount) },
    model: { name: str(m.name), requestsToday: num(m.requestsToday), estimatedTokenSpend: str(m.estimatedTokenSpend) },
    skills: { totalLoaded: num(s.totalLoaded), lastReload: nullable(s.lastReload), parseErrors: array(s.parseErrors, str) },
    memory: { entryCount: num(memory.entryCount), databaseFileSize: str(memory.databaseFileSize) },
    mcp: array(d.mcp, item => {
      const v = object(item)
      if (!['connected', 'disconnected', 'error'].includes(v.status)) throw new Error('Invalid MCP status')
      return { name: str(v.name), status: v.status, lastPing: nullable(v.lastPing), toolCount: num(v.toolCount) }
    }),
    cron: array(d.cron, item => { const v = object(item); return { name: str(v.name), nextRun: nullable(v.nextRun), lastRun: nullable(v.lastRun), lastResult: nullable(v.lastResult) } }),
    recentActivity: array(d.recentActivity, item => { const v = object(item); return { timestamp: str(v.timestamp), messagePreview: str(v.messagePreview), toolCalls: array(v.toolCalls, str) } }).slice(0, 20),
  }
}

export function activityKey(entry: ActivityEntry) {
  return JSON.stringify([entry.timestamp, entry.messagePreview, entry.toolCalls])
}

export const useStatusStore = defineStore('status', () => {
  const status = ref<SystemStatus>(structuredClone(defaultStatus))
  const loading = ref(false)
  const connectionState = ref<ConnectionState>('stopped')
  const lastUpdated = ref<string | null>(null)
  const error = ref<string | null>(null)
  const streamFresh = ref(false)
  const connected = computed(() => connectionState.value === 'live')
  const hasData = computed(() => lastUpdated.value !== null)
  const stale = computed(() => hasData.value && (!connected.value || !streamFresh.value || error.value !== null))
  const healthy = computed(() => status.value.healthy)
  const connectionLabel = computed(() => {
    if (!hasData.value && loading.value) return 'Loading status…'
    if (connectionState.value === 'reconnecting' || connectionState.value === 'connecting') return 'Reconnecting…'
    if (!connected.value) return hasData.value ? 'Offline · stale status' : 'Status unavailable'
    return error.value || (hasData.value && !streamFresh.value) ? 'Stale status' : hasData.value ? 'Live' : 'Waiting for status…'
  })
  let consumers = 0
  let revision = 0
  let request = 0
  let controller: AbortController | null = null
  const stream = createSseConnection('/api/status/stream', {
    onState: state => { connectionState.value = state; if (state !== 'live') streamFresh.value = false },
    onMessage: readSnapshot,
    events: { status: readSnapshot },
  })

  function readSnapshot(event: MessageEvent) {
    try { applyStatusData(JSON.parse(event.data)); streamFresh.value = true; stream.acknowledge() }
    catch { error.value = 'Received invalid status data. Showing last known status.' }
  }

  function applyStatusData(data: unknown) {
    const parsed = parseStatus(data)
    // Duplicate entries have identical stable identities; render each only once.
    parsed.recentActivity = parsed.recentActivity.filter((entry, i, entries) => entries.findIndex(other => activityKey(other) === activityKey(entry)) === i)
    status.value = parsed
    lastUpdated.value = new Date().toISOString()
    error.value = null
    revision++
  }
  async function fetchStatus() {
    controller?.abort()
    controller = new AbortController()
    const id = ++request, before = revision
    loading.value = true
    try {
      const res = await fetch('/api/status', { signal: controller.signal })
      if (!res.ok) throw new Error('Status request failed')
      const data = await res.json()
      if (id === request && before === revision) applyStatusData(data)
    } catch {
      if (id === request && before === revision) error.value = 'Unable to fetch status. Retry to reconnect.'
    } finally {
      if (id === request) loading.value = false
    }
  }
  function connectSSE() { consumers++; if (consumers === 1) stream.start() }
  function disconnectSSE() {
    consumers = Math.max(0, consumers - 1)
    if (!consumers) {
      stream.stop()
      controller?.abort()
      request++
      loading.value = false
    }
  }
  function retry() { stream.retry(); void fetchStatus() }
  onScopeDispose(() => { consumers = 1; disconnectSSE() })
  return { status, loading, healthy, connected, connectionState, connectionLabel, lastUpdated, error, hasData, stale, fetchStatus, connectSSE, disconnectSSE, retry }
})
