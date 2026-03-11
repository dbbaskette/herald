import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface ObsidianSearchResult {
  path: string
  name: string
  folder: string
  snippets: string[]
}

export interface ObsidianNote {
  path: string
  content: string
}

export const useObsidianStore = defineStore('obsidian', () => {
  const results = ref<ObsidianSearchResult[]>([])
  const folders = ref<string[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)
  const selectedNote = ref<ObsidianNote | null>(null)
  const noteLoading = ref(false)

  async function fetchFolders() {
    try {
      const res = await fetch('/api/obsidian/folders')
      if (!res.ok) throw new Error(res.statusText)
      folders.value = await res.json()
    } catch {
      folders.value = []
    }
  }

  async function search(query: string, folder?: string) {
    if (!query.trim()) {
      results.value = []
      return
    }
    loading.value = true
    error.value = null
    selectedNote.value = null
    try {
      const params = new URLSearchParams({ query })
      if (folder) params.set('folder', folder)
      const res = await fetch(`/api/obsidian/search?${params}`)
      if (!res.ok) throw new Error(res.statusText)
      results.value = await res.json()
    } catch (e) {
      error.value = 'Search failed — is Obsidian running?'
      results.value = []
    } finally {
      loading.value = false
    }
  }

  async function readNote(path: string) {
    noteLoading.value = true
    try {
      const res = await fetch(`/api/obsidian/read?${new URLSearchParams({ path })}`)
      if (!res.ok) throw new Error(res.statusText)
      selectedNote.value = await res.json()
    } catch {
      selectedNote.value = null
    } finally {
      noteLoading.value = false
    }
  }

  function clearNote() {
    selectedNote.value = null
  }

  return { results, folders, loading, error, selectedNote, noteLoading, fetchFolders, search, readNote, clearNote }
})
