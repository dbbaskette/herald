<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import DiffEditor from '@/components/DiffEditor.vue'

interface PromptSummary {
  name: string
  displayName: string
  description: string
  source: 'user' | 'user-override' | 'bundled'
  overridden: boolean
  overridePath: string | null
  editable: boolean
}

interface PromptDetail {
  name: string
  displayName: string
  description: string
  content: string
  defaultContent: string
  source: string
  overridden: boolean
  overridePath: string
}

const prompts = ref<PromptSummary[]>([])
const selected = ref<PromptDetail | null>(null)
const editorContent = ref('')
const dirty = ref(false)
const saving = ref(false)
const saveMessage = ref('')
const errorMessage = ref('')

const restartRequired = computed(
  () => selected.value !== null && selected.value.name !== 'CONTEXT.md'
)

// Diff view (#363) — available when the selected prompt has a bundled baseline.
const diffMode = ref(false)
const diffSummary = computed(() => {
  if (!selected.value?.defaultContent) return { added: 0, removed: 0, changed: 0 }
  return computeDiffSummary(selected.value.defaultContent, editorContent.value)
})

function computeDiffSummary(a: string, b: string) {
  if (!a || !b) return { added: 0, removed: 0, changed: 0 }
  const av = a.split('\n')
  const bv = b.split('\n')
  const m = av.length, n = bv.length
  const dp: number[][] = Array.from({ length: m + 1 }, () => new Array(n + 1).fill(0))
  for (let i = 1; i <= m; i++) for (let j = 1; j <= n; j++) {
    dp[i][j] = av[i - 1] === bv[j - 1] ? dp[i - 1][j - 1] + 1 : Math.max(dp[i - 1][j], dp[i][j - 1])
  }
  const lcs = dp[m][n]
  const removed = m - lcs
  const added = n - lcs
  const changed = Math.min(added, removed)
  return { added: added - changed, removed: removed - changed, changed }
}

async function loadList() {
  try {
    const res = await fetch('/api/prompts')
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    prompts.value = await res.json()
    if (!selected.value && prompts.value.length > 0) {
      await selectPrompt(prompts.value[0].name)
    }
  } catch (e: any) {
    errorMessage.value = `Failed to load prompts: ${e.message}`
  }
}

async function selectPrompt(name: string) {
  if (dirty.value) {
    if (!confirm('Discard unsaved changes?')) return
  }
  errorMessage.value = ''
  saveMessage.value = ''
  try {
    const res = await fetch(`/api/prompts/${encodeURIComponent(name)}`)
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    selected.value = await res.json()
    editorContent.value = selected.value!.content
    dirty.value = false
  } catch (e: any) {
    errorMessage.value = `Failed to load ${name}: ${e.message}`
  }
}

function onContentChange() {
  dirty.value = selected.value !== null && editorContent.value !== selected.value.content
}

