<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted, computed } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { useMemoryStore } from '@/stores/memory'
import { useObsidianStore } from '@/stores/obsidian'
import {
  useFileMemoryStore,
  TYPE_ORDER,
  TYPE_LABEL,
} from '@/stores/fileMemory'
import NowStripe from '@/components/NowStripe.vue'
import PageHeader from '@/components/PageHeader.vue'
import SectionRule from '@/components/SectionRule.vue'
import StatusGlyph from '@/components/StatusGlyph.vue'
import ConfirmModal from '@/components/ConfirmModal.vue'
import { useChatStore } from '@/stores/chat'
import MemoryHealthBar from '@/components/MemoryHealthBar.vue'

type TabId = 'wiki' | 'kv' | 'obsidian'

const route = useRoute()
const router = useRouter()
const store = useMemoryStore()
const obsidian = useObsidianStore()
const fileMemory = useFileMemoryStore()

const chat = useChatStore()
const obsidianEnabled = ref(false)
const deletingMemory = ref(false)
const contextPaths = ref<string[]>([])
let contextGeneration = 0
let contextTimer: ReturnType<typeof setInterval> | undefined
async function refreshContext() {
  const generation = ++contextGeneration
  try {
    const res = await fetch(`/api/memory/files/context?${new URLSearchParams({conversationId: chat.conversationId})}`)
    const value = res.ok ? await res.json() : null
    if (generation === contextGeneration) contextPaths.value = value?.active === true && Array.isArray(value.paths) ? value.paths.filter((p: unknown) => typeof p === 'string') : []
  } catch { if (generation === contextGeneration) contextPaths.value = [] }
}
watch(() => chat.conversationId, () => { contextPaths.value = []; refreshContext() })
onUnmounted(() => { ++contextGeneration; clearInterval(contextTimer) })
function canLeave() {
  if (fileMemory.dirty || fileMemory.saving) { fileMemory.error = 'Save or discard your draft before leaving.'; return false }
  return true
}
onBeforeRouteLeave(canLeave)
onBeforeRouteUpdate(canLeave)
watch(() => fileMemory.selected?.path, () => { deletingMemory.value = false })
function beforeUnload(event: BeforeUnloadEvent) { if (fileMemory.dirty) { event.preventDefault(); event.returnValue = '' } }
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onUnmounted(() => window.removeEventListener('beforeunload', beforeUnload))
const selectedMetadata = computed(() => Object.values(fileMemory.grouped).flat().find(p => p.path === fileMemory.selected?.path))
async function deleteMemory() { if(await fileMemory.deleteSelected()) deletingMemory.value = false }

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

const allTabs: { id: TabId; label: string; hint: string }[] = [
  { id: 'wiki',     label: 'File Memory', hint: 'Canonical learned memory — the index and typed Markdown notes Herald reads and maintains.' },
  { id: 'kv',       label: 'Legacy Key·Value', hint: 'Retained SQLite entries for compatibility and manual reference. These are not automatically learned or loaded into agent context.' },
  { id: 'obsidian', label: 'Obsidian',  hint: 'Search the connected Obsidian vault.' },
]

const tabs = computed(() => allTabs.filter(t => t.id !== 'obsidian' || obsidianEnabled.value))
const tabHint = computed(() => tabs.value.find((t) => t.id === activeTab.value)?.hint ?? '')

function setTab(tab: TabId) {
  if (fileMemory.dirty || fileMemory.saving) { fileMemory.error = 'Save or discard your draft before leaving.'; return }
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
    if (tab && tabs.value.some((t) => t.id === tab)) activeTab.value = tab
    const path = q.path as string | undefined
    if (path) await openPathFromQuery(path)
  },
  { immediate: true },
)

