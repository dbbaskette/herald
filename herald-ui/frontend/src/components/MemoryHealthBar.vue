<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

// Surfaces where memory actually resolves on disk, how many notes exist, and
// when the last write happened. The resolved path being visible is the point —
// a misconfigured/escaped vault path (which previously failed silently) is
// obvious here the moment you load the page.
interface Health {
  path: string
  exists: boolean
  noteCount: number
  lastWrite: string | null
  hasIndex: boolean
}

const health = ref<Health | null>(null)

async function load() {
  try {
    const res = await fetch('/api/memory/files/health')
    if (res.ok) health.value = await res.json()
  } catch {
    health.value = null
  }
}
onMounted(load)

const lastWriteLabel = computed(() => {
  const ts = health.value?.lastWrite
  if (!ts) return 'never'
  const then = new Date(ts).getTime()
  const diff = Date.now() - then
  if (diff < 60_000) return 'just now'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`
  return `${Math.floor(diff / 86_400_000)}d ago`
})

const tone = computed(() => {
  if (!health.value) return 'off'
  if (!health.value.exists) return 'error'
  if (!health.value.hasIndex || health.value.noteCount === 0) return 'warn'
  return 'ok'
})
</script>

<template>
  <div v-if="health" class="mem-health" :class="tone">
    <span class="mem-dot"></span>
    <span class="mem-stat"><strong>{{ health.noteCount }}</strong> notes</span>
    <span class="mem-sep">·</span>
    <span class="mem-stat">index {{ health.hasIndex ? '✓' : 'missing' }}</span>
    <span class="mem-sep">·</span>
    <span class="mem-stat">last write {{ lastWriteLabel }}</span>
    <span class="mem-sep">·</span>
    <code class="mem-path" :title="health.path">{{ health.path }}</code>
    <span v-if="!health.exists" class="mem-warn">— directory does not exist</span>
    <button class="mem-refresh" title="Refresh" @click="load">↻</button>
  </div>
</template>

<style scoped>
.mem-health {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  font-size: 0.76rem;
  color: var(--color-text-muted);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.mem-stat strong { color: var(--color-text-primary); }
.mem-sep { opacity: 0.4; }
.mem-path {
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.7rem;
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 460px;
  white-space: nowrap;
}
.mem-warn { color: #dc2626; font-weight: 600; }
.mem-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.mem-health.ok .mem-dot { background: #22c55e; }
.mem-health.warn .mem-dot { background: #eab308; }
.mem-health.error .mem-dot { background: #dc2626; }
.mem-health.off .mem-dot { background: #9ca3af; }
.mem-refresh {
  margin-left: auto;
  border: none;
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  font-size: 0.9rem;
}
</style>