async function save() {
  if (!selected.value) return
  saving.value = true
  errorMessage.value = ''
  saveMessage.value = ''
  try {
    const res = await fetch(`/api/prompts/${encodeURIComponent(selected.value.name)}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: editorContent.value }),
    })
    const result = await res.json()
    if (!res.ok || result.saved === false) {
      throw new Error(result.error ?? `HTTP ${res.status}`)
    }
    saveMessage.value = result.restartRequired
      ? `Saved override at ${result.path} — restart herald-bot to apply.`
      : `Saved at ${result.path}.`
    selected.value!.content = editorContent.value
    selected.value!.overridden = selected.value!.name !== 'CONTEXT.md' || true
    dirty.value = false
    await loadList()
  } catch (e: any) {
    errorMessage.value = `Save failed: ${e.message}`
  } finally {
    saving.value = false
  }
}

async function revertOverride() {
  if (!selected.value || !selected.value.overridden) return
  if (selected.value.name === 'CONTEXT.md') {
    if (!confirm('Clear CONTEXT.md content?')) return
    editorContent.value = ''
    await save()
    return
  }
  if (!confirm(`Delete the user override and revert to the bundled ${selected.value.displayName}? Restart required to take effect.`)) {
    return
  }
  try {
    const res = await fetch(`/api/prompts/${encodeURIComponent(selected.value.name)}`, {
      method: 'DELETE',
    })
    const result = await res.json()
    if (!res.ok) throw new Error(result.error ?? `HTTP ${res.status}`)
    saveMessage.value = result.reverted
      ? `Reverted — restart herald-bot to apply the bundled default.`
      : 'No override file present.'
    await selectPrompt(selected.value.name)
    await loadList()
  } catch (e: any) {
    errorMessage.value = `Revert failed: ${e.message}`
  }
}

function loadDefault() {
  if (!selected.value) return
  if (!confirm('Replace editor content with the bundled default? (Not saved until you press Save.)')) return
  editorContent.value = selected.value.defaultContent
  onContentChange()
}

function insertDocSnippet() {
  // Convenience helper: drop a recommended "auto-process documents" block
  // into CONTEXT.md so the user doesn't have to write it from scratch.
  const snippet = `

## Document Handling

When the user uploads any document via Telegram or the web chat — PDF, DOCX, PPTX, XLSX,
HTML, image, audio, etc. — and the user's intent is "remember", "ingest", "summarize",
"file this", or anything similar:

1. Run the \`markitdown\` skill to convert the file to structured Markdown. The skill
   self-installs the CLI on first use; confirm the install if prompted.
2. Hand the result to the \`wiki-ingest\` skill to file it under
   \`~/.herald/memories/sources/\` and update concepts/entities pages.
3. Reply with a 5-bullet summary: question, key findings, surprises, limitations,
   what to do with it. Mention the source slug so the user can re-find the page.

If the user just says "look at this" without a clear intent, ask once whether to
ingest it or just summarize.
`
  editorContent.value = (editorContent.value || '').replace(/\s+$/, '') + snippet + '\n'
  onContentChange()
}

onMounted(() => {
  loadList()
})
</script>

<template>
  <div class="prompts-page">
    <header class="prompts-header">
      <div>
        <h1 class="page-title">Prompts</h1>
        <p class="page-subtitle">View and edit Herald's system prompts and your personal context.</p>
      </div>
    </header>

    <div class="prompts-layout">
      <!-- Left: list -->
      <aside class="prompts-list">
        <button
          v-for="p in prompts"
          :key="p.name"
          class="prompt-list-item"
          :class="{ active: selected?.name === p.name }"
          @click="selectPrompt(p.name)"
        >
          <span class="prompt-name">{{ p.displayName }}</span>
          <span class="prompt-meta">
            <span class="badge" :class="`badge-${p.source}`">
              {{ p.source === 'user' ? 'personal' : p.source === 'user-override' ? 'overridden' : 'bundled' }}
            </span>
          </span>
        </button>
      </aside>

      <!-- Right: editor -->
      <section v-if="selected" class="prompts-editor-pane">
        <div class="editor-header">
          <div>
            <h2 class="editor-title">{{ selected.displayName }}</h2>
            <p class="editor-description">{{ selected.description }}</p>
            <p v-if="selected.name !== 'CONTEXT.md'" class="editor-path">
              <span v-if="selected.overridden">
                Override: <code>{{ selected.overridePath }}</code> (restart bot to apply)
              </span>
              <span v-else>
                Bundled (read-only). Saving creates an override at <code>{{ selected.overridePath }}</code>.
              </span>
            </p>
            <p v-else class="editor-path">
              File: <code>{{ selected.overridePath }}</code> — loaded into every turn via ContextMdAdvisor.
            </p>
          </div>
          <div class="editor-actions">
            <button
              v-if="selected.name === 'CONTEXT.md'"
              class="btn btn-subtle"
              @click="insertDocSnippet"
              title="Append a recommended document-handling section"
            >+ Insert document handler</button>
            <button
              v-if="selected.defaultContent"
              class="btn btn-subtle"
              :class="{ 'btn-active': diffMode }"
              :title="diffMode ? 'Hide diff' : 'Show diff vs bundled default'"
              @click="diffMode = !diffMode"
            >
              Diff
              <span v-if="diffMode" class="diff-summary-inline">
                <span class="diff-changed">~{{ diffSummary.changed }}</span>
                <span class="diff-added">+{{ diffSummary.added }}</span>
                <span class="diff-removed">−{{ diffSummary.removed }}</span>
              </span>
            </button>
            <button
              v-if="selected.defaultContent"
              class="btn btn-subtle"
              @click="loadDefault"
              title="Replace editor content with the bundled default"
            >Load default</button>
            <button
              v-if="selected.overridden"
              class="btn btn-subtle"
              @click="revertOverride"
              title="Delete the user override / clear file"
            >{{ selected.name === 'CONTEXT.md' ? 'Clear' : 'Revert to bundled' }}</button>
            <button
              class="btn btn-primary"
              :disabled="!dirty || saving"
              @click="save"
            >{{ saving ? 'Saving…' : 'Save' }}</button>
          </div>
        </div>

        <div v-if="saveMessage" class="banner banner-success">{{ saveMessage }}</div>
        <div v-if="errorMessage" class="banner banner-error">{{ errorMessage }}</div>

        <DiffEditor
          v-if="diffMode && selected.defaultContent"
          class="editor-diff"
          :original="selected.defaultContent"
          :modified="editorContent"
          @update:modified="(v) => { editorContent = v; onContentChange() }"
        />
        <textarea
          v-else
          class="editor-textarea"
          :value="editorContent"
          @input="(e) => { editorContent = (e.target as HTMLTextAreaElement).value; onContentChange() }"
          spellcheck="false"
        ></textarea>

        <p v-if="restartRequired && dirty" class="editor-hint">
          ⚠ Saving this prompt creates a file in <code>~/.herald/prompts/</code>. Herald-bot reads
          prompts at startup, so restart with <code>./run.sh</code> to pick up changes.
        </p>
      </section>

      <section v-else class="prompts-empty">
        <p>Select a prompt on the left to view or edit.</p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.prompts-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  font-family: 'DM Sans', system-ui, sans-serif;
}

.prompts-header {
  padding-bottom: 12px;
  border-bottom: 1px solid #e8e5df;
}

.page-title {
  font-size: 1.5rem;
  font-weight: 600;
  color: #1a1a1a;
  letter-spacing: -0.02em;
  margin: 0;
}

.page-subtitle {
  font-size: 0.85rem;
  color: #6b7280;
  margin: 4px 0 0 0;
}

.prompts-layout {
  flex: 1;
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: 16px;
  padding-top: 16px;
  min-height: 0;
}

.prompts-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  overflow-y: auto;
  padding-right: 4px;
}

