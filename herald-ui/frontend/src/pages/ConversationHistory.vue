<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useMessagesStore } from '@/stores/messages'
import ConfirmModal from '@/components/ConfirmModal.vue'
import { extractMemoryPath, memoryViewerRoute } from '@/utils/memoryTools'
import NowStripe from '@/components/NowStripe.vue'
import PageHeader from '@/components/PageHeader.vue'
import SectionRule from '@/components/SectionRule.vue'
import StatusGlyph from '@/components/StatusGlyph.vue'

const store = useMessagesStore()
const router = useRouter()

const expandedTools = ref<Set<string>>(new Set())
const expandedSubagents = ref<Set<string>>(new Set())
const confirmingClear = ref(false)

onMounted(() => { store.fetchMessages() })

function toggleToolCall(messageId: string, index: number) {
  const key = `${messageId}-tool-${index}`
  if (expandedTools.value.has(key)) expandedTools.value.delete(key)
  else expandedTools.value.add(key)
}
const isToolExpanded = (m: string, i: number) => expandedTools.value.has(`${m}-tool-${i}`)

function toggleSubagent(messageId: string, index: number) {
  const key = `${messageId}-sub-${index}`
  if (expandedSubagents.value.has(key)) expandedSubagents.value.delete(key)
  else expandedSubagents.value.add(key)
}
const isSubagentExpanded = (m: string, i: number) => expandedSubagents.value.has(`${m}-sub-${i}`)

async function executeClear() {
  await store.clearHistory()
  confirmingClear.value = false
}

function formatTime(ts: string | null): string {
  if (!ts) return '—'
  try { return new Date(ts).toLocaleString() } catch { return ts }
}

function roleBadgeClass(role: string): string {
  switch (role) {
    case 'user':      return 'badge badge-user'
    case 'assistant': return 'badge badge-assistant'
    case 'system':    return 'badge badge-system'
    default:          return 'badge badge-system'
  }
}

function formatJson(v: unknown): string {
  try { return JSON.stringify(v, null, 2) } catch { return String(v) }
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
  <div class="history-page">
    <NowStripe />

    <PageHeader title="History" path="/history">
      <template #right>
        <RouterLink to="/chat" class="btn-secondary">Open chat</RouterLink>
        <button class="btn-danger" @click="confirmingClear = true">Clear history</button>
      </template>
    </PageHeader>

    <p class="page-hint">
      Audit log of all bot messages. To resume a thread with full context, use the
      conversations sidebar on the Chat page.
    </p>

    <ConfirmModal
      :open="confirmingClear"
      title="Clear history"
      message="Clear all conversation history? This cannot be undone."
      confirm-label="Clear all"
      danger
      @confirm="executeClear()"
      @cancel="confirmingClear = false"
    />

    <!-- Filter bar -->
    <SectionRule label="FILTER" tone="info" />
    <div class="filter-bar">
      <div class="filter-field flex-1">
        <label class="micro-label">Search</label>
        <input v-model="store.search" type="text" placeholder="search messages…" class="input" @keydown.enter="store.applyFilters()" />
      </div>
      <div class="filter-field">
        <label class="micro-label">From</label>
        <input v-model="store.startDate" type="date" class="input" />
      </div>
      <div class="filter-field">
        <label class="micro-label">To</label>
        <input v-model="store.endDate" type="date" class="input" />
      </div>
      <button class="btn-primary" @click="store.applyFilters()">Search</button>
      <button class="btn-secondary" @click="store.clearFilters()">Clear</button>
    </div>

    <SectionRule
      label="MESSAGES"
      tone="gold"
      :trailing="`${store.totalElements} total`"
    />

    <div v-if="store.loading" class="empty">
      <StatusGlyph kind="idle" /> Loading…
    </div>

    <div v-else class="message-list">
      <article v-for="msg in store.messages" :key="msg.id" class="message">
        <div class="message-head">
          <span :class="roleBadgeClass(msg.role)">{{ msg.role }}</span>
          <span class="caption">{{ formatTime(msg.timestamp) }}</span>
          <button
            v-if="msg.role === 'user'"
            class="link-action continue-link"
            @click="continueInChat(msg.content)"
          >
            continue in chat →
          </button>
        </div>
        <p class="message-body">{{ msg.content }}</p>

        <div v-if="msg.toolCalls && msg.toolCalls.length > 0" class="tool-section">
          <div class="label tool-section-label">tool calls · {{ msg.toolCalls.length }}</div>
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
              open in memory →
            </span>
            <div v-if="isToolExpanded(msg.id, ti)" class="tool-detail">
              <div class="label detail-label">inputs</div>
              <pre class="detail-code">{{ formatJson(tool.inputs) }}</pre>
              <div class="label detail-label">outputs</div>
              <pre class="detail-code">{{ formatJson(tool.outputs) }}</pre>
            </div>
          </div>
        </div>

        <div v-if="msg.subagentCalls && msg.subagentCalls.length > 0" class="tool-section">
          <div class="label tool-section-label">subagents · {{ msg.subagentCalls.length }}</div>
          <div v-for="(sub, si) in msg.subagentCalls" :key="si" class="tool-block subagent">
            <button class="tool-toggle" @click="toggleSubagent(msg.id, si)">
              <span class="chevron" :class="{ open: isSubagentExpanded(msg.id, si) }">›</span>
              <span class="tool-name sub">{{ sub.name }}</span>
            </button>
            <div v-if="isSubagentExpanded(msg.id, si)" class="tool-detail">
              <div v-if="sub.toolCalls && sub.toolCalls.length > 0">
                <div class="label detail-label">tool calls</div>
                <div v-for="(stool, sti) in sub.toolCalls" :key="sti" class="sub-tool">
                  <p class="tool-name">{{ stool.name }}</p>
                  <pre class="detail-code">{{ formatJson(stool.inputs) }}</pre>
                </div>
              </div>
              <div class="label detail-label">result</div>
              <pre class="detail-code">{{ sub.result }}</pre>
            </div>
          </div>
        </div>
      </article>

      <div v-if="store.messages.length === 0" class="empty-block">
        {{ store.search || store.startDate || store.endDate ? 'no messages match the filters' : 'no conversation history yet' }}
      </div>
    </div>

    <div v-if="store.totalPages > 1" class="pagination">
      <span class="caption">page {{ store.currentPage + 1 }} of {{ store.totalPages }}</span>
      <div>
        <button class="link-muted" :disabled="!store.hasPrevPage" @click="store.prevPage()">← prev</button>
        <button class="link-muted" :disabled="!store.hasNextPage" @click="store.nextPage()">next →</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.history-page { max-width: 980px; }

.page-hint {
  color: var(--graphite-2);
  font-size: 0.6875rem;
  margin: 0 0 12px;
  letter-spacing: 0.02em;
}

/* ─── Filter bar ──────────────────────────────────────────── */
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-end;
  margin-bottom: 20px;
}
.filter-field { display: flex; flex-direction: column; gap: 4px; }
.flex-1 { flex: 1; min-width: 200px; }
.micro-label {
  font-size: 0.625rem;
  color: var(--graphite-2);
  letter-spacing: 0.06em;
}

