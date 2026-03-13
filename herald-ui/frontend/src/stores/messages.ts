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

function parseToolCalls(raw: unknown): ToolCall[] {
  if (!raw) return []
  try {
    const parsed = typeof raw === 'string' ? JSON.parse(raw) : raw
    if (!Array.isArray(parsed)) return []
    return parsed.map((tc: Record<string, unknown>) => ({
      name: (tc.name ?? '') as string,
      inputs: (tc.inputs ?? tc.arguments ?? {}) as Record<string, unknown>,
      outputs: tc.outputs ?? tc.result ?? '',
    }))
  } catch {
    return []
  }
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
      const data = await res.json()
      messages.value = (data.content ?? []).map((row: Record<string, unknown>) => ({
        id: String(row.id ?? row.ID ?? ''),
        role: (row.role ?? row.ROLE ?? 'system') as Message['role'],
        content: (row.content ?? row.CONTENT ?? '') as string,
        timestamp: (row.created_at ?? row.CREATED_AT ?? null) as string,
        toolCalls: parseToolCalls(row.tool_calls ?? row.TOOL_CALLS),
        subagentCalls: [],
      }))
      currentPage.value = data.number ?? 0
      totalPages.value = data.totalPages ?? 0
      totalElements.value = data.totalElements ?? 0
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
