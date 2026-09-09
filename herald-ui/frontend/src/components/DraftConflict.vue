<script setup lang="ts">
import DiffEditor from './DiffEditor.vue'
defineProps<{ latest: string; draft: string }>()
defineEmits<{ latest: []; reconcile: []; edit: [content: string] }>()
</script>
<template>
  <section class="conflict" aria-label="External edit conflict">
    <h2>This file changed externally</h2>
    <p>Compare the latest file (left) with your draft (right). Edit the draft to merge changes, then keep it for an explicit save, or discard it and use the latest file.</p>
    <DiffEditor class="comparison" :original="latest" :modified="draft" @update:modified="$emit('edit', $event)" />
    <button @click="$emit('latest')">Discard draft and use latest</button>
    <button @click="$emit('reconcile')">Keep draft against latest</button>
  </section>
</template>
<style scoped>
.conflict { padding: 12px; border: 2px solid #b7791f; background: #fffbeb; color: #333; }
.comparison { height: 260px; margin: 12px 0; }
button { padding: 6px 10px; margin-right: 12px; border: 1px solid #999; border-radius: 4px; }
</style>
