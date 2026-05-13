<script setup lang="ts">
/**
 * CodeMirror 6 MergeView wrapper for skill/prompt diff editing (#363).
 *
 * - Left pane: the bundled baseline (read-only).
 * - Right pane: the user's working copy (editable).
 *
 * Emits `update:modified` with the right-pane content on every edit. Internally
 * tears down and re-creates the MergeView when `original` or the externally-set
 * `modified` value changes — MergeView doesn't expose a clean way to swap docs
 * in place, and the diff is cheap to recompute for typical skill-sized files.
 */
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { MergeView } from '@codemirror/merge'
import { EditorView, keymap, lineNumbers, highlightActiveLineGutter } from '@codemirror/view'
import { EditorState } from '@codemirror/state'
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { markdown } from '@codemirror/lang-markdown'
import { oneDark } from '@codemirror/theme-one-dark'

const props = defineProps<{
  original: string
  modified: string
  readOnly?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modified', value: string): void
}>()

const host = ref<HTMLElement | null>(null)
let merge: MergeView | null = null

function rebuild() {
  if (!host.value) return
  if (merge) {
    merge.destroy()
    merge = null
  }
  const commonExt = [
    history(),
    lineNumbers(),
    highlightActiveLineGutter(),
    keymap.of([...defaultKeymap, ...historyKeymap]),
    markdown(),
    oneDark,
    EditorView.lineWrapping,
  ]
  merge = new MergeView({
    a: {
      doc: props.original,
      extensions: [
        ...commonExt,
        EditorState.readOnly.of(true),
      ],
    },
    b: {
      doc: props.modified,
      extensions: [
        ...commonExt,
        EditorState.readOnly.of(!!props.readOnly),
        EditorView.updateListener.of((u) => {
          if (u.docChanged) emit('update:modified', u.state.doc.toString())
        }),
      ],
    },
    parent: host.value,
    collapseUnchanged: { margin: 3, minSize: 6 },
    revertControls: 'b-to-a',
    gutter: true,
  })
}

onMounted(rebuild)
onBeforeUnmount(() => merge?.destroy())

// Rebuild whenever either side's text changes from outside.
watch(() => [props.original, props.modified], (next, prev) => {
  if (!merge) { rebuild(); return }
  // If the modified value is updated externally (e.g. Load Default), rebuild
  // rather than try to splice. CodeMirror handles small docs in <1ms.
  const externalChange = !prev || next[0] !== prev[0]
    || (next[1] !== prev[1] && next[1] !== merge.b.state.doc.toString())
  if (externalChange) rebuild()
})
</script>

<template>
  <div ref="host" class="diff-editor"></div>
</template>

<style scoped>
.diff-editor {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.diff-editor :deep(.cm-merge-view) {
  height: 100%;
  flex: 1;
}

.diff-editor :deep(.cm-mergeView) {
  height: 100%;
  flex: 1;
}

.diff-editor :deep(.cm-mergeViewEditor) {
  height: 100%;
}

.diff-editor :deep(.cm-editor) {
  height: 100%;
  font-size: 13px;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
}

.diff-editor :deep(.cm-scroller) {
  font-family: inherit;
}
</style>