onMounted(() => {
  store.fetchEntries()
  fetch('/api/memory/files/capabilities').then(r => r.ok ? r.json() : {obsidian: false}).then(value => { obsidianEnabled.value = value.obsidian === true; if (obsidianEnabled.value) obsidian.fetchFolders() }).catch(() => {})
  refreshContext()
  contextTimer = setInterval(refreshContext, 5000)
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
function cancelEdit() { editingKey.value = null; editValue.value = '' }
async function saveEdit(key: string) {
  if (await store.updateEntry(key, editValue.value)) { editingKey.value = null; editValue.value = '' }
}
async function addEntry() {
  if (!newKey.value.trim()) return
  const ok = await store.addEntry(newKey.value.trim(), newValue.value)
  if (ok) { newKey.value = ''; newValue.value = '' }
}
function confirmDelete(key: string) { deletingKey.value = key }
async function executeDelete(key: string) { await store.deleteEntry(key); deletingKey.value = null }
function cancelDelete() { deletingKey.value = null }

function triggerImport() { importInput.value?.click() }

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
  try { return new Date(ts).toLocaleString() } catch { return ts }
}
</script>

<template>
  <div class="memory-page">
    <NowStripe />

    <PageHeader title="Memory" path="/memory">
      <template #right>
        <div v-if="activeTab === 'kv'" class="header-actions">
          <button class="btn-secondary" :disabled="store.loading" @click="store.exportJson()">Export legacy backup</button>
          <button class="btn-secondary" @click="triggerImport()">Import legacy JSON</button>
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
      </template>
    </PageHeader>

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

    <p class="hint">{{ tabHint }}</p>

    <!-- ── Wiki ─────────────────────────────────────────────────── -->
    <div v-show="activeTab === 'wiki'">
      <MemoryHealthBar />
      <form class="filter-bar" @submit.prevent="fileMemory.fetchPages()">
        <input v-model="fileMemory.query" aria-label="Search memory text" class="input" placeholder="Search filenames and full text…" />
        <input v-model="fileMemory.tag" aria-label="Filter memory tag" class="input" placeholder="Tag…" />
        <button class="btn-secondary" :disabled="fileMemory.loading">Search</button>
      </form>
      <p class="hint">Context badges show actual reads during the executing turn of {{ chat.conversationId }}. Tool text is not retained after the turn. Files over 256 KiB are listed by filename only.</p>
      <div v-if="fileMemory.trash" class="alert" role="status">
        Moved to {{ fileMemory.trash.trashPath }}
        <button class="btn-secondary" :disabled="fileMemory.saving" @click="fileMemory.restore()">Undo delete</button>
      </div>
      <ConfirmModal :open="deletingMemory" title="Move memory to trash?" :message="`Move ${fileMemory.selected?.path} to .memory-trash? You can undo this deletion.`" confirm-label="Move to trash" danger @confirm="deleteMemory" @cancel="deletingMemory = false" />
      <div v-if="fileMemory.error" class="alert alert-err">
        <StatusGlyph kind="err" /> {{ fileMemory.error }}
      </div>
      <div v-if="fileMemory.loading && !fileMemory.selected" class="empty">
        <StatusGlyph kind="idle" /> Loading memory pages…
      </div>

      <!-- Page detail viewer -->
      <div v-else-if="fileMemory.selected" class="reader">
        <div class="reader-head">
          <span class="reader-path">{{ fileMemory.selected.path }}</span>
          <button class="btn-secondary" @click="fileMemory.clearSelected()">← Back</button>
        </div>
        <p v-if="contextPaths.includes(fileMemory.selected.path)" class="hint">In current context</p>
        <p class="hint" v-if="selectedMetadata?.conversationId">Why is this here? Written from <RouterLink :to="{path: '/history', query: {conversationId: selectedMetadata.conversationId}}">{{ selectedMetadata.conversationId }}</RouterLink> {{ selectedMetadata.createdAt || '' }}</p>
        <p v-else class="hint">No conversation attribution recorded for this memory.</p>
        <textarea v-model="fileMemory.draft" class="reader-body memory-editor" aria-label="Memory Markdown editor" :disabled="fileMemory.saving || fileMemory.selectedLoading" spellcheck="false" />
        <div class="reader-head">
          <span>{{ fileMemory.dirty ? 'Unsaved changes' : 'Saved' }}</span>
          <button class="btn-primary" :disabled="!fileMemory.dirty || fileMemory.saving || !fileMemory.selected.version" @click="fileMemory.save()">Save</button>
          <button class="btn-secondary" :disabled="!fileMemory.dirty || fileMemory.saving" @click="fileMemory.discardDraft()">Discard draft</button>
          <button class="btn-secondary" :disabled="fileMemory.dirty || fileMemory.saving" @click="fileMemory.openPage(fileMemory.selected.path)">Reload</button>
          <button class="btn-secondary" :disabled="fileMemory.dirty || fileMemory.saving || !fileMemory.selected.version" @click="deletingMemory = true">Delete</button>
        </div>
      </div>

      <!-- Grouped list -->
      <div v-else>
        <template v-for="type in TYPE_ORDER" :key="type">
          <div class="memory-section">
            <div class="memory-section__head">
              <span :class="`type-pill type-pill--${type}`">{{ TYPE_LABEL[type] }}</span>
              <span class="memory-section__line" />
              <span class="memory-section__count">
                {{ (fileMemory.grouped[type] || []).length }}
                page{{ (fileMemory.grouped[type] || []).length === 1 ? '' : 's' }}
              </span>
            </div>
            <div v-if="(fileMemory.grouped[type] || []).length === 0" class="empty">
              no {{ TYPE_LABEL[type].toLowerCase() }} yet.
            </div>
            <div v-else class="page-list">
              <button
                v-for="page in fileMemory.grouped[type]"
                :key="page.path"
                class="page-row"
                :class="`page-row--${type}`"
                @click="fileMemory.openPage(page.path)"
              >
                <span class="page-row__path">{{ page.path }}</span>
                <span class="page-row__desc">{{ contextPaths.includes(page.path) ? 'In current context · ' : '' }}{{ page.description || '—' }} {{ page.tags?.map(t => '#' + t).join(' ') }}</span>
                <span class="page-row__size">{{ formatBytes(page.size) }}</span>
              </button>
            </div>
          </div>
        </template>
      </div>
    </div>

    <!-- ── Key·Value ────────────────────────────────────────────── -->
    <div v-show="activeTab === 'kv'">
      <p class="hint legacy-ownership">Use File Memory for facts Herald should remember. Legacy edits stay in SQLite and do not update learned Markdown notes or runtime settings. Export backs up only legacy keys and values; importing restores those entries and overwrites matching keys. Neither operation changes File Memory or Obsidian.</p>
      <div v-if="store.error" class="alert alert-warn" role="alert">
        {{ store.error }}
        <button class="btn-secondary" :disabled="store.loading" @click="store.fetchEntries()">Retry legacy load</button>
      </div>
      <div
        v-if="importStatus"
        class="alert"
        :class="importStatus.errors.length ? 'alert-warn' : 'alert-ok'"
      >
        <p>Imported {{ importStatus.imported }} legacy entries.</p>
        <p v-for="(err, i) in importStatus.errors" :key="i" class="caption">{{ err }}</p>
        <button class="link-dismiss" @click="importStatus = null">dismiss</button>
      </div>

      <div class="filter-bar">
        <input
          v-model="store.filter"
          type="text"
          placeholder="filter by key…"
          class="input filter-input"
        />
      </div>

      <SectionRule label="ENTRIES" tone="data" :trailing="`${store.filteredEntries.length}`" />

      <div v-if="store.loading" class="empty">
        <StatusGlyph kind="idle" /> Loading…
      </div>

      <table v-else class="data-table kv-table">
        <thead>
          <tr>
            <th>Key</th>
            <th>Value</th>
            <th>Updated</th>
            <th class="col-action"></th>
          </tr>
        </thead>
        <tbody>
          <tr class="add-row">
            <td>
              <input v-model="newKey" type="text" placeholder="new key" class="input input-inline" @keydown.enter="addEntry()" />
            </td>
            <td>
              <input v-model="newValue" type="text" placeholder="value" class="input input-inline" @keydown.enter="addEntry()" />
            </td>
            <td class="text-muted">—</td>
            <td>
              <button class="link-action" :disabled="!newKey.trim()" @click="addEntry()">add</button>
            </td>
          </tr>
          <tr v-for="entry in store.filteredEntries" :key="entry.key">
            <td class="cell-key">{{ entry.key }}</td>
            <td>
              <div v-if="editingKey === entry.key" class="edit-row">
                <input v-model="editValue" type="text" class="input input-inline flex-1" @keydown.enter="saveEdit(entry.key)" @keydown.escape="cancelEdit()" />
                <button class="link-action" @click="saveEdit(entry.key)">save</button>
                <button class="link-muted" @click="cancelEdit()">cancel</button>
              </div>
              <span v-else class="cell-editable" @click="startEdit(entry.key, entry.value)">{{ entry.value }}</span>
            </td>
            <td class="text-muted">{{ formatTime(entry.lastUpdated) }}</td>
            <td>
              <div v-if="deletingKey === entry.key" class="edit-row">
                <button class="link-danger" @click="executeDelete(entry.key)">delete</button>
                <button class="link-muted" @click="cancelDelete()">no</button>
              </div>
              <button v-else class="link-muted" @click="confirmDelete(entry.key)">✕</button>
            </td>
          </tr>
          <tr v-if="store.filteredEntries.length === 0">
            <td colspan="4" class="empty-cell">
              {{ store.filter ? 'no entries match the filter' : 'no legacy entries retained' }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- ── Obsidian ─────────────────────────────────────────────── -->
    <div v-show="activeTab === 'obsidian'">
      <div class="filter-bar">
        <input
          v-model="obsidianQuery"
          type="text"
          placeholder="search vault…"
          class="input flex-1"
          style="max-width:24rem"
          @keydown.enter="searchObsidian()"
        />
        <select v-model="obsidianFolder" class="input" style="width:auto">
          <option value="">all folders</option>
          <option v-for="f in obsidian.folders" :key="f" :value="f">{{ f }}</option>
        </select>
        <button class="btn-primary" :disabled="!obsidianQuery.trim() || obsidian.loading" @click="searchObsidian()">
          Search
        </button>
      </div>

      <div v-if="obsidian.error" class="alert alert-err">
        <StatusGlyph kind="err" /> {{ obsidian.error }}
      </div>
      <div v-if="obsidian.loading" class="empty">
        <StatusGlyph kind="idle" /> searching…
      </div>

      <div v-else-if="obsidian.selectedNote" class="reader">
        <div class="reader-head">
          <span class="reader-path">{{ obsidian.selectedNote.path }}</span>
          <button class="btn-secondary" @click="obsidian.clearNote()">← Results</button>
        </div>
        <pre class="reader-body">{{ obsidian.selectedNote.content }}</pre>
      </div>

      <div v-else-if="obsidian.results.length > 0">
        <SectionRule label="RESULTS" tone="info" :trailing="`${obsidian.results.length}`" />
        <div class="page-list">
          <button
            v-for="result in obsidian.results"
            :key="result.path"
            class="page-row"
            @click="obsidian.readNote(result.path)"
          >
            <span class="page-row__path">{{ result.name }}</span>
            <span class="page-row__desc">{{ result.snippets.length ? result.snippets.slice(0, 3).join(' … ') : '—' }}</span>
            <span class="page-row__size text-muted">{{ result.folder || '—' }}</span>
          </button>
        </div>
      </div>

      <div v-else-if="obsidianQuery && !obsidian.error" class="empty">
        no notes match "{{ obsidianQuery }}"
      </div>
    </div>
  </div>
</template>

<style scoped>
.memory-editor { width: 100%; min-height: 24rem; box-sizing: border-box; background: var(--paper-2); border: 0; font-family: monospace; }
.memory-page { max-width: 1080px; }

.header-actions { display: flex; gap: 8px; }
.hidden { display: none; }

.hint {
  color: var(--graphite-2);
  font-size: 0.6875rem;
  margin: 0 0 16px;
  letter-spacing: 0.02em;
}

.alert {
  margin-bottom: 16px;
  padding: 8px 12px;
  border-left: 2px solid var(--paper-rule);
  background: var(--paper-2);
  font-size: 0.8125rem;
  color: var(--graphite);
}
.alert-err { border-left-color: var(--err); color: var(--err); }
.alert-warn{ border-left-color: var(--warn); color: var(--warn); }
.alert-ok  { border-left-color: var(--ok); color: var(--ok); }

.link-dismiss {
  margin-top: 4px;
  font-size: 0.6875rem;
  background: none;
  border: none;
  color: var(--graphite-2);
  cursor: pointer;
  padding: 0;
  text-decoration: underline;
}

.empty {
  font-size: 0.8125rem;
  color: var(--graphite-2);
  font-style: italic;
  padding: 8px 0;
  display: flex;
  align-items: baseline;
  gap: 8px;
}

/* Memory sections — type-coloured */
.memory-section { margin: 28px 0 20px; }
.memory-section__head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}
.memory-section__line {
  flex: 1;
  height: 1px;
  background: var(--paper-3);
}
.memory-section__count {
  font-size: 0.75rem;
  color: var(--graphite-2);
  letter-spacing: 0.04em;
}

/* Page rows — buttons styled as terminal table lines */
.page-list { display: flex; flex-direction: column; }

.page-row {
  display: grid;
  grid-template-columns: minmax(14rem, 24rem) 1fr 5rem;
  gap: 16px;
  align-items: baseline;
  padding: 8px 12px;
  background: transparent;
  border: none;
  border-left: 2px solid transparent;
  border-bottom: 1px solid var(--paper-3);
  text-align: left;
  cursor: pointer;
  width: 100%;
  font-family: inherit;
  font-size: 0.875rem;
  color: var(--ink);
  transition: background 100ms, border-left-color 100ms;
}
.page-row:last-child { border-bottom: none; }
.page-row:hover { background: var(--paper-2); }

/* Type-coloured left border on hover */
.page-row--concept:hover   { border-left-color: var(--info); }
.page-row--entity:hover    { border-left-color: var(--magic); }
.page-row--source:hover    { border-left-color: var(--data); }
.page-row--user:hover      { border-left-color: var(--gold); }
.page-row--feedback:hover  { border-left-color: var(--warn); }
.page-row--project:hover   { border-left-color: var(--ok); }
.page-row--reference:hover { border-left-color: var(--graphite-2); }
.page-row--unknown:hover   { border-left-color: var(--graphite-2); }

.page-row--concept   .page-row__path { color: var(--info); }
.page-row--entity    .page-row__path { color: var(--magic); }
.page-row--source    .page-row__path { color: var(--data); }
.page-row--user      .page-row__path { color: var(--gold-dim); }
.page-row--feedback  .page-row__path { color: var(--warn); }
.page-row--project   .page-row__path { color: var(--ok); }
.page-row__path { color: var(--info); }
.page-row__desc {
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.page-row__size { color: var(--graphite-2); font-size: 0.6875rem; text-align: right; }

/* Reader / detail view */
.reader {
  border: 1px solid var(--paper-rule);
  background: var(--paper-2);
}
.reader-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  border-bottom: 1px solid var(--paper-rule);
  background: var(--paper);
}
.reader-path { color: var(--gold-dim); font-size: 0.75rem; }
.reader-body {
  margin: 0;
  padding: 16px;
  font-size: 0.8125rem;
  line-height: 1.6;
  color: var(--ink);
  white-space: pre-wrap;
  max-height: 32rem;
  overflow: auto;
}

/* Filter bar */
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}
.filter-input { max-width: 24rem; }

