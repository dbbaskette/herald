<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
const props = defineProps<{ open: boolean; busy: boolean }>()
const emit = defineEmits<{ choose: [choice: 'save' | 'discard' | 'stay'] }>()
const dialog = ref<HTMLDialogElement>()
let previous: HTMLElement | null = null
watch(() => props.open, async open => {
  await nextTick()
  if (open) { previous = document.activeElement as HTMLElement; dialog.value?.showModal() }
  else { dialog.value?.close(); previous?.focus() }
})
</script>
<template>
  <dialog v-if="open" ref="dialog" aria-labelledby="draft-title" aria-describedby="draft-description" @cancel.prevent="emit('choose', 'stay')">
    <h2 id="draft-title">Unsaved changes</h2>
    <p id="draft-description">Save your draft before continuing, discard it, or stay here to keep editing.</p>
    <div class="actions">
      <button :disabled="busy" @click="emit('choose', 'save')">{{ busy ? 'Saving…' : 'Save' }}</button>
      <button :disabled="busy" @click="emit('choose', 'discard')">Discard</button>
      <button autofocus :disabled="busy" @click="emit('choose', 'stay')">Stay</button>
    </div>
  </dialog>
</template>
<style scoped>
dialog { margin: auto; max-width: 440px; padding: 24px; border: 1px solid #777; border-radius: 12px; background: #1c1f26; color: #e8e1d3; }
dialog::backdrop { background: #0009; }
h2 { font-size: 1.1rem; margin-bottom: 12px; }
p { line-height: 1.5; }
.actions { display: flex; justify-content: flex-end; gap: 12px; margin-top: 20px; }
button { border: 1px solid #777; border-radius: 5px; padding: 6px 12px; }
</style>
