import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface CronJob {
  id: string
  name: string
  expression: string
  enabled: boolean
}

export const useCronStore = defineStore('cron', () => {
  const jobs = ref<CronJob[]>([])
  const loading = ref(false)

  async function fetchJobs() {
    loading.value = true
    try {
      const res = await fetch('/api/cron-jobs')
      if (!res.ok) throw new Error(res.statusText)
      jobs.value = await res.json()
    } catch {
      jobs.value = []
    } finally {
      loading.value = false
    }
  }

  return { jobs, loading, fetchJobs }
})
