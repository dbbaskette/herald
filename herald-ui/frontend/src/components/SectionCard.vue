<script setup lang="ts">
/**
 * Containerised section — replaces the v2 flat SectionRule pattern.
 *
 * White card on gray canvas by default; tone variants tint the header strip
 * and give a soft colored border.
 *
 *   <SectionCard label="Bot" tone="ok" glyph="live">
 *     <MetricRow label="Status" tone="ok">Running</MetricRow>
 *     <MetricRow label="PID" :value="43221" />
 *   </SectionCard>
 *
 * tone:  gray (default) | ok | warn | err | info | data | magic | gold
 * glyph: optional status glyph kind rendered next to the label
 * trailing: small right-aligned text (e.g. "2 servers", "47 jobs")
 */
import StatusGlyph from './StatusGlyph.vue'

defineProps<{
  label: string
  tone?: 'gray' | 'ok' | 'warn' | 'err' | 'info' | 'data' | 'magic' | 'gold'
  glyph?: 'live' | 'live-pulse' | 'idle' | 'running' | 'warn' | 'err' | 'na' | 'attention' | 'gold' | 'data' | 'magic' | 'info'
  trailing?: string
}>()
</script>

<template>
  <section :class="['section-card', tone && tone !== 'gray' ? `section-card--${tone}` : '']">
    <header class="section-card__head">
      <span class="section-card__label">{{ label }}</span>
      <StatusGlyph v-if="glyph" :kind="glyph" size="sm" />
      <span class="section-card__line" />
      <span v-if="trailing" class="section-card__trailing">{{ trailing }}</span>
    </header>
    <div class="section-card__body">
      <slot />
    </div>
  </section>
</template>
