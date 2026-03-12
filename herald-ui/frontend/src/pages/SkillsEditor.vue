<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { useSkillsStore } from '@/stores/skills'
import { EditorView, keymap, lineNumbers, highlightActiveLine } from '@codemirror/view'
import { EditorState } from '@codemirror/state'
import { markdown } from '@codemirror/lang-markdown'
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { syntaxHighlighting, HighlightStyle } from '@codemirror/language'
import { tags } from '@lezer/highlight'

const store = useSkillsStore()

const editorContainer = ref<HTMLElement | null>(null)
let editorView: EditorView | null = null

const showNewModal = ref(false)
const newSkillName = ref('')
const newSkillError = ref('')
const confirmingDelete = ref(false)
const saveFlash = ref(false)

const sseStatus = ref<'connected' | 'disconnected' | 'error'>('disconnected')
const lastLoaded = ref<string | null>(null)
let eventSource: EventSource | null = null

// Warm syntax theme for dark editor
const darkroomHighlight = HighlightStyle.define([
  { tag: tags.heading, color: '#e2b563', fontWeight: '600' },
  { tag: tags.emphasis, color: '#c9a0dc', fontStyle: 'italic' },
  { tag: tags.strong, color: '#e8e1d3', fontWeight: '700' },
  { tag: tags.keyword, color: '#e2b563' },
  { tag: tags.atom, color: '#d19a66' },
  { tag: tags.string, color: '#8fbf7f' },
  { tag: tags.comment, color: '#6b7280', fontStyle: 'italic' },
  { tag: tags.link, color: '#6ea8d7', textDecoration: 'underline' },
  { tag: tags.url, color: '#6ea8d7' },
  { tag: tags.meta, color: '#d19a66' },
  { tag: tags.content, color: '#d4cdc4' },
  { tag: tags.processingInstruction, color: '#e2b563' },
])

function createEditor() {
  if (!editorContainer.value) return

  const updateListener = EditorView.updateListener.of((update) => {
    if (update.docChanged) {
      store.editorContent = update.state.doc.toString()
    }
  })

  const state = EditorState.create({
    doc: store.editorContent,
    extensions: [
      lineNumbers(),
      highlightActiveLine(),
      history(),
      keymap.of([...defaultKeymap, ...historyKeymap]),
      markdown(),
      syntaxHighlighting(darkroomHighlight),
      updateListener,
      EditorView.theme({
        '&': {
          height: '100%',
          fontSize: '13.5px',
          backgroundColor: '#1c1f26',
        },
        '.cm-scroller': {
          overflow: 'auto',
          fontFamily: "'JetBrains Mono', 'Fira Code', ui-monospace, SFMono-Regular, monospace",
          lineHeight: '1.7',
        },
        '.cm-content': {
          padding: '16px 0',
          caretColor: '#e2b563',
          color: '#d4cdc4',
        },
        '.cm-cursor': {
          borderLeftColor: '#e2b563',
          borderLeftWidth: '2px',
        },
        '.cm-gutters': {
          backgroundColor: '#161920',
          borderRight: '1px solid #2a2d37',
          color: '#4a4f5c',
          fontFamily: "'JetBrains Mono', monospace",
          fontSize: '12px',
          minWidth: '48px',
        },
        '.cm-activeLineGutter': {
          backgroundColor: '#1c1f26',
          color: '#8b8e96',
        },
        '.cm-activeLine': {
          backgroundColor: '#22252e',
        },
        '.cm-selectionBackground': {
          backgroundColor: '#2e3a50 !important',
        },
        '&.cm-focused .cm-selectionBackground': {
          backgroundColor: '#2e3a50 !important',
        },
        '.cm-line': {
          padding: '0 16px',
        },
      }),
      EditorState.readOnly.of(false),
    ],
  })

  editorView = new EditorView({ state, parent: editorContainer.value })
}

function syncEditorContent(content: string) {
  if (!editorView) return
  const current = editorView.state.doc.toString()
  if (current !== content) {
    editorView.dispatch({
      changes: { from: 0, to: editorView.state.doc.length, insert: content },
    })
  }
}

watch(() => store.savedContent, (val) => {
  syncEditorContent(val)
})

