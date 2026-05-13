import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface ConversationSummary {
  id: string
  title: string
  turnCount: number
  lastTurnAt: string
  firstTurnAt: string
}

export const useConversationsStore = defineStore('conversations', () => {
  const items = ref<ConversationSummary[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function fetchAll() {
    loading.value = true
    error.value = null
    try {
      const res = await fetch('/api/conversations')
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      items.value = await res.json()
    } catch (e: any) {
      error.value = e.message
      items.value = []
    } finally {
      loading.value = false
    }
  }

  async function deleteConversation(id: string): Promise<boolean> {
    try {
      const res = await fetch(`/api/conversations/${encodeURIComponent(id)}`, { method: 'DELETE' })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      items.value = items.value.filter(c => c.id !== id)
      return true
    } catch (e: any) {
      error.value = e.message
      return false
    }
  }

  return { items, loading, error, fetchAll, deleteConversation }
})