/* KV table specifics */
.kv-table .col-action { width: 4rem; }
.add-row td { background: var(--paper-2); }
.add-row td:first-child { padding-top: 4px; padding-bottom: 4px; }

.input-inline { padding: 4px 8px; font-size: 0.8125rem; }
.flex-1 { flex: 1; }

.cell-key { color: var(--ink); font-weight: 500; }
.cell-editable { cursor: pointer; color: var(--ink); }
.cell-editable:hover { color: var(--gold-dim); }

.empty-cell {
  text-align: center;
  color: var(--graphite-2);
  padding: 24px 8px;
  font-style: italic;
  font-size: 0.8125rem;
}

.edit-row { display: flex; gap: 8px; align-items: center; }

.link-action {
  background: none;
  border: none;
  padding: 0;
  font-family: inherit;
  font-size: 0.8125rem;
  color: var(--gold-dim);
  cursor: pointer;
}
.link-action:disabled { opacity: 0.4; cursor: not-allowed; }

.link-muted {
  background: none;
  border: none;
  padding: 0;
  font-family: inherit;
  font-size: 0.8125rem;
  color: var(--graphite-2);
  cursor: pointer;
}

.link-danger {
  background: none;
  border: none;
  padding: 0;
  font-family: inherit;
  font-size: 0.8125rem;
  color: var(--err);
  cursor: pointer;
}
</style>
