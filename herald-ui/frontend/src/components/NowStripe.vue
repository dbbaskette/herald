<script setup lang="ts">
/**
 * Top-of-page heartbeat strip. Always-visible system signal:
 *
 *   ●  sonnet-4-5  ·  up 2d 14h  ·  $0.87 today  ·  ─ alerts
 *
 * Reads from useStatusStore. When the bot is offline, switches to a
 * single clear "OFFLINE" state with a red glyph + last-seen time
 * instead of a row of dashes.
 */
import { computed } from 'vue'
import { useStatusStore } from '@/stores/status'
import StatusGlyph from './StatusGlyph.vue'

const status = useStatusStore()

const liveKind = computed<'live-pulse' | 'err' | 'idle'>(() => {
  if (!status.status.bot.running) return 'err'
  if (!status.connected) return 'idle'
  return 'live-pulse'
})

const modelName = computed(() => status.status.model.name || '—')
const uptime    = computed(() => status.status.bot.uptime || '—')
const spend     = computed(() => status.status.model.estimatedTokenSpend || '—')
const alertText = computed(() => {
  const errs = status.status.skills.parseErrors?.length || 0
  return errs > 0 ? `${errs} alert${errs === 1 ? '' : 's'}` : '0 alerts'
})
const alertKind = computed<'attention' | 'na'>(() =>
  (status.status.skills.parseErrors?.length || 0) > 0 ? 'attention' : 'na',
)
</script>

<template>
  <!-- Offline state — a single clear chip, not a row of dashes -->
  <div v-if="!status.status.bot.running" class="now-stripe now-stripe--offline">
    <StatusGlyph kind="err" size="sm" />
    <span class="now-stripe__label">Bot offline</span>
    <span class="now-stripe__sep">·</span>
    <span class="caption">start with <code class="now-stripe__code">./run.sh bot</code></span>
  </div>

  <!-- Disconnected (bot is running but SSE dropped) -->
  <div v-else-if="!status.connected" class="now-stripe now-stripe--disconnected">
    <StatusGlyph kind="idle" size="sm" />
    <span class="now-stripe__label">Reconnecting…</span>
    <span class="now-stripe__sep">·</span>
    <span class="now-stripe__value">{{ modelName }}</span>
  </div>

  <!-- Steady-state -->
  <div v-else class="now-stripe">
    <StatusGlyph :kind="liveKind" size="sm" />
    <span class="now-stripe__model">{{ modelName }}</span>
    <span class="now-stripe__sep">·</span>
    <span class="caption">up</span>
    <span class="now-stripe__value">{{ uptime }}</span>
    <span class="now-stripe__sep">·</span>
    <span class="now-stripe__value now-stripe__spend">{{ spend }}</span>
    <span class="caption">today</span>
    <span class="now-stripe__sep">·</span>
    <StatusGlyph :kind="alertKind" size="sm" />
    <span :class="alertKind === 'attention' ? 'now-stripe__alert' : 'caption'">{{ alertText }}</span>
  </div>
</template>

<style scoped>
.now-stripe--offline {
  background: var(--err-soft);
  border-color: var(--err-border);
}
.now-stripe--offline .now-stripe__label {
  color: var(--err);
  font-weight: 600;
}
.now-stripe--disconnected .now-stripe__label {
  color: var(--graphite);
}

.now-stripe__model {
  color: var(--gold-dim);
  font-variant-numeric: tabular-nums;
  font-weight: 500;
}

.now-stripe__spend {
  color: var(--info);
  font-weight: 500;
}

.now-stripe__alert {
  color: var(--warn);
  font-weight: 500;
}

.now-stripe__code {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  background: var(--paper-2);
  border: 1px solid var(--paper-3);
  padding: 0 6px;
  border-radius: 2px;
  font-size: 0.75rem;
  color: var(--ink);
}
</style>
