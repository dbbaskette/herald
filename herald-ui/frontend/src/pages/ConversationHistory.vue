<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useMessagesStore } from '@/stores/messages'
import ConfirmModal from '@/components/ConfirmModal.vue'
import { extractMemoryPath, memoryViewerRoute } from '@/utils/memoryTools'

const store = useMessagesStore()
const router = useRouter()

const expandedTools = ref<Set<string>>(new Set())
const expandedSubagents = ref<Set<string>>(new Set())
const confirmingClear = ref(false)

onMounted(() => {
  store.fetchMessages()
})

function toggleToolCall(messageId: string, index: number) {
  const key = `${messageId}-tool-${index}`
  if (expandedTools.value.has(key)) {
    expandedTools.value.delete(key)
  } else {
    expandedTools.value.add(key)
  }
}

function isToolExpanded(messageId: string, index: number): boolean {
  return expandedTools.value.has(`${messageId}-tool-${index}`)
}

function toggleSubagent(messageId: string, index: number) {
  const key = `${messageId}-sub-${index}`
  if (expandedSubagents.value.has(key)) {
    expandedSubagents.value.delete(key)
  } else {
    expandedSubagents.value.add(key)
  }
}

function isSubagentExpanded(messageId: string, index: number): boolean {
  return expandedSubagents.value.has(`${messageId}-sub-${index}`)
}

async function executeClear() {
  await store.clearHistory()
  confirmingClear.value = false
}

function formatTime(ts: string | null): string {
  if (!ts) return '—'
  try {
    return new Date(ts).toLocaleString()
  } catch {
    return ts
  }
}

function roleBadgeClass(role: string): string {
  switch (role) {
    case 'user': return 'badge badge-user'
    case 'assistant': return 'badge badge-assistant'
    case 'system': return 'badge badge-system'
    default: return 'badge badge-system'
  }
}

function formatJson(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

function openMemoryPath(toolName: string, inputs: Record<string, unknown>) {
  const path = extractMemoryPath(toolName, inputs)
  if (path) router.push(memoryViewerRoute(path))
}

function continueInChat(content: string) {
  sessionStorage.setItem('herald-chat-draft', content)
  router.push('/chat')
}
</script>

<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">Conversation History</h1>
      <div class="page-actions">
        <RouterLink to="/chat" class="btn-secondary">Open Chat</RouterLink>
        <button class="btn-danger" @click="confirmingClear = true">Clear History</button>
      </div>
    </div>

    <p class="help-text">
      Audit log of all bot messages. To resume a thread with full context, use the
      <strong>Conversations</strong> sidebar on the Chat page. User messages can be sent as a new chat via <em>Continue in Chat</em>.
    </p>

    <ConfirmModal
      :open="confirmingClear"
      title="Clear History"
      message="Clear all conversation history? This cannot be undone."
      confirm-label="Clear All"
      danger
      @confirm="executeClear()"
      @cancel="confirmingClear = false"
    />

    <div class="filters card">
      <div class="filter-field flex-1">
        <label class="filter-label">Search</label>
        <input v-model="store.search" type="text" placeholder="Search messages…" class="input" @keydown.enter="store.applyFilters()" />
      </div>
      <div class="filter-field">
        <label class="filter-label">From</label>
        <input v-model="store.startDate" type="date" class="input" />
      </div>
      <div class="filter-field">
        <label class="filter-label">To</label>
        <input v-model="store.endDate" type="date" class="input" />
      </div>
      <button class="btn-primary" @click="store.applyFilters()">Search</button>
      <button class="btn-secondary" @click="store.clearFilters()">Clear Filters</button>
    </div>

    <div v-if="store.loading" class="text-muted">Loading messages…</div>

    <div v-else class="message-list">
      <div v-for="msg in store.messages" :key="msg.id" class="card message-card">
        <div class="message-head">
          <span :class="roleBadgeClass(msg.role)">{{ msg.role }}</span>
          <span class="text-muted text-xs">{{ formatTime(msg.timestamp) }}</span>
          <button
            v-if="msg.role === 'user'"
            class="btn-secondary btn-sm continue-btn"
            @click="continueInChat(msg.content)"
          >
            Continue in Chat
          </button>
        </div>
        <p class="message-body">{{ msg.content }}</p>

        <div v-if="msg.toolCalls && msg.toolCalls.length > 0" class="tool-section">
          <p class="section-label">Tool Calls ({{ msg.toolCalls.length }})</p>
          <div v-for="(tool, ti) in msg.toolCalls" :key="ti" class="tool-block">
            <button class="tool-toggle" @click="toggleToolCall(msg.id, ti)">
              <span class="chevron" :class="{ open: isToolExpanded(msg.id, ti) }">›</span>
              <span class="tool-name">{{ tool.name }}</span>
            </button>
            <span
              v-if="extractMemoryPath(tool.name, tool.inputs)"
              class="link-memory memory-link-row"
              @click="openMemoryPath(tool.name, tool.inputs)"
            >
              Open in Memory →
            </span>
            <div v-if="isToolExpanded(msg.id, ti)" class="tool-detail">
              <p class="detail-label">Inputs</p>
              <pre class="detail-code">{{ formatJson(tool.inputs) }}</pre>
              <p class="detail-label">Outputs</p>
              <pre class="detail-code">{{ formatJson(tool.outputs) }}</pre>
            </div>
          </div>
        </div>

        <div v-if="msg.subagentCalls && msg.subagentCalls.length > 0" class="tool-section">
          <p class="section-label">Subagent Calls ({{ msg.subagentCalls.length }})</p>
          <div v-for="(sub, si) in msg.subagentCalls" :key="si" class="tool-block subagent">
            <button class="tool-toggle" @click="toggleSubagent(msg.id, si)">
              <span class="chevron" :class="{ open: isSubagentExpanded(msg.id, si) }">›</span>
              <span class="tool-name sub">{{ sub.name }}</span>
            </button>
            <div v-if="isSubagentExpanded(msg.id, si)" class="tool-detail">
              <div v-if="sub.toolCalls && sub.toolCalls.length > 0">
                <p class="detail-label">Tool Calls</p>
                <div v-for="(stool, sti) in sub.toolCalls" :key="sti" class="sub-tool">
                  <p class="tool-name">{{ stool.name }}</p>
                  <pre class="detail-code">{{ formatJson(stool.inputs) }}</pre>
                </div>
              </div>
              <p class="detail-label">Result</p>
              <pre class="detail-code">{{ sub.result }}</pre>
            </div>
          </div>
        </div>
      </div>

      <div v-if="store.messages.length === 0" class="card empty-state">
        {{ store.search || store.startDate || store.endDate ? 'No messages match the filters' : 'No conversation history yet' }}
      </div>
    </div>

    <div v-if="store.totalPages > 1" class="pagination">
      <p class="text-muted text-sm">
        Page {{ store.currentPage + 1 }} of {{ store.totalPages }} ({{ store.totalElements }} messages)
      </p>
      <div class="page-actions">
        <button class="btn-secondary" :disabled="!store.hasPrevPage" @click="store.prevPage()">Previous</button>
        <button class="btn-secondary" :disabled="!store.hasNextPage" @click="store.nextPage()">Next</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.page-actions {
  display: flex;
  gap: 8px;
}

.text-muted { color: var(--color-text-muted); }
.text-xs { font-size: 0.75rem; }
.text-sm { font-size: 0.875rem; }
.flex-1 { flex: 1; min-width: 200px; }

.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-end;
  padding: 16px;
  margin-bottom: 20px;
}

