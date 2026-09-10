import { ref, onUnmounted } from 'vue'
import { createSseConnection } from '@/lib/sse'

export type SkillReloadStatus = 'loaded' | 'reloading' | 'error'

export function useSkillReloadSse() {
  const status = ref<SkillReloadStatus>('loaded')
  const lastLoadedAt = ref<string | null>(null)
  let awaitingReload = false
  let reloadingTimer: ReturnType<typeof setTimeout> | null = null
  function clearReloadingTimer() {
    if (reloadingTimer !== null) clearTimeout(reloadingTimer)
    reloadingTimer = null
  }
  const stream = createSseConnection('/api/status/stream', {
    onState: state => { if (state === 'offline' || state === 'reconnecting') status.value = 'error' },
    events: {
      'skill-reload': event => {
        try {
          const data = JSON.parse(event.data)
          if (typeof data?.timestamp !== 'string' || !Number.isFinite(Date.parse(data.timestamp))) return
          lastLoadedAt.value = data.timestamp
          awaitingReload = false
          clearReloadingTimer()
          status.value = 'loaded'
          stream.acknowledge()
        } catch { /* Preserve last known reload state. */ }
      },
      status: event => {
        try {
          const data = JSON.parse(event.data)
          if (typeof data?.timestamp !== 'string' || !Number.isFinite(Date.parse(data.timestamp))) return
          stream.acknowledge()
          if (status.value === 'error' && !awaitingReload) status.value = 'loaded'
        } catch { /* Ignore malformed heartbeat. */ }
      },
    },
  })
  function setReloading() {
    awaitingReload = true
    status.value = 'reloading'
    clearReloadingTimer()
    reloadingTimer = setTimeout(() => {
      // A timeout is not confirmation that a requested reload succeeded.
      status.value = 'error'
      reloadingTimer = null
    }, 10000)
  }
  stream.start()
  onUnmounted(() => { stream.stop(); clearReloadingTimer() })
  return { status, lastLoadedAt, setReloading, retry: stream.retry }
}
