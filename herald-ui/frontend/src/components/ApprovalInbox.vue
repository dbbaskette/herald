<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useApprovalsStore } from '@/stores/approvals'

const props = withDefaults(defineProps<{
  conversationId?: string
  compact?: boolean
}>(), {
  compact: false,
})

const store = useApprovalsStore()
const resolving = ref<string | null>(null)

onMounted(() => {
  store.fetchAll(props.conversationId)
})

onUnmounted(() => {
  if (props.compact) return
})

async function approve(id: string) {
  resolving.value = id
  await store.resolve(id, true)
  resolving.value = null
}

async function decline(id: string) {
  resolving.value = id
  await store.resolve(id, false)
  resolving.value = null
}

function formatTool(name: string): string {
  return name.replace(/^memory/i, 'Memory ').replace(/([A-Z])/g, ' $1').trim()
}
</script>

<template>
  <div class="approval-inbox" :class="{ compact }">
    <div v-if="!compact" class="inbox-header">
      <h2 class="section-label">Pending Approvals</h2>
      <span v-if="store.count > 0" class="inbox-badge">{{ store.count }}</span>
    </div>

    <div v-if="store.loading && store.items.length === 0" class="inbox-muted">
      Checking for pending edits…
    </div>
    <div v-else-if="store.error" class="inbox-error">{{ store.error }}</div>
    <div v-else-if="store.items.length === 0 && !compact" class="inbox-muted">
      No memory edits awaiting approval.
    </div>

    <div v-else-if="store.items.length > 0" class="inbox-list">
      <div
        v-for="item in store.items"
        :key="item.id"
        class="inbox-item card"
      >
        <div class="inbox-item-head">
          <span class="inbox-kind">{{ item.kind }}</span>
          <span class="inbox-tool">{{ formatTool(item.toolName) }}</span>
          <span v-if="item.path" class="inbox-path">{{ item.path }}</span>
        </div>
        <pre class="inbox-diff">{{ item.diffPreview }}</pre>
        <div class="inbox-actions">
          <button
            class="btn-secondary"
            :disabled="resolving === item.id"
            @click="decline(item.id)"
          >
            Decline
          </button>
          <button
            class="btn-primary"
            :disabled="resolving === item.id"
            @click="approve(item.id)"
          >
            {{ resolving === item.id ? 'Saving…' : 'Approve' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.approval-inbox.compact .inbox-list {
  gap: 8px;
}

.inbox-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.inbox-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  border-radius: 10px;
  background: var(--color-brand);
  color: #fff;
  font-size: 0.7rem;
  font-weight: 600;
}

.inbox-muted {
  font-size: 0.85rem;
  color: var(--color-text-muted);
}

.inbox-error {
  font-size: 0.85rem;
  color: #dc2626;
  padding: 10px 12px;
  border-radius: 8px;
  background: rgba(239, 68, 68, 0.08);
}

.inbox-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.inbox-item {
  padding: 14px 16px;
}

.inbox-item-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.inbox-kind {
  font-size: 0.65rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--color-brand-dim);
}

.inbox-tool {
  font-size: 0.8rem;
  font-weight: 600;
  color: var(--color-text-primary);
}

.inbox-path {
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.75rem;
  color: var(--color-text-secondary);
}

.inbox-diff {
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.72rem;
  line-height: 1.5;
  color: var(--color-text-primary);
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: 6px;
  padding: 10px 12px;
  margin: 0 0 12px;
  max-height: 200px;
  overflow: auto;
  white-space: pre-wrap;
}

.inbox-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
