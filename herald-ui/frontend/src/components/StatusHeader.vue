<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useStatusStore } from '@/stores/status'

// Compact health strip for the chat console: bot reachability, active model,
// Google connection, and memory note count — at a glance, so "is it offline?"
// is never a guess. Bot/model/memory come from the status store (SSE-driven);
// Google state is polled separately since it isn't in the status stream.
const status = useStatusStore()

type Tone = 'ok' | 'warn' | 'off'

const gws = ref<{ installed: boolean; authenticated: boolean; user?: string } | null>(null)
let gwsTimer: ReturnType<typeof setInterval> | null = null

async function fetchGws() {
  try {
    const res = await fetch('/api/gws/status')
    if (res.ok) gws.value = await res.json()
  } catch {
    gws.value = null
  }
}

const botTone = computed<Tone>(() => (status.status.bot.running ? 'ok' : 'off'))
const modelName = computed(() => status.status.model.name || '—')
const memoryCount = computed(() => status.status.memory.entryCount)
const requestsToday = computed(() => status.status.model.requestsToday)
const spendToday = computed(() => status.status.model.estimatedTokenSpend)

const googleTone = computed<Tone>(() => {
  if (!gws.value || !gws.value.installed) return 'off'
  return gws.value.authenticated ? 'ok' : 'warn'
})
const googleLabel = computed(() => {
  if (!gws.value || !gws.value.installed) return 'Google off'
  if (gws.value.authenticated) return gws.value.user ? `Google · ${gws.value.user}` : 'Google'
  return 'Google — connect'
})

onMounted(() => {
  fetchGws()
  gwsTimer = setInterval(fetchGws, 30_000)
})
onUnmounted(() => {
  if (gwsTimer) clearInterval(gwsTimer)
})
</script>

<template>
  <div class="status-header">
    <span class="status-seg" :title="status.status.bot.running ? 'Bot reachable' : 'Bot unreachable'">
      <span class="status-dot" :class="botTone"></span>
      Bot
    </span>
    <span class="status-sep">·</span>
    <span class="status-seg status-model" :title="`Active model: ${modelName}`">
      <span class="status-dot" :class="status.status.bot.running ? 'ok' : 'off'"></span>
      {{ modelName }}
    </span>
    <span class="status-sep">·</span>
    <span class="status-seg" :title="googleLabel">
      <span class="status-dot" :class="googleTone"></span>
      {{ googleLabel }}
    </span>
    <span class="status-sep">·</span>
    <span class="status-seg" :title="`${memoryCount} memory notes`">
      <span class="status-dot" :class="memoryCount > 0 ? 'ok' : 'warn'"></span>
      {{ memoryCount }} notes
    </span>
    <span class="status-spacer"></span>
    <span class="status-seg status-usage" :title="`${requestsToday} requests today · ${spendToday} estimated spend`">
      {{ requestsToday }} reqs · {{ spendToday }} today
    </span>
  </div>
</template>

<style scoped>
.status-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 12px;
  font-size: 0.72rem;
  color: var(--color-text-muted);
  border-bottom: 1px solid var(--color-border);
  background: var(--color-surface);
  flex-wrap: wrap;
}
.status-seg {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  white-space: nowrap;
}
.status-model { font-family: 'JetBrains Mono', monospace; color: var(--color-text-primary); }
.status-sep { opacity: 0.4; }
.status-spacer { flex: 1; }
.status-usage { font-family: 'JetBrains Mono', monospace; opacity: 0.85; }
.status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}
.status-dot.ok { background: #22c55e; }
.status-dot.warn { background: #eab308; }
.status-dot.off { background: #9ca3af; }
</style>
