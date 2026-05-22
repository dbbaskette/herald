<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
import { useStatusStore } from '@/stores/status'
import { useApprovalsStore } from '@/stores/approvals'
import ApprovalInbox from '@/components/ApprovalInbox.vue'

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

function mcpStatusClass(status: string): string {
  if (status === 'connected') return 'status-ok'
  if (status === 'error') return 'status-err'
  return 'status-idle'
}

function formatTime(ts: string | null): string {
  if (!ts) return '—'
  try {
    return new Date(ts).toLocaleString()
  } catch {
    return ts
  }
}
</script>

<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">System Status</h1>
      <div class="live-indicator">
        <span class="status-dot" :class="store.connected ? 'status-dot--live' : 'status-dot--idle'" />
        <span class="live-label">{{ store.connected ? 'Live' : 'Disconnected' }}</span>
      </div>
    </div>

    <div v-if="store.loading" class="text-muted">Loading status…</div>

    <template v-else>
      <section class="mb-8">
        <ApprovalInbox />
      </section>

      <div class="status-grid">
        <div class="card status-card">
          <div class="card-head">
            <span class="status-dot" :class="store.status.bot.running ? 'status-dot--live' : 'status-dot--error'" />
            <h2 class="section-label">Bot</h2>
          </div>
          <dl class="kv-list">
            <div class="kv-row">
              <dt>Status</dt>
              <dd :class="store.status.bot.running ? 'status-ok' : 'status-err'">
                {{ store.status.bot.running ? 'Running' : 'Stopped' }}
              </dd>
            </div>
            <div class="kv-row"><dt>PID</dt><dd>{{ store.status.bot.pid ?? '—' }}</dd></div>
            <div class="kv-row"><dt>Uptime</dt><dd>{{ store.status.bot.uptime }}</dd></div>
            <div class="kv-row"><dt>Restarts</dt><dd>{{ store.status.bot.restartCount }}</dd></div>
          </dl>
        </div>

        <div class="card status-card">
          <div class="card-head">
            <span class="status-dot status-dot--warning" />
            <h2 class="section-label">Model</h2>
          </div>
          <dl class="kv-list">
            <div class="kv-row"><dt>Name</dt><dd>{{ store.status.model.name }}</dd></div>
            <div class="kv-row"><dt>Requests today</dt><dd>{{ store.status.model.requestsToday }}</dd></div>
            <div class="kv-row"><dt>Est. token spend</dt><dd>{{ store.status.model.estimatedTokenSpend }}</dd></div>
          </dl>
        </div>

        <div class="card status-card">
          <div class="card-head">
            <span class="status-dot" style="background: #a855f7" />
            <h2 class="section-label">Skills</h2>
          </div>
          <dl class="kv-list">
            <div class="kv-row"><dt>Total loaded</dt><dd>{{ store.status.skills.totalLoaded }}</dd></div>
            <div class="kv-row"><dt>Last reload</dt><dd>{{ formatTime(store.status.skills.lastReload) }}</dd></div>
            <div v-if="store.status.skills.parseErrors.length" class="parse-errors">
              <dt class="status-err">Parse errors</dt>
              <dd v-for="(err, i) in store.status.skills.parseErrors" :key="i" class="status-err text-xs">{{ err }}</dd>
            </div>
          </dl>
        </div>

        <div class="card status-card">
          <div class="card-head">
            <span class="status-dot status-dot--warning" />
            <h2 class="section-label">Memory</h2>
          </div>
          <dl class="kv-list">
            <div class="kv-row"><dt>Entries</dt><dd>{{ store.status.memory.entryCount }}</dd></div>
            <div class="kv-row"><dt>DB file size</dt><dd>{{ store.status.memory.databaseFileSize }}</dd></div>
          </dl>
        </div>

        <div class="card status-card span-2">
          <div class="card-head">
            <span class="status-dot" style="background: #6366f1" />
            <h2 class="section-label">MCP Connections</h2>
          </div>
          <div v-if="store.status.mcp.length === 0" class="text-muted text-sm">No MCP servers configured</div>
          <div v-else class="mcp-list">
            <div v-for="server in store.status.mcp" :key="server.name" class="mcp-row">
              <div class="mcp-name">
                <span>{{ server.name }}</span>
                <span class="text-xs" :class="mcpStatusClass(server.status)">{{ server.status }}</span>
              </div>
              <div class="mcp-meta">
                <span>{{ server.toolCount }} tools</span>
                <span>Last ping: {{ formatTime(server.lastPing) }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div v-if="store.status.cron.length" class="card section-block">
        <h2 class="section-label mb-3">Cron Jobs</h2>
        <table class="data-table">
          <thead>
            <tr>
              <th class="table-header pb-2">Job</th>
              <th class="table-header pb-2">Next Run</th>
              <th class="table-header pb-2">Last Run</th>
              <th class="table-header pb-2">Last Result</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="job in store.status.cron" :key="job.name" class="data-row">
              <td class="font-medium">{{ job.name }}</td>
              <td class="text-secondary">{{ formatTime(job.nextRun) }}</td>
              <td class="text-secondary">{{ formatTime(job.lastRun) }}</td>
              <td>
                <span v-if="job.lastResult" class="result-badge" :class="job.lastResult === 'success' ? 'ok' : 'err'">
                  {{ job.lastResult }}
                </span>
                <span v-else class="text-muted">—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="card section-block">
        <h2 class="section-label mb-3">Recent Activity</h2>
        <div v-if="store.status.recentActivity.length === 0" class="text-muted text-sm">No recent activity</div>
        <div v-else class="activity-list">
          <div v-for="(entry, i) in store.status.recentActivity" :key="i" class="activity-item">
            <div class="activity-head">
              <span class="text-xs text-muted">{{ formatTime(entry.timestamp) }}</span>
              <div v-if="entry.toolCalls.length" class="tool-tags">
                <span v-for="(tool, j) in entry.toolCalls" :key="j" class="tool-tag">{{ tool }}</span>
              </div>
            </div>
            <p class="activity-preview">{{ entry.messagePreview }}</p>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24px;
}

