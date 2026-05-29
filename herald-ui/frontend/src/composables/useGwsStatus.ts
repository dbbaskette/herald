import { ref } from 'vue'

export interface GwsStatus {
  installed: boolean
  authenticated: boolean
  tokenValid?: boolean
  user?: string
  projectId?: string
  scopes?: string[]
}

// Module-level singleton: every consumer (StatusHeader, SetupChecklist, …)
// shares ONE fetch + reactive value, so they can't disagree. The `gws auth
// status` subprocess is occasionally slow right after a bot restart, so we
// fire a couple of quick retries to ride out the reconnect window, then poll.
const status = ref<GwsStatus | null>(null)
let started = false
let pollTimer: ReturnType<typeof setInterval> | null = null

async function refresh(): Promise<void> {
  try {
    const res = await fetch('/api/gws/status')
    if (res.ok) status.value = await res.json()
  } catch {
    // keep last-known value on transient failure
  }
}

export function useGwsStatus() {
  // Idempotent — only the first caller starts the timers; the rest just read.
  if (!started) {
    started = true
    refresh()
    setTimeout(refresh, 3_000)   // ride out the post-restart "Reconnecting" window
    setTimeout(refresh, 8_000)
    pollTimer = setInterval(refresh, 30_000)
  }
  return { status, refresh }
}

// Exposed for tests / teardown; not normally needed (singleton lives for the app).
export function _stopGwsStatusPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  started = false
}
