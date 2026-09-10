import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export interface FileMemoryPage {
  path: string
  name: string | null
  description: string | null
  type: string
  size: number
  lastModified: string
  tags?: string[]
  conversationId?: string | null
  createdAt?: string | null
  oversized?: boolean
}

export type FileMemoryGrouped = Record<string, FileMemoryPage[]>

export interface FileMemoryContent {
  path: string
  content: string
  version?: string
  size: number
}

export const TYPE_ORDER: string[] = [
  'index',
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
  index: 'Memory index',
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

  const draft = ref('')
  const dirty = computed(() => selected.value !== null && draft.value !== selected.value.content)
  const saving = ref(false)
  const trash = ref<{token: string; trashPath: string; originalPath: string} | null>(null)
  const query = ref('')
  const tag = ref('')
  let listGeneration = 0
  let selectionGeneration = 0

  async function responseError(res: Response) {
    let message = res.status === 409 ? 'Memory changed on disk. Your draft is preserved; reload and reconcile it.' : res.status === 413 ? 'Memory exceeds 256 KiB. No partial text was loaded.' : res.statusText || 'Memory request failed'
    try { const body = await res.json(); if (typeof body.message === 'string') message = body.message } catch { /* empty body */ }
    return new Error(message)
  }
  async function fetchPages() {
    const generation = ++listGeneration
    loading.value = true
    error.value = null
    try {
      const params = new URLSearchParams({ query: query.value, tag: tag.value })
      const res = await fetch(`/api/memory/files?${params}`)
      if (!res.ok) throw await responseError(res)
      const value = await res.json()
      if (generation === listGeneration) grouped.value = value
    } catch (e) {
      if (generation === listGeneration) error.value = e instanceof Error ? e.message : 'Failed to load memory files'
    } finally { if (generation === listGeneration) loading.value = false }
  }

  async function openPage(path: string) {
    if (dirty.value || saving.value) { error.value = 'Save or discard your draft before opening another memory.'; return }
    const generation = ++selectionGeneration
    selectedLoading.value = true
    error.value = null
    try {
      const res = await fetch(`/api/memory/files/content?path=${encodeURIComponent(path)}`)
      if (!res.ok) throw await responseError(res)
      const value = await res.json() as FileMemoryContent
      if (generation === selectionGeneration && !dirty.value) { selected.value = value; draft.value = value.content }
    } catch (e) {
      if (generation === selectionGeneration) error.value = e instanceof Error ? e.message : 'Failed to load memory file'
    } finally { if (generation === selectionGeneration) selectedLoading.value = false }
  }

  function clearSelected() {
    if (dirty.value || saving.value) { error.value = 'Save or discard your draft before leaving.'; return }
    ++selectionGeneration
    selectedLoading.value = false
    selected.value = null
  }
  function discardDraft() { draft.value = selected.value?.content ?? ''; error.value = null }
  async function save() {
    if (!selected.value || !selected.value.version || saving.value) return false
    saving.value = true; error.value = null
    const submitted = draft.value
    try {
      const res = await fetch('/api/memory/files/content', { method: 'PUT', headers: {'Content-Type':'application/json'}, body: JSON.stringify({path: selected.value.path, content: submitted, version: selected.value.version}) })
      if (!res.ok) throw await responseError(res)
      selected.value = await res.json()
      await fetchPages()
      return true
    } catch (e) { error.value = e instanceof Error ? e.message : 'Save failed'; return false }
    finally { saving.value = false }
  }
  async function deleteSelected() {
    if (!selected.value?.version || saving.value || dirty.value) return false
    saving.value = true; error.value = null
    try {
      const res = await fetch(`/api/memory/files/content?${new URLSearchParams({path: selected.value.path, version: selected.value.version})}`, {method:'DELETE'})
      if (!res.ok) throw await responseError(res)
      trash.value = await res.json(); ++selectionGeneration; selected.value = null; draft.value = ''
      await fetchPages(); return true
    } catch (e) { error.value = e instanceof Error ? e.message : 'Delete failed'; return false }
    finally { saving.value = false }
  }
  async function restore() {
    if (!trash.value || saving.value) return false
    saving.value = true; error.value = null
    try {
      const res = await fetch('/api/memory/files/restore', {method:'POST', headers:{'Content-Type':'application/json'},body:JSON.stringify({token:trash.value.token})})
      if (!res.ok) throw await responseError(res)
      trash.value = null; await fetchPages(); return true
    } catch (e) { error.value = e instanceof Error ? e.message : 'Restore failed'; return false }
    finally { saving.value = false }
  }
  return { grouped, loading, error, selected, selectedLoading, fetchPages, openPage, clearSelected,
    draft, dirty, saving, trash, query, tag, discardDraft, save, deleteSelected, restore }
})
