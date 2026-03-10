import { ref, onUnmounted } from 'vue'

export type SkillReloadStatus = 'loaded' | 'reloading' | 'error'

export function useSkillReloadSse() {
  const status = ref<SkillReloadStatus>('loaded')
  const lastLoadedAt = ref<string | null>(null)

  let eventSource: EventSource | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let errorTimer: ReturnType<typeof setTimeout> | null = null
  let reloadingTimer: ReturnType<typeof setTimeout> | null = null

  function connect() {
    cleanup()

    eventSource = new EventSource('/api/status/stream')

    eventSource.onopen = () => {
      clearErrorTimer()
      if (status.value === 'error') {
        status.value = 'loaded'
      }
    }

    eventSource.addEventListener('skill-reload', (e: MessageEvent) => {
      try {
        const data = JSON.parse(e.data)
        lastLoadedAt.value = data.timestamp ?? new Date().toISOString()
      } catch {
        lastLoadedAt.value = new Date().toISOString()
      }
      clearReloadingTimer()
      status.value = 'loaded'
    })

    eventSource.onerror = () => {
      eventSource?.close()
      eventSource = null

      if (!errorTimer) {
        errorTimer = setTimeout(() => {
          status.value = 'error'
          errorTimer = null
        }, 10_000)
      }

      reconnectTimer = setTimeout(() => {
        reconnectTimer = null
        connect()
      }, 5_000)
    }
  }

  function setReloading() {
    status.value = 'reloading'
    clearReloadingTimer()
    reloadingTimer = setTimeout(() => {
      if (status.value === 'reloading') {
        status.value = 'loaded'
      }
      reloadingTimer = null
    }, 2_000)
  }

  function clearErrorTimer() {
    if (errorTimer) {
      clearTimeout(errorTimer)
      errorTimer = null
    }
  }

  function clearReloadingTimer() {
    if (reloadingTimer) {
      clearTimeout(reloadingTimer)
      reloadingTimer = null
    }
  }

  function cleanup() {
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    clearErrorTimer()
    clearReloadingTimer()
  }

  connect()

  onUnmounted(() => {
    cleanup()
  })

  return { status, lastLoadedAt, setReloading }
}
