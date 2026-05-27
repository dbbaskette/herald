<script setup lang="ts">
/**
 * Terminal-style key·value row. Label on left, value on right, both monospace.
 *
 *   <MetricRow label="PID" :value="43221" />
 *   <MetricRow label="Status" value="Running" tone="ok">
 *     <template #leading><StatusGlyph kind="live" /></template>
 *   </MetricRow>
 */
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    label: string
    value?: string | number | null
    tone?: 'default' | 'muted' | 'ok' | 'warn' | 'err' | 'gold' | 'info' | 'data' | 'magic'
  }>(),
  { tone: 'default' },
)

const valueClass = computed(() =>
  props.tone === 'default'
    ? 'metric-row__value'
    : `metric-row__value metric-row__value--${props.tone}`,
)

const displayValue = computed(() => {
  if (props.value === null || props.value === undefined || props.value === '') return '—'
  return props.value
})
</script>

<template>
  <div class="metric-row">
    <div class="metric-row__label">{{ label }}</div>
    <div :class="valueClass">
      <slot name="leading" />
      <slot>{{ displayValue }}</slot>
    </div>
  </div>
</template>

<style scoped>
.metric-row__value {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
</style>
