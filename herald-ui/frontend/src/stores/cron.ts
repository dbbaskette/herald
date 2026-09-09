import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface CronJob {
  id: string
  name: string
  expression: string
  enabled: boolean
  builtIn: boolean
  promptText: string
  timezone?: string
  scheduleStatus?: string
  status: string
  lastRun: string | null
  nextRun: string | null
  lastRunLog: string | null
}

export const useCronStore = defineStore('cron', () => {
  const jobs = ref<CronJob[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)
  let version = 0
  let mutations = 0

  async function checked(res: Response) {
    if (res.ok) return res
    let message = `Request failed (${res.status}). Please retry.`
    try {
      const detail = await res.json()
      message = detail.message || detail.detail || message
    } catch { /* A proxy error may have no JSON body. */ }
    throw new Error(message)
  }
  function fail(cause: unknown) {
    error.value = cause instanceof Error ? cause.message : 'Unable to reach Herald. Please retry.'
  }
  async function fetchJobs(quiet = false) {
    if (mutations) return
    const request = ++version
    if (!quiet) loading.value = true
    try {
      const res = await checked(await fetch('/api/cron'))
      const data = await res.json()
      if (!Array.isArray(data) || data.some(j => typeof j.id !== 'string' || typeof j.expression !== 'string'))
        throw new Error('Herald returned an invalid cron response. Please retry.')
      if (request === version) { jobs.value = data; error.value = null }
    } catch (cause) {
      if (request === version) fail(cause)
    } finally {
      if (request === version) loading.value = false
    }
  }
  async function saveJob(job: Partial<CronJob> & { name: string; expression: string; promptText: string }): Promise<boolean> {
    return mutate(job.id, job.id ? 'PUT' : 'POST', {
      name: job.name, expression: job.expression, promptText: job.promptText, enabled: job.enabled,
    })
  }
  async function mutate(id: string | undefined, method: string, body?: object): Promise<boolean> {
    ++mutations
    ++version // In-flight list responses must not overwrite a mutation.
    try {
      const res = await checked(await fetch(`/api/cron${id ? '/' + encodeURIComponent(id) : ''}`, {
        method, headers: { 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined,
      }))
      if (method === 'DELETE') jobs.value = jobs.value.filter(j => j.id !== id)
      else {
        const saved: CronJob = await res.json()
        const index = jobs.value.findIndex(j => j.id === saved.id)
        if (index < 0) jobs.value.push(saved)
        else jobs.value[index] = saved
      }
      error.value = null
      return true
    } catch (cause) { fail(cause); return false }
    finally { --mutations; ++version; loading.value = false }
  }
  async function toggleJob(id: string, enabled: boolean) { return mutate(id, 'PATCH', { enabled }) }
  async function deleteJob(id: string) { return mutate(id, 'DELETE') }
  async function runJob(id: string): Promise<boolean> {
    ++mutations
    ++version
    try {
      await checked(await fetch(`/api/cron/${encodeURIComponent(id)}/run`, { method: 'POST' }))
      const job = jobs.value.find(j => j.id === id)
      if (job) job.status = 'queued'
      error.value = null
      return true
    } catch (cause) { fail(cause); return false }
    finally { --mutations; ++version; loading.value = false }
  }
  return { jobs, loading, error, fetchJobs, toggleJob, saveJob, deleteJob, runJob }
})
