<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useCronStore, type CronJob } from '@/stores/cron'

const store = useCronStore()

const editingJob = ref<Partial<CronJob> | null>(null)
const expandedJobId = ref<string | null>(null)
const deletingJobId = ref<string | null>(null)
const runningJobId = ref<string | null>(null)

// Visual cron builder state
const cronMinute = ref('0')
const cronHour = ref('*')
const cronDayOfMonth = ref('*')
const cronMonth = ref('*')
const cronDayOfWeek = ref('*')

const cronExpression = computed(() => {
  return `${cronMinute.value} ${cronHour.value} ${cronDayOfMonth.value} ${cronMonth.value} ${cronDayOfWeek.value}`
})

const humanReadableSchedule = computed(() => {
  if (!editingJob.value) return ''
  return describeCron(cronExpression.value)
})

function describeCron(expr: string): string {
  const parts = expr.split(' ')
  if (parts.length !== 5) return expr

  const [min, hour, dom, mon, dow] = parts

  if (min === '*' && hour === '*' && dom === '*' && mon === '*' && dow === '*') return 'Every minute'
  if (hour === '*' && dom === '*' && mon === '*' && dow === '*') return `Every hour at minute ${min}`
  if (dom === '*' && mon === '*' && dow === '*') {
    const hourStr = hour === '*' ? 'every hour' : `${hour.padStart(2, '0')}:${min.padStart(2, '0')}`
    return hour === '*' ? `Every hour at minute ${min}` : `Daily at ${hourStr}`
  }
  if (dom === '*' && mon === '*' && dow !== '*') {
    const days = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday']
    const dayName = days[parseInt(dow)] || dow
    return `Every ${dayName} at ${hour.padStart(2, '0')}:${min.padStart(2, '0')}`
  }
  return expr
}

function formatJobSchedule(expression: string): string {
  return describeCron(expression)
}

function formatTime(ts: string | null): string {
  if (!ts) return '—'
  try {
    return new Date(ts).toLocaleString()
  } catch {
    return ts
  }
}

function statusDot(status: string): string {
  if (status === 'success') return 'bg-green-400'
  if (status === 'running') return 'bg-blue-400'
  if (status === 'error' || status === 'failed') return 'bg-red-400'
  return 'bg-gray-400'
}

function openNewJob() {
  cronMinute.value = '0'
  cronHour.value = '9'
  cronDayOfMonth.value = '*'
  cronMonth.value = '*'
  cronDayOfWeek.value = '*'
  editingJob.value = {
    name: '',
    expression: '0 9 * * *',
    enabled: true,
    promptText: '',
  }
}

function openEditJob(job: CronJob) {
  const parts = job.expression.split(' ')
  if (parts.length === 5) {
    cronMinute.value = parts[0]
    cronHour.value = parts[1]
    cronDayOfMonth.value = parts[2]
    cronMonth.value = parts[3]
    cronDayOfWeek.value = parts[4]
  }
  editingJob.value = { ...job }
}

function cancelEdit() {
  editingJob.value = null
}

async function saveEdit() {
  if (!editingJob.value || !editingJob.value.name?.trim()) return
  editingJob.value.expression = cronExpression.value
  const ok = await store.saveJob(editingJob.value as { name: string; expression: string; promptText: string })
  if (ok) editingJob.value = null
}

function toggleExpanded(id: string) {
  expandedJobId.value = expandedJobId.value === id ? null : id
}

async function toggleEnabled(job: CronJob) {
  await store.toggleJob(job.id, !job.enabled)
}

function confirmDelete(id: string) {
  deletingJobId.value = id
}

async function executeDelete(id: string) {
  await store.deleteJob(id)
  deletingJobId.value = null
}

function cancelDelete() {
  deletingJobId.value = null
}

async function runNow(id: string) {
  runningJobId.value = id
  await store.runJob(id)
  runningJobId.value = null
}

const minutes = Array.from({ length: 60 }, (_, i) => String(i))
const hours = Array.from({ length: 24 }, (_, i) => String(i))
const daysOfMonth = Array.from({ length: 31 }, (_, i) => String(i + 1))
const months = [
  { value: '1', label: 'January' }, { value: '2', label: 'February' },
  { value: '3', label: 'March' }, { value: '4', label: 'April' },
  { value: '5', label: 'May' }, { value: '6', label: 'June' },
  { value: '7', label: 'July' }, { value: '8', label: 'August' },
  { value: '9', label: 'September' }, { value: '10', label: 'October' },
  { value: '11', label: 'November' }, { value: '12', label: 'December' },
]
const daysOfWeek = [
  { value: '0', label: 'Sunday' }, { value: '1', label: 'Monday' },
  { value: '2', label: 'Tuesday' }, { value: '3', label: 'Wednesday' },
  { value: '4', label: 'Thursday' }, { value: '5', label: 'Friday' },
  { value: '6', label: 'Saturday' },
]