watch(() => store.selectedName, (name) => {
  if (name) {
    if (editorView) {
      syncEditorContent(store.editorContent)
    } else {
      createEditor()
    }
  } else {
    editorView?.destroy()
    editorView = null
  }
}, { flush: 'post' })

onMounted(async () => {
  await store.fetchSkills()
  connectSSE()
})

onUnmounted(() => {
  editorView?.destroy()
  disconnectSSE()
})

function connectSSE() {
  disconnectSSE()
  eventSource = new EventSource('/api/status/stream')
  eventSource.onopen = () => { sseStatus.value = 'connected' }
  eventSource.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data)
      sseStatus.value = 'connected'
      if (data.timestamp) lastLoaded.value = data.timestamp
      if (data.skills?.lastReload) lastLoaded.value = data.skills.lastReload
    } catch { /* ignore */ }
  }
  eventSource.onerror = () => {
    sseStatus.value = 'error'
    eventSource?.close()
    setTimeout(() => connectSSE(), 5000)
  }
}

function disconnectSSE() {
  if (eventSource) {
    eventSource.close()
    eventSource = null
    sseStatus.value = 'disconnected'
  }
}

async function handleSave() {
  const ok = await store.saveSkill()
  if (ok) {
    syncEditorContent(store.editorContent)
    saveFlash.value = true
    setTimeout(() => saveFlash.value = false, 1200)
  }
}

function handleDiscard() {
  store.discardChanges()
  syncEditorContent(store.savedContent)
}

async function openNewSkillModal() {
  newSkillName.value = ''
  newSkillError.value = ''
  showNewModal.value = true
  await nextTick()
  const input = document.querySelector('.modal-name-input') as HTMLInputElement
  input?.focus()
}

async function handleCreateSkill() {
  const name = newSkillName.value.trim()
  if (!name) return
  if (!/^[a-zA-Z0-9_-]+$/.test(name)) {
    newSkillError.value = 'Only letters, numbers, hyphens, and underscores'
    return
  }
  const ok = await store.createSkill(name)
  if (ok) {
    showNewModal.value = false
    await nextTick()
    syncEditorContent(store.editorContent)
  } else {
    newSkillError.value = store.error || 'Failed to create skill'
  }
}

async function handleDelete() {
  if (!store.selectedName) return
  const ok = await store.deleteSkill(store.selectedName)
  if (ok) {
    confirmingDelete.value = false
    syncEditorContent('')
  }
}

function formatTime(ts: string | null): string {
  if (!ts) return ''
  try {
    const d = new Date(ts)
    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  } catch { return ts }
}
</script>

