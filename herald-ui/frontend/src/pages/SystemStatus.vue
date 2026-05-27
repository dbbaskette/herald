<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue'
import { useStatusStore } from '@/stores/status'
import { useApprovalsStore } from '@/stores/approvals'
import ApprovalInbox from '@/components/ApprovalInbox.vue'
import NowStripe from '@/components/NowStripe.vue'
import PageHeader from '@/components/PageHeader.vue'
import SectionCard from '@/components/SectionCard.vue'
import MetricRow from '@/components/MetricRow.vue'
import StatusGlyph from '@/components/StatusGlyph.vue'

const store = useStatusStore()
const approvals = useApprovalsStore()

onMounted(async () => {
  await store.fetchStatus()
  store.connectSSE()
  approvals.startPolling(5000)
})

onUnmounted(() => {
  store.disconnectSSE()
  approvals.stopPolling()
})

function formatTime(ts: string | null): string {
  if (!ts) return '—'
  try { return new Date(ts).toLocaleString() } catch { return ts }
}

const botGlyph = computed<'live' | 'err'>(() =>
  store.status.bot.running ? 'live' : 'err',
)
const botTone = computed<'ok' | 'err'>(() =>
  store.status.bot.running ? 'ok' : 'err',
)
const liveGlyph = computed<'live-pulse' | 'idle'>(() =>
  store.connected ? 'live-pulse' : 'idle',
)
const skillsTone = computed<'data' | 'warn'>(() =>
  store.status.skills.parseErrors.length ? 'warn' : 'data',
)
const skillsGlyph = computed<'data' | 'warn'>(() =>
  store.status.skills.parseErrors.length ? 'warn' : 'data',
)
const mcpGlyph = (s: string): 'live' | 'err' | 'idle' =>
  s === 'connected' ? 'live' : s === 'error' ? 'err' : 'idle'
</script>

<template>
  <div class="status-page">
    <NowStripe />

    <PageHeader title="Status" path="/status">
      <template #right>
        <span class="page-right">
          <StatusGlyph :kind="liveGlyph" size="sm" />
          <span>{{ store.connected ? 'live' : 'disconnected' }}</span>
        </span>
      </template>
    </PageHeader>

    <div v-if="store.loading" class="loading">
      <StatusGlyph kind="idle" /> Loading status…
    </div>

    <template v-else>
      <!-- Approval inbox up top when non-empty -->
      <ApprovalInbox class="approval-block" />

      <!-- Bot + Model -->
      <div class="card-grid">
        <SectionCard label="Bot" :tone="botTone" :glyph="botGlyph">
          <div class="section-body">
            <MetricRow label="Status" :tone="store.status.bot.running ? 'ok' : 'err'">
              <template #leading><StatusGlyph :kind="botGlyph" /></template>
              {{ store.status.bot.running ? 'Running' : 'Stopped' }}
            </MetricRow>
            <MetricRow label="PID"      :value="store.status.bot.pid" />
            <MetricRow label="Uptime"   :value="store.status.bot.uptime" />
            <MetricRow label="Restarts" :value="store.status.bot.restartCount" />
          </div>
        </SectionCard>

        <SectionCard label="Model" tone="gold" glyph="gold">
          <div class="section-body">
            <MetricRow label="Name"           :value="store.status.model.name" tone="gold" />
            <MetricRow label="Requests today" :value="store.status.model.requestsToday" tone="info" />
            <MetricRow label="Spend"          :value="store.status.model.estimatedTokenSpend" tone="info" />
          </div>
        </SectionCard>
      </div>

      <!-- Skills + Memory -->
      <div class="card-grid">
        <SectionCard label="Skills" :tone="skillsTone" :glyph="skillsGlyph">
          <div class="section-body">
            <MetricRow label="Total loaded" :value="store.status.skills.totalLoaded" tone="data" />
            <MetricRow label="Last reload"  :value="formatTime(store.status.skills.lastReload)" />
            <div v-if="store.status.skills.parseErrors.length" class="parse-errors">
              <div class="parse-errors__head">
                <StatusGlyph kind="warn" />
                <span class="parse-errors__label">Parse errors</span>
              </div>
              <div v-for="(err, i) in store.status.skills.parseErrors" :key="i" class="parse-errors__row">
                {{ err }}
              </div>
            </div>
          </div>
        </SectionCard>

        <SectionCard label="Memory" tone="data" glyph="data">
          <div class="section-body">
            <MetricRow label="Entries" :value="store.status.memory.entryCount" tone="data" />
            <MetricRow label="DB size" :value="store.status.memory.databaseFileSize" />
          </div>
        </SectionCard>
      </div>

      <!-- MCP -->
      <SectionCard
        label="MCP Connections"
        tone="magic"
        glyph="magic"
        :trailing="store.status.mcp.length === 0 ? '0 servers' : `${store.status.mcp.length} server${store.status.mcp.length === 1 ? '' : 's'}`"
      >
        <div v-if="store.status.mcp.length === 0" class="empty">
          No MCP servers configured.
        </div>
        <div v-else class="mcp-list">
          <div v-for="server in store.status.mcp" :key="server.name" class="mcp-row">
            <div class="mcp-row__name">
              <StatusGlyph :kind="mcpGlyph(server.status)" />
              <span class="mcp-row__text">{{ server.name }}</span>
              <span class="caption">{{ server.status }}</span>
            </div>
            <div class="mcp-row__meta">
              <span>{{ server.toolCount }} tools</span>
              <span class="dot-sep">·</span>
              <span>last ping {{ formatTime(server.lastPing) }}</span>
            </div>
          </div>
        </div>
      </SectionCard>

      <!-- Cron -->
      <SectionCard
        v-if="store.status.cron.length"
        label="Cron"
        tone="ok"
        :trailing="`${store.status.cron.length} job${store.status.cron.length === 1 ? '' : 's'}`"
      >
        <table class="data-table">
          <thead>
            <tr>
              <th>Job</th>
              <th>Next run</th>
              <th>Last run</th>
              <th>Result</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="job in store.status.cron" :key="job.name">
              <td>{{ job.name }}</td>
              <td class="text-muted">{{ formatTime(job.nextRun) }}</td>
              <td class="text-muted">{{ formatTime(job.lastRun) }}</td>
              <td>
                <span v-if="job.lastResult === 'success'" class="result-badge ok">ok</span>
                <span v-else-if="job.lastResult" class="result-badge err">{{ job.lastResult }}</span>
                <span v-else class="text-muted">—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </SectionCard>

      <!-- Recent activity -->
      <SectionCard label="Recent activity" tone="info">
        <div v-if="store.status.recentActivity.length === 0" class="empty">
          No recent activity.
        </div>
        <div v-else class="activity-list">
          <div v-for="(entry, i) in store.status.recentActivity" :key="i" class="activity-item">
            <div class="activity-item__head">
              <span class="caption">{{ formatTime(entry.timestamp) }}</span>
              <div v-if="entry.toolCalls.length" class="tool-tags">
                <span v-for="(tool, j) in entry.toolCalls" :key="j" class="tool-tag">{{ tool }}</span>
              </div>
            </div>
            <p class="activity-item__preview">{{ entry.messagePreview }}</p>
          </div>
        </div>
      </SectionCard>
    </template>
  </div>
