import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export interface MemoryEntry {
  key: string
  value: string
  lastUpdated: string
}

export const useMemoryStore = defineStore('memory', () => {
  const entries = ref<MemoryEntry[]>([])
  const loading = ref(false)
  const filter = ref('')
  const error = ref<string | null>(null)

  const filteredEntries = computed(() => {
    if (!filter.value) return entries.value
    const q = filter.value.toLowerCase()
    return entries.value.filter(e => e.key.toLowerCase().includes(q))
  })

  async function fetchEntries(): Promise<boolean> {
    loading.value = true
    error.value = null
    try {
      const res = await fetch('/api/memory')
      if (!res.ok) throw new Error(res.statusText)
      entries.value = await res.json()
      return true
    } catch {
      error.value = 'Unable to load legacy entries. Retry before exporting a backup.'
      return false
    } finally {
      loading.value = false
    }
  }

  async function updateEntry(key: string, value: string): Promise<boolean> {
    error.value = null
    try {
      const res = await fetch(`/api/memory/${encodeURIComponent(key)}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ value }),
      })
      if (!res.ok) throw new Error(res.statusText)
      const updated: MemoryEntry = await res.json()
      const idx = entries.value.findIndex(e => e.key === key)
      if (idx >= 0) {
        entries.value[idx] = updated
      } else {
        entries.value.unshift(updated)
      }
      return true
    } catch {
      error.value = 'Legacy entry was not saved. Your edit is preserved; retry when the service is available.'
      return false
    }
  }

  async function addEntry(key: string, value: string): Promise<boolean> {
    return updateEntry(key, value)
  }

  async function deleteEntry(key: string): Promise<boolean> {
    try {
      const res = await fetch(`/api/memory/${encodeURIComponent(key)}`, {
        method: 'DELETE',
      })
      if (!res.ok) throw new Error(res.statusText)
      entries.value = entries.value.filter(e => e.key !== key)
      return true
    } catch {
      return false
    }
  }

  /** Compatibility backup of legacy SQLite keys/values only, never learned memory files. */
  async function exportJson(): Promise<boolean> {
    if (loading.value || !(await fetchEntries())) return false
    try {
      const data = Object.fromEntries(entries.value.map(entry => [entry.key, entry.value]))
      const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      try {
        const a = document.createElement('a')
        a.href = url
        a.download = 'herald-legacy-memory-backup.json'
        a.click()
      } finally { URL.revokeObjectURL(url) }
      return true
    } catch {
      error.value = 'Unable to export the legacy backup. No entries were changed.'
      return false
    }
  }

  async function importJson(file: File): Promise<{ imported: number; errors: string[] }> {
    const errors: string[] = []
    let imported = 0
    try {
      const text = await file.text()
      const data = JSON.parse(text)
      if (typeof data !== 'object' || data === null || Array.isArray(data)) {
        return { imported: 0, errors: ['File must contain a JSON object with key-value pairs'] }
      }
      for (const [key, value] of Object.entries(data)) {
        if (typeof value !== 'string') {
          errors.push(`Skipped "${key}": value is not a string`)
          continue
        }
        const ok = await updateEntry(key, value)
        if (ok) {
          imported++
        } else {
          errors.push(`Failed to import "${key}"`)
        }
      }
    } catch {
      errors.push('Failed to parse JSON file')
    }
    return { imported, errors }
  }

  return { entries, loading, error, filter, filteredEntries, fetchEntries, updateEntry, addEntry, deleteEntry, exportJson, importJson }
})
