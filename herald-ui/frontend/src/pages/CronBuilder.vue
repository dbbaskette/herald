<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useCronStore, type CronJob } from '@/stores/cron'
import NowStripe from '@/components/NowStripe.vue'
import PageHeader from '@/components/PageHeader.vue'
import SectionRule from '@/components/SectionRule.vue'
import StatusGlyph from '@/components/StatusGlyph.vue'

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

const cronExpression = computed(
  () => `${cronMinute.value} ${cronHour.value} ${cronDayOfMonth.value} ${cronMonth.value} ${cronDayOfWeek.value}`,
)

const humanReadableSchedule = computed(() =>
  editingJob.value ? describeCron(cronExpression.value) : '',
)

function describeCron(expr: string): string {
  const parts = expr.split(' ')
  if (parts.length !== 5) return expr
  const [min, hour, dom, mon, dow] = parts
  if (min === '*' && hour === '*' && dom === '*' && mon === '*' && dow === '*') return 'every minute'
  if (hour === '*' && dom === '*' && mon === '*' && dow === '*') return `every hour at :${min.padStart(2, '0')}`
  if (dom === '*' && mon === '*' && dow === '*') {
    return hour === '*'
      ? `every hour at :${min.padStart(2, '0')}`
      : `daily at ${hour.padStart(2, '0')}:${min.padStart(2, '0')}`
  }
  if (dom === '*' && mon === '*' && dow !== '*') {
    const days = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']
    return `${days[parseInt(dow)] || dow}s at ${hour.padStart(2, '0')}:${min.padStart(2, '0')}`
  }
  return expr
}

const formatJobSchedule = (e: string) => describeCron(e)

function formatTime(ts: string | null): string {
  if (!ts) return '—'
  try { return new Date(ts).toLocaleString() } catch { return ts }
}

function statusGlyph(s: string): 'live' | 'running' | 'err' | 'idle' {
  if (s === 'success') return 'live'
  if (s === 'running') return 'running'
  if (s === 'error' || s === 'failed') return 'err'
  return 'idle'
}

function openNewJob() {
  cronMinute.value = '0'; cronHour.value = '9'
  cronDayOfMonth.value = '*'; cronMonth.value = '*'; cronDayOfWeek.value = '*'
  editingJob.value = { name: '', expression: '0 9 * * *', enabled: true, promptText: '' }
}
function openEditJob(job: CronJob) {
  const parts = job.expression.split(' ')
  if (parts.length === 5) {
    [cronMinute.value, cronHour.value, cronDayOfMonth.value, cronMonth.value, cronDayOfWeek.value] = parts
  }
  editingJob.value = { ...job }
}
function cancelEdit() { editingJob.value = null }
async function saveEdit() {
  if (!editingJob.value || !editingJob.value.name?.trim()) return
  editingJob.value.expression = cronExpression.value
  const ok = await store.saveJob(editingJob.value as { name: string; expression: string; promptText: string })
  if (ok) editingJob.value = null
}
function toggleExpanded(id: string) { expandedJobId.value = expandedJobId.value === id ? null : id }
async function toggleEnabled(job: CronJob) { await store.toggleJob(job.id, !job.enabled) }
function confirmDelete(id: string) { deletingJobId.value = id }
async function executeDelete(id: string) { await store.deleteJob(id); deletingJobId.value = null }
function cancelDelete() { deletingJobId.value = null }
async function runNow(id: string) { runningJobId.value = id; await store.runJob(id); runningJobId.value = null }

const minutes = Array.from({ length: 60 }, (_, i) => String(i))
const hours = Array.from({ length: 24 }, (_, i) => String(i))
const daysOfMonth = Array.from({ length: 31 }, (_, i) => String(i + 1))
const months = [
  ['1','Jan'],['2','Feb'],['3','Mar'],['4','Apr'],['5','May'],['6','Jun'],
  ['7','Jul'],['8','Aug'],['9','Sep'],['10','Oct'],['11','Nov'],['12','Dec'],
] as const
const daysOfWeek = [
  ['0','Sun'],['1','Mon'],['2','Tue'],['3','Wed'],['4','Thu'],['5','Fri'],['6','Sat'],
] as const

onMounted(() => { store.fetchJobs() })
</script>