<template>
  <div class="skills-page">
    <!-- Header bar -->
    <header class="skills-header">
      <div class="header-left">
        <h1 class="page-title">Skills</h1>
        <span class="skill-count" v-if="store.skillNames.length">{{ store.skillNames.length }} files</span>
      </div>
      <div class="header-right">
        <div class="sse-chip" :class="'sse-' + sseStatus">
          <span class="sse-dot"></span>
          <span class="sse-label">{{ sseStatus === 'connected' ? 'Live' : sseStatus === 'error' ? 'Error' : 'Offline' }}</span>
          <span v-if="lastLoaded && sseStatus === 'connected'" class="sse-time">{{ formatTime(lastLoaded) }}</span>
        </div>
      </div>
    </header>

    <!-- Error banner -->
    <div v-if="store.error" class="error-banner">
      <svg class="error-icon" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" clip-rule="evenodd"/></svg>
      <span>{{ store.error }}</span>
      <button class="error-dismiss" @click="store.error = null">Dismiss</button>
    </div>

    <!-- Main layout -->
    <div class="skills-layout">
      <!-- File tree sidebar -->
      <aside class="file-tree">
        <div class="tree-header">
          <span class="tree-label">FILES</span>
          <button class="new-btn" @click="openNewSkillModal()" title="New skill">
            <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><line x1="8" y1="3" x2="8" y2="13"/><line x1="3" y1="8" x2="13" y2="8"/></svg>
          </button>
        </div>
        <div v-if="store.loading && store.skillNames.length === 0" class="tree-loading">
          <span class="loading-dot"></span>
          <span class="loading-dot"></span>
          <span class="loading-dot"></span>
        </div>
        <div v-else class="tree-list">
          <button
            v-for="skill in store.skills"
            :key="skill.name"
            class="tree-item"
            :class="{ active: store.selectedName === skill.name }"
            :title="skill.description || skill.name"
            @click="store.selectSkill(skill.name)"
          >
            <svg class="file-icon" viewBox="0 0 16 16" fill="currentColor"><path d="M3 1h7l3 3v9a2 2 0 01-2 2H5a2 2 0 01-2-2V3a2 2 0 012-2z" opacity="0.15"/><path d="M3 1h7l3 3v9a2 2 0 01-2 2H5a2 2 0 01-2-2V3a2 2 0 012-2z" fill="none" stroke="currentColor" stroke-width="1"/></svg>
            <span class="tree-name">{{ skill.name }}</span>
            <span class="tree-ext">.md</span>
            <span v-if="skill.readOnly" class="tree-badge">bundled</span>
          </button>
          <div v-if="store.skillNames.length === 0" class="tree-empty">
            No skills yet
          </div>
        </div>
      </aside>

      <!-- Editor panel -->
      <div class="editor-panel" :class="{ 'save-flash': saveFlash }">
        <!-- Editor toolbar -->
        <div v-if="store.selectedName" class="editor-toolbar">
          <div class="toolbar-left">
            <span class="toolbar-filename">{{ store.selectedName }}<span class="toolbar-ext">.md</span></span>
            <transition name="dot-fade">
              <span v-if="store.isDirty" class="dirty-indicator" title="Unsaved changes"></span>
            </transition>
          </div>
          <div class="toolbar-actions">
            <button
              class="action-btn action-save"
              :disabled="!store.isDirty || store.saving"
              @click="handleSave()"
            >
              <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M13 5.5V13a1 1 0 01-1 1H4a1 1 0 01-1-1V3a1 1 0 011-1h6.5L13 5.5z"/><path d="M5 14v-4h6v4"/><path d="M10 2v3"/></svg>
              {{ store.saving ? 'Saving' : 'Save' }}
            </button>
            <button
              class="action-btn action-discard"
              :disabled="!store.isDirty"
              @click="handleDiscard()"
            >
              Discard
            </button>
            <div class="toolbar-separator"></div>
            <button
              class="action-btn action-delete"
              @click="confirmingDelete = true"
            >
              <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M3 4h10M6 4V3a1 1 0 011-1h2a1 1 0 011 1v1m2 0v8a2 2 0 01-2 2H6a2 2 0 01-2-2V4h8z"/></svg>
            </button>
          </div>
        </div>

        <!-- CodeMirror editor -->
        <div v-if="store.selectedName" ref="editorContainer" class="editor-container"></div>

        <!-- Empty state -->
        <div v-else class="editor-empty">
          <div class="empty-visual">
            <svg viewBox="0 0 80 80" fill="none" xmlns="http://www.w3.org/2000/svg">
              <rect x="16" y="8" width="48" height="64" rx="4" stroke="currentColor" stroke-width="1.5" opacity="0.2"/>
              <path d="M28 28h24M28 36h18M28 44h20" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" opacity="0.15"/>
              <circle cx="58" cy="58" r="14" stroke="currentColor" stroke-width="1.5" opacity="0.3"/>
              <path d="M54 58h8M58 54v8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" opacity="0.3"/>
            </svg>
          </div>
          <p class="empty-text">Select a skill or create a new one</p>
          <button class="empty-action" @click="openNewSkillModal()">
            <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><line x1="8" y1="3" x2="8" y2="13"/><line x1="3" y1="8" x2="13" y2="8"/></svg>
            New Skill
          </button>
        </div>
      </div>
    </div>

    <!-- New Skill Modal -->
    <Transition name="modal">
      <div v-if="showNewModal" class="modal-overlay" @click.self="showNewModal = false">
        <div class="modal-card">
          <div class="modal-header">
            <h2>Create Skill</h2>
          </div>
          <div class="modal-body">
            <label class="modal-label">Skill Name</label>
            <div class="modal-input-wrap">
              <input
                v-model="newSkillName"
                type="text"
                placeholder="my-skill"
                class="modal-name-input"
                @keydown.enter="handleCreateSkill()"
                @keydown.escape="showNewModal = false"
              />
              <span class="modal-ext">.md</span>
            </div>
            <p v-if="newSkillError" class="modal-error">{{ newSkillError }}</p>
            <p class="modal-hint">Letters, numbers, hyphens, underscores only</p>
          </div>
          <div class="modal-footer">
            <button class="action-btn action-discard" @click="showNewModal = false">Cancel</button>
            <button
              class="action-btn action-save"
              :disabled="!newSkillName.trim()"
              @click="handleCreateSkill()"
            >Create</button>
          </div>
        </div>
      </div>
    </Transition>

    <!-- Delete Confirmation Modal -->
    <Transition name="modal">
      <div v-if="confirmingDelete" class="modal-overlay" @click.self="confirmingDelete = false">
        <div class="modal-card modal-card-delete">
          <div class="modal-header">
            <h2>Delete Skill</h2>
          </div>
          <div class="modal-body">
            <p class="modal-message">
              Remove <code>{{ store.selectedName }}.md</code> permanently?
            </p>
          </div>
          <div class="modal-footer">
            <button class="action-btn action-discard" @click="confirmingDelete = false">Cancel</button>
            <button class="action-btn action-danger" @click="handleDelete()">Delete</button>
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
@import url('https://fonts.googleapis.com/css2?family=DM+Sans:ital,opsz,wght@0,9..40,300;0,9..40,500;0,9..40,600;1,9..40,400&family=JetBrains+Mono:wght@400;500&display=swap');

