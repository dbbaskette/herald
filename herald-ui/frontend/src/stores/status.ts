import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useStatusStore = defineStore('status', () => {
  const healthy = ref(false)
  const loading = ref(false)

  async function fetchStatus() {
    loading.value = true
    try {
      const res = await fetch('/api/status')
      if (!res.ok) throw new Error(res.statusText)
      const data = await res.json()
      healthy.value = data.healthy ?? false
    } catch {
      healthy.value = false
    } finally {
      loading.value = false
    }
  }

  return { healthy, loading, fetchStatus }
})