<template>
  <div class="cron-page">
    <NowStripe />

    <PageHeader title="Cron" path="/cron">
      <template #right>
        <button class="btn-primary" @click="openNewJob()">+ New job</button>
      </template>
    </PageHeader>

    <div v-if="store.loading" class="empty">
      <StatusGlyph kind="idle" /> Loading cron jobs…
    </div>

    <!-- ── Editor panel ─────────────────────────────────────────── -->
    <template v-if="editingJob">
      <SectionRule :label="editingJob.id ? 'EDIT JOB' : 'NEW JOB'" glyph="warn" tone="warn" />

      <div class="editor">
        <div class="field">
          <label class="field__label">Name</label>
          <input
            v-model="editingJob.name"
            type="text"
            class="input"
            placeholder="e.g. Morning briefing"
            style="max-width: 24rem"
          />
        </div>

        <div class="field">
          <label class="field__label">Schedule</label>
          <div class="cron-grid">
            <div>
              <label class="micro-label">min</label>
              <select v-model="cronMinute" class="input input-cron">
                <option value="*">*</option>
                <option v-for="m in minutes" :key="m" :value="m">{{ m }}</option>
              </select>
            </div>
            <div>
              <label class="micro-label">hour</label>
              <select v-model="cronHour" class="input input-cron">
                <option value="*">*</option>
                <option v-for="h in hours" :key="h" :value="h">{{ h }}</option>
              </select>
            </div>
            <div>
              <label class="micro-label">day-of-month</label>
              <select v-model="cronDayOfMonth" class="input input-cron">
                <option value="*">*</option>
                <option v-for="d in daysOfMonth" :key="d" :value="d">{{ d }}</option>
              </select>
            </div>
            <div>
              <label class="micro-label">month</label>
              <select v-model="cronMonth" class="input input-cron">
                <option value="*">*</option>
                <option v-for="mo in months" :key="mo[0]" :value="mo[0]">{{ mo[1] }}</option>
              </select>
            </div>
            <div>
              <label class="micro-label">day-of-week</label>
              <select v-model="cronDayOfWeek" class="input input-cron">
                <option value="*">*</option>
                <option v-for="dw in daysOfWeek" :key="dw[0]" :value="dw[0]">{{ dw[1] }}</option>
              </select>
            </div>
          </div>
          <div class="cron-readout">
            <code class="cron-expr">{{ cronExpression }}</code>
            <span class="caption">→ {{ humanReadableSchedule }}</span>
          </div>
        </div>

        <div class="field">
          <label class="field__label">Prompt</label>
          <textarea
            v-model="editingJob.promptText"
            rows="4"
            class="input textarea"
            placeholder="The prompt to execute when this job runs…"
          />
        </div>

        <div class="actions">
          <button class="btn-primary" :disabled="!editingJob.name?.trim()" @click="saveEdit()">Save</button>
          <button class="btn-secondary" @click="cancelEdit()">Cancel</button>
        </div>
      </div>
    </template>

    <!-- ── Jobs list ────────────────────────────────────────────── -->
    <template v-if="!store.loading && !editingJob">
      <SectionRule
        label="JOBS"
        tone="ok"
        :trailing="`${store.jobs.length} job${store.jobs.length === 1 ? '' : 's'}`"
      />
      <table class="data-table jobs-table">
        <thead>
          <tr>
            <th class="col-toggle"></th>
            <th>Name</th>
            <th>Schedule</th>
            <th>Expr</th>
            <th>Status</th>
            <th>Last run</th>
            <th>Next run</th>
            <th class="col-actions"></th>
          </tr>
        </thead>
        <tbody>
          <template v-for="job in store.jobs" :key="job.id">
            <tr>
              <td class="col-toggle">
                <button
                  class="toggle"
                  :class="{ 'toggle--on': job.enabled }"
                  :title="job.enabled ? 'Disable' : 'Enable'"
                  @click="toggleEnabled(job)"
                >
                  <span class="toggle-thumb" />
                </button>
              </td>
              <td class="cell-name">{{ job.name }}</td>
              <td>{{ formatJobSchedule(job.expression) }}</td>
              <td><code class="expr-pill">{{ job.expression }}</code></td>
              <td>
                <span class="status-inline">
                  <StatusGlyph :kind="statusGlyph(job.status)" size="sm" />
                  {{ job.status || '—' }}
                </span>
              </td>
              <td class="text-muted">{{ formatTime(job.lastRun) }}</td>
              <td class="text-muted">{{ formatTime(job.nextRun) }}</td>
              <td class="col-actions">
                <div class="row-actions">
                  <button
                    class="link-action"
                    :disabled="runningJobId === job.id"
                    @click="runNow(job.id)"
                  >
                    {{ runningJobId === job.id ? '…running' : 'run' }}
                  </button>
                  <button class="link-muted" @click="openEditJob(job)">edit</button>
                  <template v-if="!job.builtIn">
                    <span v-if="deletingJobId === job.id" class="confirm-group">
                      <button class="link-danger" @click="executeDelete(job.id)">delete</button>
                      <button class="link-muted" @click="cancelDelete()">no</button>
                    </span>
                    <button v-else class="link-muted" @click="confirmDelete(job.id)">del</button>
                  </template>
                  <button
                    v-if="job.lastRunLog"
                    class="link-muted"
                    @click="toggleExpanded(job.id)"
                  >
                    {{ expandedJobId === job.id ? 'hide' : 'log' }}
                  </button>
                </div>
              </td>
            </tr>
            <tr v-if="expandedJobId === job.id && job.lastRunLog" class="log-row">
              <td colspan="8">
                <div class="label log-label">LAST RUN OUTPUT</div>
                <pre class="log-pane">{{ job.lastRunLog }}</pre>
              </td>
            </tr>
          </template>
          <tr v-if="store.jobs.length === 0">
            <td colspan="8" class="empty-cell">No cron jobs configured.</td>
          </tr>
        </tbody>
      </table>
    </template>
  </div>