/* ═══════════════════════════════════
   Page Layout
   ═══════════════════════════════════ */

.skills-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  font-family: 'DM Sans', system-ui, sans-serif;
  gap: 12px;
}

/* ═══════════════════════════════════
   Header
   ═══════════════════════════════════ */

.skills-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.header-left {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.page-title {
  font-size: 1.5rem;
  font-weight: 600;
  color: #1a1a1a;
  letter-spacing: -0.02em;
}

.skill-count {
  font-size: 0.75rem;
  color: #9ca3af;
  font-weight: 500;
  letter-spacing: 0.02em;
}

/* SSE Status Chip */
.sse-chip {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px 4px 8px;
  border-radius: 100px;
  font-size: 0.7rem;
  font-weight: 500;
  letter-spacing: 0.03em;
  text-transform: uppercase;
  transition: all 0.3s ease;
}

.sse-connected {
  background: #0c1f0c;
  color: #6ee67a;
}

.sse-disconnected {
  background: #f3f4f6;
  color: #9ca3af;
}

.sse-error {
  background: #2a0f0f;
  color: #f87171;
}

.sse-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}

.sse-connected .sse-dot {
  background: #4ade80;
  box-shadow: 0 0 6px #4ade80;
  animation: pulse-green 2s ease-in-out infinite;
}

.sse-disconnected .sse-dot {
  background: #d1d5db;
}

.sse-error .sse-dot {
  background: #f87171;
  box-shadow: 0 0 6px #f87171;
}

.sse-time {
  opacity: 0.6;
  font-variant-numeric: tabular-nums;
}

@keyframes pulse-green {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

/* ═══════════════════════════════════
   Error Banner
   ═══════════════════════════════════ */

.error-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 8px;
  font-size: 0.8125rem;
  color: #b91c1c;
}

.error-icon {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
  color: #ef4444;
}

.error-dismiss {
  margin-left: auto;
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  opacity: 0.6;
  cursor: pointer;
  background: none;
  border: none;
  color: inherit;
}

.error-dismiss:hover {
  opacity: 1;
}

/* ═══════════════════════════════════
   Main Layout
   ═══════════════════════════════════ */

.skills-layout {
  display: flex;
  flex: 1;
  gap: 0;
  min-height: 0;
  border-radius: 10px;
  overflow: hidden;
  box-shadow:
    0 1px 3px rgba(0,0,0,0.08),
    0 8px 24px rgba(0,0,0,0.06);
}