onMounted(() => {
  store.fetchJobs()
})
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-2xl font-bold text-gray-900">Cron Builder</h1>
      <button
        class="px-3 py-1.5 text-sm bg-blue-600 text-white rounded-md shadow-sm hover:bg-blue-700"
        @click="openNewJob()"
      >
        New Job
      </button>
    </div>

    <!-- Loading state -->
    <div v-if="store.loading" class="text-gray-500">Loading cron jobs…</div>

    <!-- Edit panel -->
    <div v-if="editingJob" class="bg-white rounded-lg shadow p-6 mb-6">
      <h2 class="text-lg font-semibold text-gray-900 mb-4">
        {{ editingJob.id ? 'Edit Job' : 'New Job' }}
      </h2>

      <div class="space-y-4">
        <!-- Name -->
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">Job Name</label>
          <input
            v-model="editingJob.name"
            type="text"
            placeholder="e.g. Morning Briefing"
            class="w-full max-w-md px-3 py-2 text-sm border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-1 focus:ring-blue-500 focus:border-blue-500"
          />
        </div>

        <!-- Visual Cron Builder -->
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-2">Schedule</label>
          <div class="grid grid-cols-2 md:grid-cols-5 gap-3 max-w-2xl">
            <div>
              <label class="block text-xs text-gray-500 mb-1">Minute</label>
              <select
                v-model="cronMinute"
                class="w-full px-2 py-1.5 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-1 focus:ring-blue-500"
              >
                <option value="*">Every (*)</option>
                <option v-for="m in minutes" :key="m" :value="m">{{ m }}</option>
              </select>
            </div>
            <div>
              <label class="block text-xs text-gray-500 mb-1">Hour</label>
              <select
                v-model="cronHour"
                class="w-full px-2 py-1.5 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-1 focus:ring-blue-500"
              >
                <option value="*">Every (*)</option>
                <option v-for="h in hours" :key="h" :value="h">{{ h }}</option>
              </select>
            </div>
            <div>
              <label class="block text-xs text-gray-500 mb-1">Day of Month</label>
              <select
                v-model="cronDayOfMonth"
                class="w-full px-2 py-1.5 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-1 focus:ring-blue-500"
              >
                <option value="*">Every (*)</option>
                <option v-for="d in daysOfMonth" :key="d" :value="d">{{ d }}</option>
              </select>
            </div>
            <div>
              <label class="block text-xs text-gray-500 mb-1">Month</label>
              <select
                v-model="cronMonth"
                class="w-full px-2 py-1.5 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-1 focus:ring-blue-500"
              >
                <option value="*">Every (*)</option>
                <option v-for="mo in months" :key="mo.value" :value="mo.value">{{ mo.label }}</option>
              </select>
            </div>
            <div>
              <label class="block text-xs text-gray-500 mb-1">Day of Week</label>
              <select
                v-model="cronDayOfWeek"
                class="w-full px-2 py-1.5 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-1 focus:ring-blue-500"
              >
                <option value="*">Every (*)</option>
                <option v-for="dw in daysOfWeek" :key="dw.value" :value="dw.value">{{ dw.label }}</option>
              </select>
            </div>
          </div>
          <div class="mt-2 text-sm">
            <span class="text-gray-500">Expression:</span>
            <code class="ml-1 px-2 py-0.5 bg-gray-100 rounded text-gray-800 font-mono text-xs">{{ cronExpression }}</code>
            <span class="ml-3 text-gray-500">→</span>
            <span class="ml-1 text-gray-700">{{ humanReadableSchedule }}</span>
          </div>
        </div>

        <!-- Prompt Text -->
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">Prompt Text</label>
          <textarea
            v-model="editingJob.promptText"
            rows="4"
            placeholder="The prompt to execute when this job runs…"
            class="w-full px-3 py-2 text-sm border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-1 focus:ring-blue-500 focus:border-blue-500"
          ></textarea>
        </div>

        <!-- Actions -->
        <div class="flex gap-2 pt-2">
          <button
            class="px-4 py-2 text-sm bg-blue-600 text-white rounded-md shadow-sm hover:bg-blue-700 disabled:opacity-50"
            :disabled="!editingJob.name?.trim()"
            @click="saveEdit()"
          >
            Save
          </button>
          <button
            class="px-4 py-2 text-sm bg-white border border-gray-300 rounded-md shadow-sm hover:bg-gray-50 text-gray-700"
            @click="cancelEdit()"
          >
            Cancel
          </button>
        </div>
      </div>
    </div>

    <!-- Job list -->
    <div v-if="!store.loading" class="bg-white rounded-lg shadow overflow-hidden">
      <div class="overflow-x-auto">
        <table class="w-full text-sm">
          <thead>
            <tr class="text-left text-gray-500 border-b border-gray-200">
              <th class="px-4 pb-2 pt-3 font-medium w-10"></th>
              <th class="px-4 pb-2 pt-3 font-medium">Name</th>
              <th class="px-4 pb-2 pt-3 font-medium">Schedule</th>
              <th class="px-4 pb-2 pt-3 font-medium">Expression</th>
              <th class="px-4 pb-2 pt-3 font-medium">Status</th>
              <th class="px-4 pb-2 pt-3 font-medium">Last Run</th>
              <th class="px-4 pb-2 pt-3 font-medium">Next Run</th>
              <th class="px-4 pb-2 pt-3 font-medium w-40"></th>
            </tr>
          </thead>
          <tbody>
            <template v-for="job in store.jobs" :key="job.id">
              <tr class="border-b border-gray-50">
                <!-- Enable/Disable toggle -->
                <td class="px-4 py-2">
                  <button
                    :title="job.enabled ? 'Disable job' : 'Enable job'"
                    class="relative inline-flex h-5 w-9 items-center rounded-full transition-colors"
                    :class="job.enabled ? 'bg-blue-600' : 'bg-gray-300'"
                    @click="toggleEnabled(job)"
                  >
                    <span
                      class="inline-block h-3.5 w-3.5 rounded-full bg-white transition-transform"
                      :class="job.enabled ? 'translate-x-4.5' : 'translate-x-1'"
                    />
                  </button>
                </td>
                <td class="px-4 py-2 text-gray-900 font-medium">{{ job.name }}</td>
                <td class="px-4 py-2 text-gray-700">{{ formatJobSchedule(job.expression) }}</td>
                <td class="px-4 py-2">
                  <code class="px-1.5 py-0.5 bg-gray-100 rounded text-xs font-mono text-gray-600">{{ job.expression }}</code>
                </td>
                <td class="px-4 py-2">
                  <span class="inline-flex items-center gap-1.5">
                    <span class="w-2 h-2 rounded-full" :class="statusDot(job.status)"></span>
                    <span class="text-gray-700">{{ job.status || '—' }}</span>
                  </span>
                </td>
                <td class="px-4 py-2 text-gray-500">{{ formatTime(job.lastRun) }}</td>
                <td class="px-4 py-2 text-gray-500">{{ formatTime(job.nextRun) }}</td>
                <td class="px-4 py-2">
                  <div class="flex items-center gap-2">
                    <!-- Run Now -->
                    <button
                      class="px-2 py-1 text-xs bg-white border border-gray-300 rounded hover:bg-gray-50 text-gray-700 disabled:opacity-50"
                      :disabled="runningJobId === job.id"
                      title="Run now"
                      @click="runNow(job.id)"
                    >
                      {{ runningJobId === job.id ? 'Running…' : 'Run Now' }}
                    </button>
                    <!-- Edit -->
                    <button
                      class="text-gray-400 hover:text-blue-600"
                      title="Edit job"
                      @click="openEditJob(job)"
                    >
                      <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
                      </svg>
                    </button>
                    <!-- Delete (not for built-in) -->
                    <template v-if="!job.builtIn">
                      <div v-if="deletingJobId === job.id" class="flex gap-1">
                        <button
                          class="text-xs text-red-600 hover:text-red-800"
                          @click="executeDelete(job.id)"
                        >
                          Confirm
                        </button>
                        <button
                          class="text-xs text-gray-500 hover:text-gray-700"
                          @click="cancelDelete()"
                        >
                          No
                        </button>
                      </div>
                      <button
                        v-else
                        class="text-gray-400 hover:text-red-600"
                        title="Delete job"
                        @click="confirmDelete(job.id)"
                      >
                        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                        </svg>
                      </button>
                    </template>
                    <!-- Expand log -->
                    <button
                      v-if="job.lastRunLog"
                      class="text-gray-400 hover:text-gray-600"
                      title="Show last run log"
                      @click="toggleExpanded(job.id)"
                    >
                      <svg
                        class="w-4 h-4 transition-transform"
                        :class="expandedJobId === job.id ? 'rotate-180' : ''"
                        fill="none" stroke="currentColor" viewBox="0 0 24 24"
                      >
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
                      </svg>
                    </button>
                  </div>
                </td>
              </tr>
              <!-- Expanded log row -->
              <tr v-if="expandedJobId === job.id && job.lastRunLog">
                <td colspan="8" class="px-4 py-3 bg-gray-50">
                  <div class="text-xs font-medium text-gray-500 mb-1">Last Run Output</div>
                  <pre class="text-xs text-gray-700 bg-gray-900 text-gray-100 rounded p-3 overflow-x-auto whitespace-pre-wrap max-h-64 overflow-y-auto">{{ job.lastRunLog }}</pre>
                </td>
              </tr>
            </template>

            <!-- Empty state -->
            <tr v-if="store.jobs.length === 0 && !store.loading">
              <td colspan="8" class="px-4 py-8 text-center text-gray-400">
                No cron jobs configured yet
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>
