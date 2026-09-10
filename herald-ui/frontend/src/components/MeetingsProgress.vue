<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
type Job = { meetingId: string; title: string; state: string; attempts: number; error: string | null }
const props = defineProps<{ meetingIds?: string[] }>()
const jobs = ref<Job[]>([])
const visibleJobs = computed(() => props.meetingIds ? jobs.value.filter(job => props.meetingIds!.includes(job.meetingId)) : jobs.value)
const error = ref('')
const retrying = ref('')
let timer: ReturnType<typeof setInterval> | undefined
let active = true
let loading = false
const counts = computed(() => Object.fromEntries(['pending', 'running', 'succeeded', 'failed'].map(state =>
  [state, visibleJobs.value.filter(job => job.state === state).length])))
async function refresh() {
  if (loading) return
  loading = true
  try {
    const res = await fetch('/api/meetings/progress')
    if (!res.ok) throw new Error('Unable to load meeting progress')
    const data = await res.json()
    if (active) { jobs.value = data; error.value = '' }
  } catch { if (active) error.value = 'Unable to load meeting progress. Retrying automatically.' }
  finally { loading = false }
}
async function retry(id: string) {
  retrying.value = id
  try {
    const res = await fetch(`/api/meetings/retry?id=${encodeURIComponent(id)}`, { method: 'POST' })
    if (!res.ok) throw new Error('Retry failed')
    await refresh()
  } catch { error.value = 'Retry could not be queued. Refresh and try again.' }
  finally { retrying.value = '' }
}
onMounted(() => { void refresh(); timer = setInterval(() => void refresh(), 3000) })
onUnmounted(() => { active = false; clearInterval(timer) })
defineExpose({ refresh })
</script>
<template>
  <div class="meeting-progress">
    <p aria-live="polite">{{ meetingIds ? 'Selected backfill' : 'Imports' }}: {{ counts.succeeded }} succeeded · {{ counts.failed }} failed · {{ counts.running }} running · {{ counts.pending }} pending</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <ul v-if="visibleJobs.length" class="meeting-jobs">
      <li v-for="job in visibleJobs" :key="job.meetingId">
        <strong>{{ job.title || job.meetingId }}</strong> — {{ job.state }} (attempt {{ job.attempts }})
        <span v-if="job.error"> · {{ job.error }}</span>
        <button v-if="job.state === 'failed'" type="button" class="btn-primary" :disabled="!!retrying" @click="retry(job.meetingId)">
          {{ retrying === job.meetingId ? 'Queuing…' : 'Retry' }}
        </button>
      </li>
    </ul>
  </div>
</template>
<style scoped>
.meeting-progress { margin-top: 1rem; font-size: .875rem; }
.meeting-jobs { max-height: 20rem; overflow: auto; padding-left: 1.25rem; }
.meeting-jobs li { margin-bottom: .75rem; overflow-wrap: anywhere; }
.meeting-jobs button { margin-left: .5rem; }
</style>
