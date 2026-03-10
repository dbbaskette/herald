import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

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

export interface SystemStatus {
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

export const useStatusStore = defineStore('status', () => {
  const status = ref<SystemStatus>({ ...defaultStatus })
  const loading = ref(false)
  const connected = ref(false)
  const healthy = computed(() => status.value.healthy)

  let eventSource: EventSource | null = null

  async function fetchStatus() {
    loading.value = true
    try {
      const res = await fetch('/api/status')
      if (!res.ok) throw new Error(res.statusText)
      const data = await res.json()
      applyStatusData(data)
    } catch {
      status.value = { ...defaultStatus }
    } finally {
      loading.value = false
    }
  }

  function applyStatusData(data: Partial<SystemStatus>) {
    status.value = {
      healthy: data.healthy ?? false,
      bot: data.bot ?? defaultStatus.bot,
      model: data.model ?? defaultStatus.model,
      mcp: data.mcp ?? [],
      skills: data.skills ?? defaultStatus.skills,
      memory: data.memory ?? defaultStatus.memory,
      cron: data.cron ?? [],
      recentActivity: data.recentActivity ?? [],
    }
  }

  function connectSSE() {
    disconnectSSE()
    eventSource = new EventSource('/api/status/stream')

    eventSource.onopen = () => {
      connected.value = true
    }

    eventSource.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data)
        applyStatusData(data)
      } catch {
        // ignore malformed messages
      }
    }

    eventSource.onerror = () => {
      connected.value = false
      eventSource?.close()
      // Reconnect after 5 seconds
      setTimeout(() => {
        if (!connected.value) {
          connectSSE()
        }
      }, 5000)
    }
  }

  function disconnectSSE() {
    if (eventSource) {
      eventSource.close()
      eventSource = null
      connected.value = false
    }
  }

  return { status, loading, healthy, connected, fetchStatus, connectSSE, disconnectSSE }
})
