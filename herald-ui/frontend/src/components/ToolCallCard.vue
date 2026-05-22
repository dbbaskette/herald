<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import type { ToolCall } from '@/stores/chat'
import { extractMemoryPath, memoryViewerRoute } from '@/utils/memoryTools'

const props = defineProps<{ call: ToolCall }>()
const router = useRouter()
const expanded = ref(false)

const memoryPath = computed(() => extractMemoryPath(props.call.name, props.call.args))

function openMemory() {
  const path = memoryPath.value
  if (!path) return
  router.push(memoryViewerRoute(path))
}

const elapsedLabel = computed(() => {
  const ms = props.call.elapsedMs ?? (Date.now() - props.call.startedAt)
  if (ms < 1000) return `${ms} ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`
  return `${Math.floor(ms / 60000)}m ${Math.floor((ms % 60000) / 1000)}s`
})

const iconClass = computed(() => {
  if (props.call.status === 'ok') return 'ok'
  if (props.call.status === 'error') return 'error'
  return 'running'
})

const headlineLabel = computed(() => props.call.args || '(no arguments)')
</script>

<template>
  <div class="tool-card" :class="iconClass">
    <button class="tool-card-head" @click="expanded = !expanded">
      <span class="tool-card-icon">
        <svg v-if="call.status === 'running'" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="8" cy="8" r="5" stroke-dasharray="6 4" />
        </svg>
        <svg v-else-if="call.status === 'ok'" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M3 8l3 3 7-7"/>
        </svg>
        <svg v-else viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M4 4l8 8M12 4l-8 8"/>
        </svg>
      </span>
      <span class="tool-card-name">{{ call.name }}</span>
      <span class="tool-card-args">{{ headlineLabel }}</span>
      <span class="tool-card-elapsed">{{ elapsedLabel }}</span>
      <svg class="tool-card-chevron" :class="{ expanded }" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.5">
        <path d="M3 4.5l3 3 3-3"/>
      </svg>
    </button>
    <div v-if="expanded" class="tool-card-body">
      <div class="tool-card-row">
        <span class="tool-card-label">Args</span>
        <code class="tool-card-code">{{ call.args || '(none)' }}</code>
      </div>
      <div v-if="call.summary" class="tool-card-row">
        <span class="tool-card-label">Result</span>
        <code class="tool-card-code">{{ call.summary }}</code>
      </div>
      <div v-if="memoryPath" class="tool-card-memory-link">
        <button type="button" class="link-memory" @click.stop="openMemory">
          Open {{ memoryPath }} in Memory →
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tool-card {
  display: flex;
  flex-direction: column;
  margin: 4px 0;
  border-radius: 7px;
  border: 1px solid var(--color-border);
  background: var(--color-surface);
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  overflow: hidden;
}

.tool-card.running {
  border-color: rgba(200, 165, 90, 0.4);
}

.tool-card.ok {
  border-color: rgba(74, 222, 128, 0.4);
}

.tool-card.error {
  border-color: rgba(239, 68, 68, 0.4);
}

.tool-card-head {
  display: grid;
  grid-template-columns: 18px auto 1fr auto 14px;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border: none;
  background: transparent;
  cursor: pointer;
  font-family: inherit;
  font-size: 0.75rem;
  color: var(--color-text-primary);
  text-align: left;
}

.tool-card-head:hover {
  background: rgba(0, 0, 0, 0.02);
}

.tool-card-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
}

.tool-card-icon svg {
  width: 13px;
  height: 13px;
}

.tool-card.running .tool-card-icon {
  color: var(--color-brand-dim);
  animation: tc-spin 1.4s linear infinite;
}

.tool-card.ok .tool-card-icon { color: #16a34a; }
.tool-card.error .tool-card-icon { color: #dc2626; }

@keyframes tc-spin {
  to { transform: rotate(360deg); }
}

.tool-card-name {
  font-weight: 600;
  color: var(--color-brand-dim);
}

.tool-card-args {
  color: var(--color-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-card-elapsed {
  color: var(--color-text-muted);
  font-size: 0.7rem;
}

.tool-card-chevron {
  width: 10px;
  height: 10px;
  transition: transform 0.15s;
  color: var(--color-text-muted);
}

.tool-card-chevron.expanded {
  transform: rotate(180deg);
}

.tool-card-body {
  border-top: 1px solid var(--color-border);
  padding: 6px 10px 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  background: var(--color-surface-raised);
}

.tool-card-row {
  display: grid;
  grid-template-columns: 44px 1fr;
  gap: 8px;
  align-items: start;
}

.tool-card-label {
  font-size: 0.65rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--color-text-muted);
  padding-top: 2px;
}

.tool-card-code {
  font-family: inherit;
  font-size: 0.7rem;
  color: var(--color-text-primary);
  white-space: pre-wrap;
  word-break: break-word;
  background: transparent;
  padding: 0;
  display: block;
}

.tool-card-memory-link {
  margin-top: 6px;
}
</style>