/* ═══════════════════════════════════
   File Tree Sidebar
   ═══════════════════════════════════ */

.file-tree {
  width: 260px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: #f8f7f5;
  border-right: 1px solid #e8e5df;
}

.tree-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-bottom: 1px solid #e8e5df;
}

.tree-label {
  font-size: 0.65rem;
  font-weight: 600;
  letter-spacing: 0.1em;
  color: #a09888;
  text-transform: uppercase;
}

.new-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 5px;
  border: none;
  background: transparent;
  color: #a09888;
  cursor: pointer;
  transition: all 0.15s ease;
}

.new-btn svg {
  width: 14px;
  height: 14px;
}

.new-btn:hover {
  background: #e8e5df;
  color: #5a5040;
}

.tree-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 24px;
}

.loading-dot {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: #c0b8a8;
  animation: dot-bounce 0.8s ease-in-out infinite;
}

.loading-dot:nth-child(2) { animation-delay: 0.1s; }
.loading-dot:nth-child(3) { animation-delay: 0.2s; }

@keyframes dot-bounce {
  0%, 100% { opacity: 0.3; transform: translateY(0); }
  50% { opacity: 1; transform: translateY(-3px); }
}

.tree-list {
  flex: 1;
  overflow-y: auto;
  padding: 4px 0;
}

.tree-item {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 5px 12px;
  border: none;
  background: transparent;
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.75rem;
  color: #6b6355;
  cursor: pointer;
  transition: all 0.12s ease;
  text-align: left;
}

.tree-item:hover {
  background: #efece6;
  color: #3d3528;
}

.tree-item.active {
  background: #1c1f26;
  color: #e2b563;
}

.file-icon {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
  opacity: 0.5;
}

.tree-item.active .file-icon {
  opacity: 0.8;
}

.tree-name {
  flex: 1;
  word-break: break-all;
}

.tree-ext {
  opacity: 0.4;
  font-size: 0.65rem;
  flex-shrink: 0;
}

.tree-badge {
  font-size: 0.55rem;
  font-family: 'DM Sans', sans-serif;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  padding: 1px 5px;
  border-radius: 3px;
  background: rgba(226, 181, 99, 0.12);
  color: #c9a050;
  flex-shrink: 0;
}

.tree-item.active .tree-badge {
  background: rgba(226, 181, 99, 0.2);
  color: #e2b563;
}

.tree-empty {
  padding: 20px;
  text-align: center;
  font-size: 0.75rem;
  color: #b0a898;
}

/* ═══════════════════════════════════
   Editor Panel
   ═══════════════════════════════════ */

.editor-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: #1c1f26;
  position: relative;
  transition: box-shadow 0.6s ease;
}

.editor-panel.save-flash::after {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  background: rgba(226, 181, 99, 0.06);
  animation: flash-fade 1.2s ease-out forwards;
  z-index: 5;
}

@keyframes flash-fade {
  0% { opacity: 1; }
  100% { opacity: 0; }
}

/* Editor Toolbar */
.editor-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 12px;
  height: 40px;
  background: #161920;
  border-bottom: 1px solid #2a2d37;
  flex-shrink: 0;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.toolbar-filename {
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.8rem;
  color: #9ca3af;
  font-weight: 500;
}

.toolbar-ext {
  opacity: 0.4;
}

.dirty-indicator {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #e2b563;
  box-shadow: 0 0 6px rgba(226, 181, 99, 0.4);
  display: inline-block;
}

.dot-fade-enter-active { animation: dot-in 0.2s ease; }
.dot-fade-leave-active { animation: dot-in 0.2s ease reverse; }
@keyframes dot-in {
  from { transform: scale(0); opacity: 0; }
  to { transform: scale(1); opacity: 1; }
}

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.toolbar-separator {
  width: 1px;
  height: 18px;
  background: #2a2d37;
  margin: 0 4px;
}

/* Action buttons */
.action-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 10px;
  border-radius: 5px;
  font-family: 'DM Sans', sans-serif;
  font-size: 0.75rem;
  font-weight: 500;
  border: none;
  cursor: pointer;
  transition: all 0.15s ease;
  white-space: nowrap;
}

