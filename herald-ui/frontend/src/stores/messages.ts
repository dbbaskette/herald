import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export interface ToolCall {
  name: string
  inputs: Record<string, unknown>
  outputs: unknown
}

export interface SubagentCall {
  name: string
  toolCalls: ToolCall[]
  result: string
}

export interface Message {
  id: string
  role: 'user' | 'assistant' | 'system'
  content: string
  timestamp: string
  toolCalls?: ToolCall[]
  subagentCalls?: SubagentCall[]
}

export interface MessagesPage {
  content: Message[]
  totalPages: number
  totalElements: number
  number: number
}

export const useMessagesStore = defineStore('messages', () => {
  const messages = ref<Message[]>([])
  const loading = ref(false)
  const currentPage = ref(0)
  const totalPages = ref(0)
  const totalElements = ref(0)
  const pageSize = 50
  const search = ref('')
  const startDate = ref('')
  const endDate = ref('')

  const hasNextPage = computed(() => currentPage.value < totalPages.value - 1)
  const hasPrevPage = computed(() => currentPage.value > 0)

  async function fetchMessages(page = 0) {
    loading.value = true
    try {
      const params = new URLSearchParams()
      params.set('page', String(page))
      params.set('size', String(pageSize))
      if (search.value) params.set('search', search.value)
      if (startDate.value) params.set('startDate', startDate.value)
      if (endDate.value) params.set('endDate', endDate.value)

      const res = await fetch(`/api/messages?${params}`)
      if (!res.ok) throw new Error(res.statusText)
      const data: MessagesPage = await res.json()
      messages.value = data.content
      currentPage.value = data.number
      totalPages.value = data.totalPages
      totalElements.value = data.totalElements
    } catch {
      messages.value = []
      totalPages.value = 0
      totalElements.value = 0
    } finally {
      loading.value = false
    }
  }

  async function clearHistory(): Promise<boolean> {
    try {
      const res = await fetch('/api/messages', { method: 'DELETE' })
      if (!res.ok) throw new Error(res.statusText)
      messages.value = []
      currentPage.value = 0
      totalPages.value = 0
      totalElements.value = 0
      return true
    } catch {
      return false
    }
  }

  function nextPage() {
    if (hasNextPage.value) {
      fetchMessages(currentPage.value + 1)
    }
  }

  function prevPage() {
    if (hasPrevPage.value) {
      fetchMessages(currentPage.value - 1)
    }
  }

  function applyFilters() {
    fetchMessages(0)
  }

  function clearFilters() {
    search.value = ''
    startDate.value = ''
    endDate.value = ''
    fetchMessages(0)
  }

  return {
    messages, loading, currentPage, totalPages, totalElements, pageSize,
    search, startDate, endDate,
    hasNextPage, hasPrevPage,
    fetchMessages, clearHistory, nextPage, prevPage, applyFilters, clearFilters,
  }
})