.prompt-list-item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid transparent;
  background: white;
  text-align: left;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.15s;
}

.prompt-list-item:hover {
  border-color: #e5e7eb;
  background: #fafaf8;
}

.prompt-list-item.active {
  border-color: #1d4ed8;
  background: #eef2ff;
}

.prompt-name {
  font-size: 0.875rem;
  font-weight: 600;
  color: #1a1a1a;
}

.prompt-meta {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.badge {
  display: inline-block;
  padding: 2px 7px;
  border-radius: 999px;
  font-size: 0.65rem;
  font-weight: 500;
  text-transform: lowercase;
}

.badge-user {
  background: #dcfce7;
  color: #166534;
}

.badge-user-override {
  background: #fef3c7;
  color: #92400e;
}

.badge-bundled {
  background: #e5e7eb;
  color: #374151;
}

.prompts-editor-pane {
  display: flex;
  flex-direction: column;
  min-height: 0;
  gap: 12px;
}

.editor-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}

.editor-title {
  font-size: 1.1rem;
  font-weight: 600;
  color: #1a1a1a;
  margin: 0 0 4px 0;
}

.editor-description {
  font-size: 0.8rem;
  color: #6b7280;
  margin: 0 0 4px 0;
}

.editor-path {
  font-size: 0.7rem;
  color: #9ca3af;
  margin: 0;
}

.editor-path code {
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.95em;
  background: #f3f4f6;
  padding: 1px 5px;
  border-radius: 3px;
  color: #374151;
}

.editor-actions {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}

.btn {
  padding: 6px 14px;
  border-radius: 6px;
  border: 1px solid transparent;
  font-family: 'DM Sans', sans-serif;
  font-size: 0.8rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-primary {
  background: #1d4ed8;
  color: white;
  border-color: #1d4ed8;
}

.btn-primary:hover:not(:disabled) {
  background: #1e40af;
}

.btn-subtle {
  background: white;
  color: #374151;
  border-color: #e5e7eb;
}

.btn-subtle:hover:not(:disabled) {
  background: #f9fafb;
  border-color: #d1d5db;
}

.banner {
  padding: 8px 12px;
  border-radius: 6px;
  font-size: 0.8rem;
}

.banner-success {
  background: #ecfdf5;
  color: #065f46;
  border: 1px solid #a7f3d0;
}

.banner-error {
  background: #fef2f2;
  color: #b91c1c;
  border: 1px solid #fecaca;
}

.editor-textarea {
  flex: 1;
  min-height: 300px;
  font-family: 'JetBrains Mono', 'Fira Code', ui-monospace, monospace;
  font-size: 0.825rem;
  line-height: 1.6;
  padding: 14px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fafaf8;
  color: #1a1a1a;
  resize: none;
  outline: none;
  white-space: pre;
  overflow: auto;
}

.editor-textarea:focus {
  border-color: #1d4ed8;
  box-shadow: 0 0 0 3px rgba(29, 78, 216, 0.1);
}

.editor-diff {
  flex: 1;
  min-height: 300px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  overflow: hidden;
}

.btn-active {
  background: rgba(200, 165, 90, 0.12) !important;
  border-color: rgba(200, 165, 90, 0.4) !important;
  color: #a68a3e !important;
}

.diff-summary-inline {
  display: inline-flex;
  gap: 6px;
  margin-left: 6px;
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
}

.diff-summary-inline .diff-changed { color: #7c3aed; }
.diff-summary-inline .diff-added   { color: #16a34a; }
.diff-summary-inline .diff-removed { color: #dc2626; }

.editor-hint {
  font-size: 0.7rem;
  color: #92400e;
  background: #fffbeb;
  border: 1px solid #fde68a;
  border-radius: 6px;
  padding: 6px 10px;
  margin: 0;
}

.prompts-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #9ca3af;
}
</style>