/* ─── Empty / loading ──────────────────────────────────────── */
.empty {
  display: flex;
  gap: 8px;
  align-items: baseline;
  font-size: 0.8125rem;
  font-style: italic;
  color: var(--graphite-2);
}
.empty-block {
  text-align: center;
  font-style: italic;
  color: var(--graphite-2);
  padding: 24px 0;
}

/* ─── Message list ─────────────────────────────────────────── */
.message-list {
  display: flex;
  flex-direction: column;
}
.message {
  padding: 14px 0;
  border-bottom: 1px solid var(--paper-rule);
}
.message:last-child { border-bottom: none; }

.message-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 8px;
}

.continue-link {
  margin-left: auto;
  font-size: 0.6875rem;
}

.message-body {
  margin: 0;
  font-size: 0.8125rem;
  line-height: 1.55;
  color: var(--ink);
  white-space: pre-wrap;
}

/* ─── Tool / subagent blocks ──────────────────────────────── */
.tool-section { margin-top: 12px; }
.tool-section-label { color: var(--graphite-2); margin-bottom: 4px; }

.tool-block {
  border-left: 2px solid var(--paper-rule);
  padding-left: 10px;
  margin-top: 6px;
}
.tool-block.subagent { border-left-color: var(--gold-dim); }

.tool-toggle {
  display: flex;
  align-items: baseline;
  gap: 8px;
  background: transparent;
  border: none;
  padding: 2px 0;
  cursor: pointer;
  font-family: inherit;
  font-size: 0.8125rem;
  color: var(--ink);
}
.tool-toggle:hover { color: var(--gold-dim); }

.chevron {
  color: var(--graphite-2);
  transition: transform 80ms;
  display: inline-block;
}
.chevron.open { transform: rotate(90deg); }

.tool-name { color: var(--gold-dim); font-weight: 500; }
.tool-name.sub { color: var(--gold); }

.memory-link-row { display: block; margin: 2px 0 0 16px; }

.tool-detail {
  padding: 6px 0 8px 16px;
  margin-top: 4px;
}
.detail-label { color: var(--graphite-2); margin: 8px 0 4px; }
.detail-label:first-child { margin-top: 0; }
.detail-code {
  margin: 0;
  padding: 8px 10px;
  background: var(--paper-2);
  border-left: 2px solid var(--paper-rule);
  font-size: 0.6875rem;
  line-height: 1.5;
  color: var(--ink);
  overflow-x: auto;
  white-space: pre-wrap;
}

/* ─── Pagination ──────────────────────────────────────────── */
.pagination {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding-top: 16px;
  margin-top: 16px;
  border-top: 1px solid var(--paper-rule);
}
.pagination > div { display: flex; gap: 12px; }

.link-action, .link-muted {
  background: none;
  border: none;
  padding: 0;
  font-family: inherit;
  font-size: 0.8125rem;
  cursor: pointer;
}
.link-action { color: var(--gold-dim); }
.link-muted  { color: var(--graphite-2); }
.link-muted:hover { color: var(--ink); }
.link-muted:disabled { opacity: 0.4; cursor: not-allowed; }
</style>