.live-indicator {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 0.875rem;
  color: var(--color-text-muted);
}

.mb-8 { margin-bottom: 32px; }
.mb-3 { margin-bottom: 12px; }
.text-muted { color: var(--color-text-muted); }
.text-secondary { color: var(--color-text-secondary); }
.text-xs { font-size: 0.75rem; }
.text-sm { font-size: 0.875rem; }
.font-medium { font-weight: 500; }

.status-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
  margin-bottom: 32px;
}

.span-2 {
  grid-column: span 2;
}

@media (max-width: 720px) {
  .span-2 { grid-column: span 1; }
}

.status-card {
  padding: 16px;
}

.card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.kv-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 0.875rem;
}

.kv-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

.kv-row dt {
  color: var(--color-text-muted);
}

.kv-row dd {
  margin: 0;
  color: var(--color-text-primary);
  font-weight: 500;
}

.status-ok { color: #15803d; }
.status-err { color: #dc2626; }
.status-idle { color: var(--color-text-muted); }

.parse-errors {
  padding-top: 4px;
}

.mcp-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.mcp-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 0.875rem;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--color-border-light);
}

.mcp-row:last-child {
  border-bottom: none;
}

.mcp-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 500;
}

.mcp-meta {
  display: flex;
  gap: 16px;
  font-size: 0.75rem;
  color: var(--color-text-muted);
}

.section-block {
  padding: 16px;
  margin-bottom: 24px;
}

.data-table {
  width: 100%;
  font-size: 0.875rem;
}

.data-row td {
  padding: 6px 0;
  border-bottom: 1px solid var(--color-border-light);
}

.result-badge {
  font-size: 0.75rem;
  padding: 2px 6px;
  border-radius: 4px;
}

.result-badge.ok {
  background: rgba(22, 163, 74, 0.12);
  color: #15803d;
}

.result-badge.err {
  background: rgba(239, 68, 68, 0.1);
  color: #dc2626;
}

.activity-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.activity-item {
  padding-bottom: 10px;
  border-bottom: 1px solid var(--color-border-light);
}

.activity-item:last-child {
  border-bottom: none;
}

.activity-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}

.tool-tags {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.tool-tag {
  font-size: 0.65rem;
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--color-border-light);
  color: var(--color-text-secondary);
}

.activity-preview {
  margin: 0;
  font-size: 0.875rem;
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