.action-btn svg {
  width: 14px;
  height: 14px;
}

.action-btn:disabled {
  opacity: 0.3;
  cursor: not-allowed;
}

.action-save {
  background: #e2b563;
  color: #1a1500;
}

.action-save:hover:not(:disabled) {
  background: #edc474;
  box-shadow: 0 0 12px rgba(226, 181, 99, 0.25);
}

.action-discard {
  background: #2a2d37;
  color: #9ca3af;
}

.action-discard:hover:not(:disabled) {
  background: #353944;
  color: #d1d5db;
}

.action-delete {
  background: transparent;
  color: #6b7280;
  padding: 4px 6px;
}

.action-delete:hover {
  color: #ef4444;
  background: rgba(239, 68, 68, 0.1);
}

.action-danger {
  background: #dc2626;
  color: white;
}

.action-danger:hover {
  background: #ef4444;
}

/* Editor Container */
.editor-container {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

/* ═══════════════════════════════════
   Empty State
   ═══════════════════════════════════ */

.editor-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
}

.empty-visual {
  color: #4a4f5c;
  width: 80px;
  height: 80px;
}

.empty-text {
  font-size: 0.8125rem;
  color: #4a4f5c;
}

.empty-action {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  border-radius: 6px;
  border: 1px dashed #3a3d47;
  background: transparent;
  color: #7a7f8c;
  font-family: 'DM Sans', sans-serif;
  font-size: 0.8rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
}

.empty-action svg {
  width: 14px;
  height: 14px;
}

.empty-action:hover {
  border-color: #e2b563;
  color: #e2b563;
  background: rgba(226, 181, 99, 0.05);
}

/* ═══════════════════════════════════
   Modals
   ═══════════════════════════════════ */

.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 50;
}

.modal-card {
  background: #1c1f26;
  border: 1px solid #2a2d37;
  border-radius: 12px;
  width: 380px;
  overflow: hidden;
  box-shadow: 0 24px 48px rgba(0, 0, 0, 0.3);
}

.modal-header {
  padding: 16px 20px 0;
}

.modal-header h2 {
  font-family: 'DM Sans', sans-serif;
  font-size: 1rem;
  font-weight: 600;
  color: #e8e1d3;
  letter-spacing: -0.01em;
}

.modal-body {
  padding: 16px 20px;
}

.modal-label {
  display: block;
  font-size: 0.7rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: #6b7280;
  margin-bottom: 8px;
}

.modal-input-wrap {
  display: flex;
  align-items: center;
  background: #161920;
  border: 1px solid #2a2d37;
  border-radius: 6px;
  overflow: hidden;
  transition: border-color 0.15s ease;
}

.modal-input-wrap:focus-within {
  border-color: #e2b563;
  box-shadow: 0 0 0 2px rgba(226, 181, 99, 0.15);
}

.modal-name-input {
  flex: 1;
  padding: 8px 10px;
  background: transparent;
  border: none;
  outline: none;
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.8125rem;
  color: #e8e1d3;
}

.modal-name-input::placeholder {
  color: #4a4f5c;
}

.modal-ext {
  padding: 0 10px;
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.75rem;
  color: #4a4f5c;
}

.modal-error {
  margin-top: 8px;
  font-size: 0.75rem;
  color: #f87171;
}

.modal-hint {
  margin-top: 6px;
  font-size: 0.7rem;
  color: #4a4f5c;
}

.modal-message {
  font-size: 0.875rem;
  color: #9ca3af;
  line-height: 1.5;
}

.modal-message code {
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.8125rem;
  color: #e8e1d3;
  background: #161920;
  padding: 2px 6px;
  border-radius: 3px;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 20px 16px;
}

/* Modal transitions */
.modal-enter-active {
  transition: opacity 0.15s ease;
}
.modal-leave-active {
  transition: opacity 0.1s ease;
}
.modal-enter-from, .modal-leave-to {
  opacity: 0;
}
.modal-enter-active .modal-card {
  animation: modal-in 0.2s ease;
}
@keyframes modal-in {
  from { transform: translateY(8px) scale(0.98); opacity: 0; }
  to { transform: translateY(0) scale(1); opacity: 1; }
}
</style>
