<script setup lang="ts">
/**
 * Labeled horizontal rule with a coloured pill — replaces card chrome.
 *
 *   <SectionRule label="BOT" tone="ok" />
 *   <SectionRule label="MCP" tone="magic" trailing="2 servers" />
 *
 * tone determines the pill color and the rule's accent.
 */
import StatusGlyph from './StatusGlyph.vue'

defineProps<{
  label: string
  glyph?: 'live' | 'live-pulse' | 'idle' | 'running' | 'warn' | 'err' | 'na' | 'attention' | 'gold' | 'data' | 'magic' | 'info'
  trailing?: string
  /** Color tone for the pill. Defaults to neutral gray. */
  tone?: 'ok' | 'warn' | 'err' | 'info' | 'data' | 'magic' | 'gold' | 'gray'
}>()
</script>

<template>
  <div class="section-rule">
    <span :class="['section-rule__pill', `pill--${tone || 'gray'}`]">{{ label }}</span>
    <StatusGlyph v-if="glyph" :kind="glyph" size="sm" />
    <span class="section-rule__line" />
    <span v-if="trailing" class="section-rule__trailing">{{ trailing }}</span>
  </div>
</template>

<style scoped>
.section-rule__pill {
  display: inline-block;
  padding: 3px 10px;
  font-size: 0.6875rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.10em;
  border-radius: 3px;
  flex-shrink: 0;
}
.pill--gray   { background: var(--paper-2);   color: var(--graphite); border: 1px solid var(--paper-3); }
.pill--ok     { background: var(--ok-soft);   color: var(--ok); }
.pill--warn   { background: var(--warn-soft); color: var(--warn); }
.pill--err    { background: var(--err-soft);  color: var(--err); }
.pill--info   { background: var(--info-soft); color: var(--info); }
.pill--data   { background: var(--data-soft); color: var(--data); }
.pill--magic  { background: var(--magic-soft);color: var(--magic); }
.pill--gold   { background: var(--gold-soft); color: var(--gold-dim); }

.section-rule__trailing {
  font-size: 0.75rem;
  color: var(--graphite-2);
  letter-spacing: 0.04em;
}
</style>
