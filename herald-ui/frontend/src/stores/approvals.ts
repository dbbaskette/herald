import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export interface PendingApproval {
  id: string
  kind: string
  conversationId: string | null
  channel: string
  toolName: string
  path: string | null
  diffPreview: string
  createdAt: string
  timeoutSeconds: number
}

export const useApprovalsStore = defineStore('approvals', () => {
  const items = ref<PendingApproval[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)
  let pollTimer: ReturnType<typeof setInterval> | null = null

  const count = computed(() => items.value.length)

  async function fetchAll(conversationId?: string) {
    loading.value = true
    error.value = null
    try {
      const url = new URL('/api/approvals', window.location.origin)
      if (conversationId) url.searchParams.set('conversationId', conversationId)
      const res = await fetch(url.toString())
      if (!res.ok) {
        const data = await res.json().catch(() => ({}))
        error.value = data.error || `HTTP ${res.status}`
        items.value = []
        return
      }
      items.value = await res.json()
    } catch (e: unknown) {
      error.value = e instanceof Error ? e.message : 'Failed to load approvals'
      items.value = []
    } finally {
      loading.value = false
    }
  }

  async function resolve(id: string, approved: boolean): Promise<boolean> {
    try {
      const res = await fetch(`/api/approvals/${encodeURIComponent(id)}/resolve`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ approved }),
      })
      if (!res.ok) return false
      items.value = items.value.filter((a) => a.id !== id)
      return true
    } catch {
      return false
    }
  }

  function upsertFromSse(raw: string) {
    try {
      const approval = JSON.parse(raw) as PendingApproval
      const idx = items.value.findIndex((a) => a.id === approval.id)
      if (idx >= 0) {
        items.value[idx] = approval
      } else {
        items.value.push(approval)
      }
    } catch { /* malformed */ }
  }

  function remove(id: string) {
    items.value = items.value.filter((a) => a.id !== id)
  }

  function startPolling(intervalMs = 5000) {
    stopPolling()
    fetchAll()
    pollTimer = setInterval(() => fetchAll(), intervalMs)
  }

  function stopPolling() {
    if (pollTimer) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  }

  return {
    items, loading, error, count,
    fetchAll, resolve, upsertFromSse, remove,
    startPolling, stopPolling,
  }
})