.filter-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.filter-label {
  font-size: 0.75rem;
  color: var(--color-text-muted);
}

.message-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.message-card {
  padding: 16px;
}

.message-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.continue-btn {
  margin-left: auto;
  padding: 4px 10px;
  font-size: 0.75rem;
}

.message-body {
  margin: 0;
  font-size: 0.875rem;
  line-height: 1.55;
  color: var(--color-text-primary);
  white-space: pre-wrap;
}

.tool-section {
  margin-top: 14px;
}

.tool-block {
  border: 1px solid var(--color-border);
  border-radius: 8px;
  margin-top: 6px;
  overflow: hidden;
}

.tool-block.subagent {
  border-color: rgba(168, 85, 247, 0.3);
}

.tool-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 12px;
  border: none;
  background: var(--color-surface);
  cursor: pointer;
  font-family: inherit;
  font-size: 0.8125rem;
  text-align: left;
}

.tool-toggle:hover {
  background: var(--color-border-light);
}

.chevron {
  color: var(--color-text-muted);
  transition: transform 0.15s;
}

.chevron.open {
  transform: rotate(90deg);
}

.tool-name {
  font-family: 'JetBrains Mono', monospace;
  font-weight: 600;
  color: var(--color-brand-dim);
}

.tool-name.sub {
  color: #7c3aed;
}

.memory-link-row {
  display: block;
  padding: 0 12px 8px;
  cursor: pointer;
}

.tool-detail {
  padding: 10px 12px;
  border-top: 1px solid var(--color-border-light);
  background: var(--color-surface-raised);
}

.detail-label {
  font-size: 0.7rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--color-text-muted);
  margin: 8px 0 4px;
}

.detail-label:first-child {
  margin-top: 0;
}

.detail-code {
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.72rem;
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: 6px;
  padding: 8px;
  overflow-x: auto;
  margin: 0;
}

.empty-state {
  padding: 32px;
  text-align: center;
  color: var(--color-text-muted);
}

.pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 20px;
}

.btn-sm {
  padding: 4px 10px;
  font-size: 0.75rem;
}
</style>
