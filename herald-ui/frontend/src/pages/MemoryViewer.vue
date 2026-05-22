<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMemoryStore } from '@/stores/memory'
import { useObsidianStore } from '@/stores/obsidian'
import {
  useFileMemoryStore,
  TYPE_ORDER,
  TYPE_LABEL,
} from '@/stores/fileMemory'

type TabId = 'wiki' | 'kv' | 'obsidian'

const route = useRoute()
const router = useRouter()
const store = useMemoryStore()
const obsidian = useObsidianStore()
const fileMemory = useFileMemoryStore()

const activeTab = ref<TabId>('wiki')

const newKey = ref('')
const newValue = ref('')
const editingKey = ref<string | null>(null)
const editValue = ref('')
const deletingKey = ref<string | null>(null)
const importInput = ref<HTMLInputElement | null>(null)
const importStatus = ref<{ imported: number; errors: string[] } | null>(null)

const obsidianQuery = ref('')
const obsidianFolder = ref('')

const tabs: { id: TabId; label: string; hint: string }[] = [
  {
    id: 'wiki',
    label: 'Wiki Memory',
    hint: 'Typed long-term memory pages (concepts, entities, sources) — the primary store Herald learns from.',
  },
  {
    id: 'kv',
    label: 'Key-Value Store',
    hint: 'Simple SQLite key-value entries for quick facts and preferences.',
  },
  {
    id: 'obsidian',
    label: 'Obsidian',
    hint: 'Search your Obsidian vault when vault mode is enabled.',
  },
]

function setTab(tab: TabId) {
  activeTab.value = tab
  router.replace({ query: { ...route.query, tab } })
}

async function openPathFromQuery(path: string) {
  activeTab.value = 'wiki'
  await fileMemory.fetchPages()
  await fileMemory.openPage(path)
}

watch(
  () => route.query,
  async (q) => {
    const tab = q.tab as TabId | undefined
    if (tab && tabs.some((t) => t.id === tab)) {
      activeTab.value = tab
    }
    const path = q.path as string | undefined
    if (path) {
      await openPathFromQuery(path)
    }
  },
  { immediate: true }
)

onMounted(() => {
  store.fetchEntries()
  obsidian.fetchFolders()
  fileMemory.fetchPages()
})

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

async function searchObsidian() {
  if (!obsidianQuery.value.trim()) return
  await obsidian.search(obsidianQuery.value, obsidianFolder.value || undefined)
}

function startEdit(key: string, value: string) {
  editingKey.value = key
  editValue.value = value
}

function cancelEdit() {
  editingKey.value = null
  editValue.value = ''
}

async function saveEdit(key: string) {
  await store.updateEntry(key, editValue.value)
  editingKey.value = null
  editValue.value = ''
}

async function addEntry() {
  if (!newKey.value.trim()) return
  const ok = await store.addEntry(newKey.value.trim(), newValue.value)
  if (ok) {
    newKey.value = ''
    newValue.value = ''
  }
}

function confirmDelete(key: string) {
  deletingKey.value = key
}

async function executeDelete(key: string) {
  await store.deleteEntry(key)
  deletingKey.value = null
}

function cancelDelete() {
  deletingKey.value = null
}

function triggerImport() {
  importInput.value?.click()
}

async function handleImport(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return
  importStatus.value = await store.importJson(file)
  target.value = ''
  await store.fetchEntries()
}

function formatTime(ts: string | null): string {
  if (!ts) return '—'
  try {
    return new Date(ts).toLocaleString()
  } catch {
    return ts
  }
}
</script>

