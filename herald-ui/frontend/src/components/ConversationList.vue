<script setup lang="ts">
/**
 * Multi-conversation sidebar for the Chat page (#361).
 *
 * Lists persisted conversations from /api/conversations (sourced from the
 * SPRING_AI_CHAT_MEMORY table). Click to switch; trash icon to delete.
 *
 * Today the conversation IDs in the table mix Telegram + web origins. Web
 * IDs use the `web-` prefix (assigned by `newConversation()` in the chat
 * store). The sidebar shows them all so users can also re-read their
 * Telegram history in the web UI if they want.
 */
import { onMounted, computed } from 'vue'
import { useConversationsStore, type ConversationSummary } from '@/stores/conversations'
import { useChatStore } from '@/stores/chat'

const conversations = useConversationsStore()
const chat = useChatStore()

const groups = computed(() => {
  const now = new Date()
  const today: ConversationSummary[] = []
  const yesterday: ConversationSummary[] = []
  const week: ConversationSummary[] = []
  const older: ConversationSummary[] = []
  for (const c of conversations.items) {
    const d = new Date(c.lastTurnAt)
    const ageMs = now.getTime() - d.getTime()
    if (sameDay(d, now)) today.push(c)
    else if (ageMs < 1000 * 60 * 60 * 36) yesterday.push(c)
    else if (ageMs < 1000 * 60 * 60 * 24 * 7) week.push(c)
    else older.push(c)
  }
  return { today, yesterday, week, older }
})

function sameDay(a: Date, b: Date) {
  return a.getFullYear() === b.getFullYear()
      && a.getMonth() === b.getMonth()
      && a.getDate() === b.getDate()
}

function formatRelative(ts: string): string {
  if (!ts) return ''
  const d = new Date(ts)
  const ms = Date.now() - d.getTime()
  if (ms < 1000 * 60) return 'just now'
  if (ms < 1000 * 60 * 60) return `${Math.floor(ms / 60000)}m`
  if (ms < 1000 * 60 * 60 * 24) return `${Math.floor(ms / 3600000)}h`
  return `${Math.floor(ms / 86400000)}d`
}

async function pick(id: string) {
  await chat.switchConversation(id)
}

async function remove(id: string, evt: Event) {
  evt.stopPropagation()
  if (!confirm('Delete this conversation history? This cannot be undone.')) return
  const ok = await conversations.deleteConversation(id)
  if (ok && chat.conversationId === id) {
    chat.newConversation()
  }
}

async function refresh() {
  await conversations.fetchAll()
}

onMounted(refresh)

defineExpose({ refresh })
</script>

<template>
  <aside class="conversation-list">
    <div class="cl-header">
      <span class="cl-title">CONVERSATIONS</span>
      <button class="cl-refresh" :title="conversations.loading ? 'Refreshing…' : 'Refresh'" :disabled="conversations.loading" @click="refresh">
        <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M3 8a5 5 0 0 1 9-3l1 1M13 8a5 5 0 0 1-9 3l-1-1"/>
          <path d="M11 3v3h3M5 13v-3H2"/>
        </svg>
      </button>
    </div>

    <div v-if="conversations.loading && conversations.items.length === 0" class="cl-loading">Loading…</div>
    <div v-else-if="conversations.items.length === 0" class="cl-empty">
      No conversations yet
    </div>

    <div v-else class="cl-body">
      <template v-for="(group, label) in groups" :key="label">
        <div v-if="group.length > 0" class="cl-group">
          <div class="cl-group-label">{{ label }}</div>
          <button
            v-for="c in group"
            :key="c.id"
            class="cl-item"
            :class="{ active: chat.conversationId === c.id }"
            :title="`${c.id} · ${c.turnCount} turns`"
            @click="pick(c.id)"
          >
            <span class="cl-item-title">{{ c.title }}</span>
            <span class="cl-item-meta">
              <span class="cl-item-time">{{ formatRelative(c.lastTurnAt) }}</span>
              <span class="cl-item-count">{{ c.turnCount }}</span>
            </span>
            <button
              class="cl-item-delete"
              title="Delete conversation"
              @click="remove(c.id, $event)"
            >
              <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.5">
                <path d="M3 4h6M5 4V3a1 1 0 0 1 1-1h0a1 1 0 0 1 1 1v1m1 0v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V4h4z"/>
              </svg>
            </button>
          </button>
        </div>
      </template>
    </div>
  </aside>
</template>

<style scoped>
.conversation-list {
  width: 220px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--color-border);
  border-radius: 10px;
  background: var(--color-surface);
  overflow: hidden;
}

.cl-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px 6px;
  border-bottom: 1px solid var(--color-border-light);
}

.cl-title {
  font-size: 0.65rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.1em;
  color: var(--color-text-muted);
}

.cl-refresh {
  width: 22px;
  height: 22px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  border: none;
  color: var(--color-text-muted);
  border-radius: 4px;
  cursor: pointer;
}

.cl-refresh:hover:not(:disabled) {
  background: var(--color-border-light);
  color: var(--color-text-primary);
}

.cl-refresh svg {
  width: 12px;
  height: 12px;
}

.cl-loading, .cl-empty {
  padding: 24px 12px;
  text-align: center;
  font-size: 0.75rem;
  color: var(--color-text-muted);
}

.cl-body {
  flex: 1;
  overflow-y: auto;
  padding: 4px 6px;
}

.cl-group + .cl-group {
  margin-top: 8px;
}

.cl-group-label {
  padding: 8px 8px 4px;
  font-size: 0.6rem;
  font-weight: 600;
  text-transform: capitalize;
  letter-spacing: 0.06em;
  color: var(--color-text-muted);
}

.cl-item {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 4px 8px;
  width: 100%;
  padding: 7px 9px;
  border: none;
  background: transparent;
  text-align: left;
  border-radius: 6px;
  cursor: pointer;
  font-family: inherit;
  position: relative;
  transition: background 0.12s;
}

.cl-item:hover {
  background: var(--color-border-light);
}

.cl-item.active {
  background: rgba(200, 165, 90, 0.12);
}

.cl-item-title {
  font-size: 0.8rem;
  color: var(--color-text-primary);
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  grid-column: 1;
}

.cl-item.active .cl-item-title {
  color: var(--color-brand-dim);
}

.cl-item-meta {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 0.65rem;
  color: var(--color-text-muted);
  font-family: 'JetBrains Mono', monospace;
  grid-column: 2;
  grid-row: 1;
}

.cl-item-count {
  padding: 0 4px;
  border-radius: 3px;
  background: var(--color-border);
  color: var(--color-text-secondary);
}

.cl-item-delete {
  position: absolute;
  right: 4px;
  top: 4px;
  width: 16px;
  height: 16px;
  display: none;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: var(--color-text-muted);
  border-radius: 3px;
  cursor: pointer;
}

.cl-item:hover .cl-item-delete {
  display: inline-flex;
}

.cl-item-delete:hover {
  background: rgba(220, 38, 38, 0.12);
  color: #dc2626;
}

.cl-item-delete svg {
  width: 10px;
  height: 10px;
}
</style>
