<script setup lang="ts">
/**
 * Page header: serif title on the left, mono path/state on the right,
 * 1px paper-rule beneath.
 *
 *   <PageHeader title="Status" path="/status">
 *     <template #right>
 *       <StatusGlyph kind="live-pulse" /> Live
 *     </template>
 *   </PageHeader>
 */
defineProps<{ title: string; path?: string }>()
</script>

<template>
  <header class="page-header">
    <h1 class="display-title">{{ title }}</h1>
    <div class="header-right">
      <slot name="right">
        <span v-if="path" class="page-path">{{ path }}</span>
      </slot>
    </div>
  </header>
</template>

<style scoped>
.header-right {
  display: flex;
  align-items: baseline;
  gap: 10px;
  color: var(--graphite-2);
  font-size: 0.6875rem;
  letter-spacing: 0.05em;
}
</style>