<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">Memory Viewer</h1>
      <div v-if="activeTab === 'kv'" class="page-actions">
        <button class="btn-secondary" @click="store.exportJson()">Export JSON</button>
        <button class="btn-secondary" @click="triggerImport()">Import JSON</button>
        <input ref="importInput" type="file" accept=".json" class="hidden" @change="handleImport" />
      </div>
      <button
        v-else-if="activeTab === 'wiki'"
        class="btn-secondary"
        :disabled="fileMemory.loading"
        @click="fileMemory.fetchPages()"
      >
        Refresh
      </button>
    </div>

    <nav class="tab-bar">
      <button
        v-for="tab in tabs"
        :key="tab.id"
        class="tab-btn"
        :class="{ active: activeTab === tab.id }"
        @click="setTab(tab.id)"
      >
        {{ tab.label }}
      </button>
    </nav>

    <p class="help-text">{{ tabs.find((t) => t.id === activeTab)?.hint }}</p>

    <!-- Wiki Memory tab -->
    <div v-show="activeTab === 'wiki'">
      <div v-if="fileMemory.error" class="alert alert-error">{{ fileMemory.error }}</div>
      <div v-if="fileMemory.loading && !fileMemory.selected" class="text-muted">Loading memory pages…</div>

      <div v-else-if="fileMemory.selected" class="card content-viewer">
        <div class="content-viewer-header">
          <span class="content-path">{{ fileMemory.selected.path }}</span>
          <button class="btn-secondary btn-sm" @click="fileMemory.clearSelected()">Back</button>
        </div>
        <pre class="content-body">{{ fileMemory.selected.content }}</pre>
      </div>

      <div v-else class="wiki-groups">
        <div v-for="type in TYPE_ORDER" :key="type" class="card wiki-group">
          <div class="wiki-group-header">
            <span class="section-label">{{ TYPE_LABEL[type] }}</span>
            <span class="wiki-count">
              {{ (fileMemory.grouped[type] || []).length }} page{{ (fileMemory.grouped[type] || []).length === 1 ? '' : 's' }}
            </span>
          </div>
          <div v-if="(fileMemory.grouped[type] || []).length === 0" class="empty-cell">
            No {{ TYPE_LABEL[type].toLowerCase() }} memories yet.
          </div>
          <table v-else class="data-table">
            <tbody>
              <tr
                v-for="page in fileMemory.grouped[type]"
                :key="page.path"
                class="data-row-clickable"
                @click="fileMemory.openPage(page.path)"
              >
                <td class="cell-path">{{ page.path }}</td>
                <td class="cell-desc">{{ page.description || '—' }}</td>
                <td class="cell-meta">{{ formatBytes(page.size) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- Key-Value tab -->
    <div v-show="activeTab === 'kv'">
      <div
        v-if="importStatus"
        class="alert"
        :class="importStatus.errors.length ? 'alert-warn' : 'alert-success'"
      >
        <p>Imported {{ importStatus.imported }} entries.</p>
        <p v-for="(err, i) in importStatus.errors" :key="i">{{ err }}</p>
        <button class="link-dismiss" @click="importStatus = null">Dismiss</button>
      </div>

      <div class="mb-4">
        <input
          v-model="store.filter"
          type="text"
          placeholder="Filter by key name…"
          class="input max-w-sm"
        />
      </div>

      <div v-if="store.loading" class="text-muted">Loading memory entries…</div>

      <div v-else class="card table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th class="table-header px-4 pt-3 pb-2">Key</th>
              <th class="table-header px-4 pt-3 pb-2">Value</th>
              <th class="table-header px-4 pt-3 pb-2">Last Updated</th>
              <th class="table-header px-4 pt-3 pb-2 w-16"></th>
            </tr>
          </thead>
          <tbody>
            <tr class="add-row">
              <td class="px-4 py-2">
                <input v-model="newKey" type="text" placeholder="New key" class="input input-sm" @keydown.enter="addEntry()" />
              </td>
              <td class="px-4 py-2">
                <input v-model="newValue" type="text" placeholder="Value" class="input input-sm" @keydown.enter="addEntry()" />
              </td>
              <td class="px-4 py-2 text-muted">—</td>
              <td class="px-4 py-2">
                <button class="link-action" :disabled="!newKey.trim()" @click="addEntry()">Add</button>
              </td>
            </tr>
            <tr v-for="entry in store.filteredEntries" :key="entry.key" class="data-row">
              <td class="px-4 py-2 cell-key">{{ entry.key }}</td>
              <td class="px-4 py-2">
                <div v-if="editingKey === entry.key" class="edit-row">
                  <input v-model="editValue" type="text" class="input input-sm flex-1" @keydown.enter="saveEdit(entry.key)" @keydown.escape="cancelEdit()" />
                  <button class="link-action" @click="saveEdit(entry.key)">Save</button>
                  <button class="link-muted" @click="cancelEdit()">Cancel</button>
                </div>
                <span v-else class="cell-editable" @click="startEdit(entry.key, entry.value)">{{ entry.value }}</span>
              </td>
              <td class="px-4 py-2 text-muted text-sm">{{ formatTime(entry.lastUpdated) }}</td>
              <td class="px-4 py-2">
                <div v-if="deletingKey === entry.key" class="edit-row">
                  <button class="link-danger" @click="executeDelete(entry.key)">Confirm</button>
                  <button class="link-muted" @click="cancelDelete()">No</button>
                </div>
                <button v-else class="icon-btn" title="Delete entry" @click="confirmDelete(entry.key)">
                  <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                  </svg>
                </button>
              </td>
            </tr>
            <tr v-if="store.filteredEntries.length === 0">
              <td colspan="4" class="empty-cell">{{ store.filter ? 'No entries match the filter' : 'No memory entries yet' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- Obsidian tab -->
    <div v-show="activeTab === 'obsidian'">
      <div class="search-bar">
        <input v-model="obsidianQuery" type="text" placeholder="Search Obsidian vault…" class="input flex-1 max-w-md" @keydown.enter="searchObsidian()" />
        <select v-model="obsidianFolder" class="input w-auto">
          <option value="">All folders</option>
          <option v-for="f in obsidian.folders" :key="f" :value="f">{{ f }}</option>
        </select>
        <button class="btn-primary" :disabled="!obsidianQuery.trim() || obsidian.loading" @click="searchObsidian()">Search</button>
      </div>

      <div v-if="obsidian.error" class="alert alert-error">{{ obsidian.error }}</div>
      <div v-if="obsidian.loading" class="text-muted">Searching…</div>

      <div v-else-if="obsidian.selectedNote" class="card content-viewer">
        <div class="content-viewer-header">
          <span class="content-path">{{ obsidian.selectedNote.path }}</span>
          <button class="btn-secondary btn-sm" @click="obsidian.clearNote()">Back to results</button>
        </div>
        <pre class="content-body">{{ obsidian.selectedNote.content }}</pre>
      </div>

      <div v-else-if="obsidian.results.length > 0" class="card table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th class="table-header px-4 pt-3 pb-2">Note</th>
              <th class="table-header px-4 pt-3 pb-2">Folder</th>
              <th class="table-header px-4 pt-3 pb-2">Matching context</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="result in obsidian.results" :key="result.path" class="data-row-clickable" @click="obsidian.readNote(result.path)">
              <td class="px-4 py-2 cell-path">{{ result.name }}</td>
              <td class="px-4 py-2 text-muted">{{ result.folder || '—' }}</td>
              <td class="px-4 py-2">{{ result.snippets.length ? result.snippets.slice(0, 3).join(' … ') : '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-else-if="obsidianQuery && !obsidian.error" class="text-muted text-sm">
        No notes found matching "{{ obsidianQuery }}"
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.page-actions {
  display: flex;
  gap: 8px;
}

.text-muted {
  color: var(--color-text-muted);
  font-size: 0.875rem;
}

.mb-4 {
  margin-bottom: 16px;
}

.max-w-sm {
  max-width: 24rem;
}

.max-w-md {
  max-width: 28rem;
}

.flex-1 {
  flex: 1;
}

.w-auto {
  width: auto;
}

.hidden {
  display: none;
}

.alert {
  margin-bottom: 16px;
  padding: 12px 14px;
  border-radius: 8px;
  font-size: 0.875rem;
}

.alert-error {
  background: rgba(239, 68, 68, 0.08);
  color: #dc2626;
}

.alert-warn {
  background: rgba(251, 191, 36, 0.12);
  color: #92400e;
}

.alert-success {
  background: rgba(22, 163, 74, 0.1);
  color: #15803d;
}

.link-dismiss {
  margin-top: 4px;
  font-size: 0.75rem;
  text-decoration: underline;
  background: none;
  border: none;
  cursor: pointer;
  color: inherit;
}

.wiki-groups {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.wiki-group-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--color-border-light);
}

.wiki-count {
  font-size: 0.75rem;
  color: var(--color-text-muted);
}

.table-wrap {
  overflow: hidden;
}

.data-table {
  width: 100%;
  font-size: 0.875rem;
}

.data-row-clickable {
  cursor: pointer;
  border-top: 1px solid var(--color-border-light);
}

.data-row-clickable:hover {
  background: rgba(200, 165, 90, 0.06);
}

.data-row {
  border-top: 1px solid var(--color-border-light);
}

.add-row {
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border-light);
}

.cell-path {
  padding: 8px 16px;
  font-family: 'JetBrains Mono', monospace;
  font-weight: 500;
  color: var(--color-brand-dim);
  width: 33%;
}

.cell-desc {
  padding: 8px 16px;
  color: var(--color-text-primary);
}

.cell-meta {
  padding: 8px 16px;
  color: var(--color-text-muted);
  font-size: 0.75rem;
  white-space: nowrap;
  width: 6rem;
}

.cell-key {
  font-weight: 500;
  color: var(--color-text-primary);
}

.cell-editable {
  cursor: pointer;
  color: var(--color-text-primary);
}

.cell-editable:hover {
  color: var(--color-brand-dim);
}

.empty-cell {
  padding: 32px 16px;
  text-align: center;
  color: var(--color-text-muted);
}

.content-viewer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--color-border-light);
  background: var(--color-surface);
}

.content-path {
  font-size: 0.8125rem;
  font-weight: 500;
  color: var(--color-text-secondary);
  font-family: 'JetBrains Mono', monospace;
}

.content-body {
  padding: 16px;
  font-size: 0.8125rem;
  line-height: 1.6;
  color: var(--color-text-primary);
  white-space: pre-wrap;
  font-family: 'JetBrains Mono', monospace;
  max-height: 24rem;
  overflow: auto;
  margin: 0;
}

.search-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}

.input-sm {
  padding: 6px 10px;
  font-size: 0.8125rem;
}

.btn-sm {
  padding: 6px 12px;
  font-size: 0.8125rem;
}

.edit-row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.link-action {
  font-size: 0.8125rem;
  color: var(--color-brand-dim);
  background: none;
  border: none;
  cursor: pointer;
}

.link-action:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.link-muted {
  font-size: 0.8125rem;
  color: var(--color-text-muted);
  background: none;
  border: none;
  cursor: pointer;
}

.link-danger {
  font-size: 0.75rem;
  color: #dc2626;
  background: none;
  border: none;
  cursor: pointer;
}

.icon-btn {
  background: none;
  border: none;
  color: var(--color-text-muted);
  cursor: pointer;
  padding: 2px;
}

.icon-btn:hover {
  color: #dc2626;
}
</style>
