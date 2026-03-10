import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface CronJob {
  id: string
  name: string
  expression: string
  enabled: boolean
  builtIn: boolean
  promptText: string
  status: string
  lastRun: string | null
  nextRun: string | null
  lastRunLog: string | null
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

  async function toggleJob(id: string, enabled: boolean): Promise<boolean> {
    try {
      const res = await fetch(`/api/cron-jobs/${encodeURIComponent(id)}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ enabled }),
      })
      if (!res.ok) throw new Error(res.statusText)
      const updated: CronJob = await res.json()
      const idx = jobs.value.findIndex(j => j.id === id)
      if (idx >= 0) jobs.value[idx] = updated
      return true
    } catch {
      return false
    }
  }

  async function saveJob(job: Partial<CronJob> & { name: string; expression: string; promptText: string }): Promise<boolean> {
    try {
      const isNew = !job.id
      const url = isNew ? '/api/cron-jobs' : `/api/cron-jobs/${encodeURIComponent(job.id!)}`
      const res = await fetch(url, {
        method: isNew ? 'POST' : 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(job),
      })
      if (!res.ok) throw new Error(res.statusText)
      const saved: CronJob = await res.json()
      if (isNew) {
        jobs.value.push(saved)
      } else {
        const idx = jobs.value.findIndex(j => j.id === saved.id)
        if (idx >= 0) jobs.value[idx] = saved
      }
      return true
    } catch {
      return false
    }
  }

  async function deleteJob(id: string): Promise<boolean> {
    try {
      const res = await fetch(`/api/cron-jobs/${encodeURIComponent(id)}`, {
        method: 'DELETE',
      })
      if (!res.ok) throw new Error(res.statusText)
      jobs.value = jobs.value.filter(j => j.id !== id)
      return true
    } catch {
      return false
    }
  }

  async function runJob(id: string): Promise<boolean> {
    try {
      const res = await fetch(`/api/cron/${encodeURIComponent(id)}/run`, {
        method: 'POST',
      })
      if (!res.ok) throw new Error(res.statusText)
      await fetchJobs()
      return true
    } catch {
      return false
    }
  }

  return { jobs, loading, fetchJobs, toggleJob, saveJob, deleteJob, runJob }
})
