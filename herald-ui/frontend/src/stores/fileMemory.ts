import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface FileMemoryPage {
  path: string
  name: string | null
  description: string | null
  type: string
  size: number
  lastModified: string
}

export type FileMemoryGrouped = Record<string, FileMemoryPage[]>

export interface FileMemoryContent {
  path: string
  content: string
  size: number
}

export const TYPE_ORDER: string[] = [
  'user',
  'feedback',
  'project',
  'reference',
  'concept',
  'entity',
  'source',
  'unknown',
]

export const TYPE_LABEL: Record<string, string> = {
  user: 'User',
  feedback: 'Feedback',
  project: 'Projects',
  reference: 'References',
  concept: 'Concepts',
  entity: 'Entities',
  source: 'Sources',
  unknown: 'Untyped',
}

export const useFileMemoryStore = defineStore('fileMemory', () => {
  const grouped = ref<FileMemoryGrouped>({})
  const loading = ref(false)
  const error = ref<string | null>(null)
  const selected = ref<FileMemoryContent | null>(null)
  const selectedLoading = ref(false)

  async function fetchPages() {
    loading.value = true
    error.value = null
    try {
      const res = await fetch('/api/memory/files')
      if (!res.ok) throw new Error(res.statusText)
      grouped.value = (await res.json()) as FileMemoryGrouped
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load memory files'
      grouped.value = {}
    } finally {
      loading.value = false
    }
  }

  async function openPage(path: string) {
    selectedLoading.value = true
    error.value = null
    try {
      const res = await fetch(`/api/memory/files/content?path=${encodeURIComponent(path)}`)
      if (!res.ok) throw new Error(res.statusText)
      selected.value = (await res.json()) as FileMemoryContent
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load memory file'
      selected.value = null
    } finally {
      selectedLoading.value = false
    }
  }

  function clearSelected() {
    selected.value = null
  }

  return {
    grouped,
    loading,
    error,
    selected,
    selectedLoading,
    fetchPages,
    openPage,
    clearSelected,
  }
})
