<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useStatusStore } from '@/stores/status'
import { useGwsStatus } from '@/composables/useGwsStatus'

// A visual mirror of `./run.sh doctor` for the console: shows what's wired up
// and what still needs attention, with a one-click action per gap. Renders only
// while something is incomplete (collapses to nothing once all green), so it
// guides first-run setup without nagging a configured install.
const status = useStatusStore()
const router = useRouter()

// Shared, polled+retried Google status (same source as the header) so the two
// never disagree and a slow initial fetch self-heals instead of sticking.
const { status: gws } = useGwsStatus()

type CheckState = 'complete' | 'skipped' | 'attention'
type Check = { label: string; state: CheckState; hint: string; action?: { label: string; to?: string; href?: string } }

const checks = computed<Check[]>(() => {
  const s = status.status
  const googleOk = !!gws.value && gws.value.installed && gws.value.authenticated
  const providerStates = Object.entries(s.capabilities ?? {})
    .filter(([id]) => id.startsWith('provider:'))
    .map(([, capability]) => capability.state)
  const modelOk = providerStates.length ? providerStates.includes('healthy') : s.model.name !== '—' && !!s.model.name
  const googleCapability = s.capabilities?.['google-workspace']
  const memoryCapability = s.capabilities?.memory
  const googleSkipped = googleCapability?.state === 'disabled'
  const memorySkipped = memoryCapability?.state === 'disabled'
  return [
    { label: 'Bot reachable', state: s.bot.running ? 'complete' : 'attention', hint: 'herald-bot is running on port 8081' },
    { label: 'Model active', state: modelOk ? 'complete' : 'attention', hint: 'At least one LLM provider is configured' },
    {
      label: 'Google connected',
      state: googleSkipped ? 'skipped' : googleOk ? 'complete' : 'attention',
      hint: googleSkipped ? 'Optional integration skipped' : gws.value && !gws.value.installed ? 'gws CLI not installed (optional)' : 'Gmail / Calendar / Drive access',
      action: googleSkipped || googleOk ? undefined : { label: 'Connect', to: '/settings' },
    },
    {
      label: 'Memory accessible',
      state: memorySkipped ? 'skipped' : memoryCapability?.state === 'healthy' ? 'complete' : 'attention',
      hint: memorySkipped ? 'Optional persistence skipped' : memoryCapability?.state === 'healthy' ? `${s.memory.entryCount} notes` : memoryCapability?.message ?? 'Memory has not been probed',
      action: memorySkipped || memoryCapability?.state === 'healthy' ? undefined : { label: 'Configure', to: '/settings' },
    },
  ]
})

const allGreen = computed(() => checks.value.every((c) => c.state !== 'attention'))
</script>

<template>
  <div v-if="!allGreen" class="setup-checklist">
    <div class="setup-title">Setup</div>
    <ul class="setup-list">
      <li v-for="c in checks" :key="c.label" class="setup-item" :class="c.state">
        <span class="setup-mark">{{ c.state === 'complete' ? '✓' : c.state === 'skipped' ? '—' : '○' }}</span>
        <span class="setup-label">{{ c.label }}</span>
        <span class="setup-hint">{{ c.hint }}</span>
        <button v-if="c.action?.to" class="setup-action" @click="router.push(c.action.to)">{{ c.action.label }}</button>
        <a v-else-if="c.action?.href" class="setup-action" :href="c.action.href" target="_blank" rel="noopener">{{ c.action.label }}</a>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.setup-checklist {
  max-width: 460px;
  margin: 16px auto 0;
  padding: 14px 16px;
  border: 1px solid var(--color-border);
  border-radius: 12px;
  background: var(--color-surface);
  text-align: left;
}
.setup-title {
  font-size: 0.7rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--color-text-muted);
  margin-bottom: 8px;
}
.setup-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.setup-item { display: flex; align-items: center; gap: 8px; font-size: 0.82rem; color: var(--color-text-primary); }
.setup-mark { width: 16px; text-align: center; color: #eab308; }
.setup-item.complete .setup-mark { color: #22c55e; }
.setup-item.skipped .setup-mark { color: var(--color-text-muted); }
.setup-label { font-weight: 500; }
.setup-hint { color: var(--color-text-muted); font-size: 0.74rem; flex: 1; }
.setup-action {
  padding: 3px 10px;
  font-size: 0.74rem;
  font-weight: 600;
  color: #fff;
  background: var(--color-brand, #2563eb);
  border: none;
  border-radius: 5px;
  cursor: pointer;
  text-decoration: none;
}
</style>
