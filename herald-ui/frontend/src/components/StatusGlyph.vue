<script setup lang="ts">
/**
 * Character-glyph status indicator. Replaces colored dots.
 *
 * Map:
 *   live      ●   (steady green)
 *   live-pulse●   (steady gold, pulsing — "actively streaming data")
 *   idle      ○
 *   running   ↑
 *   warn      ◐
 *   err       ✕
 *   na        ─
 *   attention ▴
 *
 * Usage:
 *   <StatusGlyph kind="live" />
 *   <StatusGlyph kind="err" :label="'Stopped'" />   ← label is sr-only, for a11y
 */
import { computed } from 'vue'

type Kind =
  | 'live'
  | 'live-pulse'
  | 'idle'
  | 'running'
  | 'warn'
  | 'err'
  | 'na'
  | 'attention'
  | 'gold'
  | 'data'
  | 'magic'
  | 'info'

const props = withDefaults(
  defineProps<{ kind: Kind; label?: string; size?: 'sm' | 'md' }>(),
  { size: 'md' },
)

const GLYPHS: Record<Kind, string> = {
  live: '●',
  'live-pulse': '●',
  idle: '○',
  running: '↑',
  warn: '◐',
  err: '✕',
  na: '─',
  attention: '▴',
  gold: '●',
  data: '●',
  magic: '●',
  info: '●',
}

const glyph = computed(() => GLYPHS[props.kind])
const cls = computed(() => `glyph glyph--${props.kind} glyph--${props.size}`)
const a11yLabel = computed(() => props.label ?? props.kind)
</script>

<template>
  <span :class="cls" role="img" :aria-label="a11yLabel">
    {{ glyph }}
    <span class="sr-only">{{ a11yLabel }}</span>
  </span>
</template>

<style scoped>
.glyph--sm { font-size: 0.6875rem; }
.glyph--md { font-size: 0.875rem; }
.sr-only {
  position: absolute;
  width: 1px; height: 1px;
  padding: 0; margin: -1px;
  overflow: hidden; clip: rect(0, 0, 0, 0);
  white-space: nowrap; border: 0;
}
</style>