</template>

<style scoped>
.cron-page { max-width: 1100px; }

.empty {
  font-size: 0.8125rem;
  color: var(--graphite-2);
  font-style: italic;
  padding: 8px 0;
  display: flex;
  gap: 8px;
  align-items: baseline;
}

/* ─── Editor ──────────────────────────────────────────────── */
.editor { padding: 8px 0 16px; }

.field { margin-bottom: 18px; }
.field__label {
  display: block;
  font-size: 0.6875rem;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.10em;
  color: var(--graphite-2);
  margin-bottom: 6px;
}
.micro-label {
  display: block;
  font-size: 0.625rem;
  color: var(--graphite-2);
  letter-spacing: 0.06em;
  margin-bottom: 4px;
}

.cron-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
  max-width: 44rem;
}
@media (max-width: 700px) {
  .cron-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
.input-cron { width: 100%; }

.cron-readout {
  margin-top: 10px;
  display: flex;
  align-items: baseline;
  gap: 12px;
  font-size: 0.75rem;
}
.cron-expr {
  background: var(--paper-2);
  padding: 2px 6px;
  border: 1px solid var(--paper-rule);
  color: var(--gold-dim);
}

.textarea {
  resize: vertical;
  min-height: 96px;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
}

.actions { display: flex; gap: 8px; padding-top: 4px; }

/* ─── Jobs table ──────────────────────────────────────────── */
.jobs-table { font-size: 0.8125rem; }
.col-toggle  { width: 3rem; }
.col-actions { width: 12rem; text-align: right; }
.cell-name   { color: var(--ink); font-weight: 500; }

.expr-pill {
  background: var(--paper-2);
  padding: 1px 6px;
  color: var(--gold-dim);
  font-size: 0.6875rem;
  border: 1px solid var(--paper-rule);
}

.status-inline {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  color: var(--ink);
}

/* Toggle switch */
.toggle {
  position: relative;
  width: 28px;
  height: 14px;
  border-radius: 8px;
  background: var(--paper-rule);
  border: 1px solid var(--paper-rule);
  cursor: pointer;
  transition: background 80ms, border-color 80ms;
  padding: 0;
}
.toggle-thumb {
  position: absolute;
  top: 1px;
  left: 1px;
  width: 10px;
  height: 10px;
  background: var(--paper);
  border-radius: 50%;
  transition: transform 100ms;
}
.toggle--on {
  background: var(--gold);
  border-color: var(--gold-dim);
}
.toggle--on .toggle-thumb {
  transform: translateX(14px);
  background: var(--ink);
}

/* Row-action links */
.row-actions {
  display: inline-flex;
  align-items: baseline;
  gap: 10px;
  justify-content: flex-end;
}
.confirm-group { display: inline-flex; gap: 6px; }

.link-action, .link-muted, .link-danger {
  background: none;
  border: none;
  padding: 0;
  font-family: inherit;
  font-size: 0.75rem;
  cursor: pointer;
}
.link-action  { color: var(--gold-dim); }
.link-action:disabled { opacity: 0.4; cursor: not-allowed; }
.link-muted   { color: var(--graphite-2); }
.link-muted:hover { color: var(--ink); }
.link-danger  { color: var(--err); }

.empty-cell {
  text-align: center;
  font-style: italic;
  color: var(--graphite-2);
  padding: 24px 0;
}

/* Last-run log */
.log-row td {
  background: var(--paper-2);
  padding: 10px 12px;
}
.log-label { margin-bottom: 6px; color: var(--graphite-2); }
.log-pane {
  margin: 0;
  padding: 10px 12px;
  background: var(--ink);
  color: var(--paper);
  font-size: 0.6875rem;
  line-height: 1.5;
  max-height: 16rem;
  overflow: auto;
  white-space: pre-wrap;
}
</style>