</template>

<style scoped>
.status-page { max-width: 1080px; }

.page-right {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--graphite);
  font-size: 0.875rem;
}

.loading {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--graphite-2);
  padding: 32px 0;
}

.approval-block { margin-bottom: 20px; }

/* Two-up grid of cards */
.card-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-bottom: 16px;
}
@media (max-width: 760px) {
  .card-grid { grid-template-columns: 1fr; }
}

/* MCP list inside its card */
.mcp-list { display: flex; flex-direction: column; gap: 8px; }

.mcp-row {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 16px;
  padding: 6px 0;
  border-bottom: 1px solid var(--paper-3);
}
.mcp-row:last-child { border-bottom: none; }

.mcp-row__name {
  display: flex;
  align-items: baseline;
  gap: 10px;
  font-size: 0.9375rem;
}
.mcp-row__text { color: var(--ink); font-weight: 500; }

.mcp-row__meta {
  display: flex;
  align-items: baseline;
  gap: 8px;
  color: var(--graphite-2);
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 0.8125rem;
}
.dot-sep { color: var(--paper-3); }

/* Activity feed */
.activity-list { display: flex; flex-direction: column; }
.activity-item {
  padding: 10px 0;
  border-bottom: 1px solid var(--paper-3);
}
.activity-item:last-child { border-bottom: none; }
.activity-item__head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 4px;
}
.tool-tags { display: flex; gap: 6px; flex-wrap: wrap; }
.tool-tag {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 0.6875rem;
  padding: 1px 8px;
  background: var(--gold-softer);
  color: var(--gold-dim);
  border-radius: 999px;
  font-weight: 500;
}
.activity-item__preview {
  margin: 0;
  font-size: 0.9375rem;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* Empty states */
.empty {
  font-size: 0.9375rem;
  color: var(--graphite-2);
  font-style: italic;
  padding: 8px 0;
}

/* Parse errors block inside Skills card */
.parse-errors {
  margin-top: 12px;
  padding: 10px 12px;
  background: var(--warn-softer);
  border-left: 3px solid var(--warn);
  border-radius: 4px;
}
.parse-errors__head {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--warn);
  font-weight: 600;
  font-size: 0.8125rem;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  margin-bottom: 4px;
}
.parse-errors__label { color: var(--warn); }
.parse-errors__row {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 0.8125rem;
  color: var(--graphite);
  padding: 2px 0;
}
</style>
