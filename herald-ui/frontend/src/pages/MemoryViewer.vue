<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useMemoryStore } from '@/stores/memory'
import { useObsidianStore } from '@/stores/obsidian'
import {
  useFileMemoryStore,
  TYPE_ORDER,
  TYPE_LABEL,
} from '@/stores/fileMemory'

const store = useMemoryStore()
const obsidian = useObsidianStore()
const fileMemory = useFileMemoryStore()

const newKey = ref('')
const newValue = ref('')
const editingKey = ref<string | null>(null)
const editValue = ref('')
const deletingKey = ref<string | null>(null)
const importInput = ref<HTMLInputElement | null>(null)
const importStatus = ref<{ imported: number; errors: string[] } | null>(null)

const obsidianQuery = ref('')
const obsidianFolder = ref('')

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
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-2xl font-bold text-gray-900">Memory Viewer</h1>
      <div class="flex gap-2">
        <button
          class="px-3 py-1.5 text-sm bg-white border border-gray-300 rounded-md shadow-sm hover:bg-gray-50 text-gray-700"
          @click="store.exportJson()"
        >
          Export JSON
        </button>
        <button
          class="px-3 py-1.5 text-sm bg-white border border-gray-300 rounded-md shadow-sm hover:bg-gray-50 text-gray-700"
          @click="triggerImport()"
        >
          Import JSON
        </button>
        <input
          ref="importInput"
          type="file"
          accept=".json"
          class="hidden"
          @change="handleImport"
        />
      </div>
    </div>

    <!-- Import status message -->
    <div
      v-if="importStatus"
      class="mb-4 p-3 rounded-md text-sm"
      :class="importStatus.errors.length ? 'bg-yellow-50 text-yellow-800' : 'bg-green-50 text-green-800'"
    >
      <p>Imported {{ importStatus.imported }} entries.</p>
      <p v-for="(err, i) in importStatus.errors" :key="i" class="text-red-600">{{ err }}</p>
      <button class="mt-1 text-xs underline" @click="importStatus = null">Dismiss</button>
    </div>

    <!-- Filter -->
    <div class="mb-4">
      <input
        v-model="store.filter"
        type="text"
        placeholder="Filter by key name…"
        class="w-full max-w-sm px-3 py-2 text-sm border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-1 focus:ring-blue-500 focus:border-blue-500"
      />
    </div>

    <!-- Loading state -->
    <div v-if="store.loading" class="text-gray-500">Loading memory entries…</div>

    <div v-else class="bg-white rounded-lg shadow overflow-hidden">
      <div class="overflow-x-auto">
        <table class="w-full text-sm">
          <thead>
            <tr class="text-left text-gray-500 border-b border-gray-200">
              <th class="px-4 pb-2 pt-3 font-medium">Key</th>
              <th class="px-4 pb-2 pt-3 font-medium">Value</th>
              <th class="px-4 pb-2 pt-3 font-medium">Last Updated</th>
              <th class="px-4 pb-2 pt-3 font-medium w-16"></th>
            </tr>
          </thead>
          <tbody>
            <!-- Add new entry row -->
            <tr class="border-b border-gray-100 bg-gray-50">
              <td class="px-4 py-2">
                <input
                  v-model="newKey"
                  type="text"
                  placeholder="New key"
                  class="w-full px-2 py-1 text-sm border border-gray-300 rounded focus:outline-none focus:ring-1 focus:ring-blue-500"
                  @keydown.enter="addEntry()"
                />
              </td>
              <td class="px-4 py-2">
                <input
                  v-model="newValue"
                  type="text"
                  placeholder="Value"
                  class="w-full px-2 py-1 text-sm border border-gray-300 rounded focus:outline-none focus:ring-1 focus:ring-blue-500"
                  @keydown.enter="addEntry()"
                />
              </td>
              <td class="px-4 py-2 text-gray-400">—</td>
              <td class="px-4 py-2">
                <button
                  class="text-sm text-blue-600 hover:text-blue-800 disabled:text-gray-300"
                  :disabled="!newKey.trim()"
                  @click="addEntry()"
                >
                  Add
                </button>
              </td>
            </tr>

            <!-- Memory entries -->
            <tr
              v-for="entry in store.filteredEntries"
              :key="entry.key"
              class="border-b border-gray-50"
            >
              <td class="px-4 py-2 text-gray-900 font-medium">{{ entry.key }}</td>
              <td class="px-4 py-2 text-gray-700">
                <!-- Editing mode -->
                <div v-if="editingKey === entry.key" class="flex gap-2">
                  <input
                    v-model="editValue"
                    type="text"
                    class="flex-1 px-2 py-1 text-sm border border-blue-300 rounded focus:outline-none focus:ring-1 focus:ring-blue-500"
                    @keydown.enter="saveEdit(entry.key)"
                    @keydown.escape="cancelEdit()"
                  />
                  <button
                    class="text-xs text-blue-600 hover:text-blue-800"
                    @click="saveEdit(entry.key)"
                  >
                    Save
                  </button>
                  <button
                    class="text-xs text-gray-500 hover:text-gray-700"
                    @click="cancelEdit()"
                  >
                    Cancel
                  </button>
                </div>
                <!-- Display mode -->
                <span
                  v-else
                  class="cursor-pointer hover:text-blue-600"
                  @click="startEdit(entry.key, entry.value)"
                >
                  {{ entry.value }}
                </span>
              </td>
              <td class="px-4 py-2 text-gray-500">{{ formatTime(entry.lastUpdated) }}</td>
              <td class="px-4 py-2">
                <!-- Delete confirmation -->
                <div v-if="deletingKey === entry.key" class="flex gap-1">
                  <button
                    class="text-xs text-red-600 hover:text-red-800"
                    @click="executeDelete(entry.key)"
                  >
                    Confirm
                  </button>
                  <button
                    class="text-xs text-gray-500 hover:text-gray-700"
                    @click="cancelDelete()"
                  >
                    No
                  </button>
                </div>
                <!-- Trash icon -->
                <button
                  v-else
                  class="text-gray-400 hover:text-red-600"
                  title="Delete entry"
                  @click="confirmDelete(entry.key)"
                >
                  <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                  </svg>
                </button>
              </td>
            </tr>

            <!-- Empty state -->
            <tr v-if="store.filteredEntries.length === 0 && !store.loading">
              <td colspan="4" class="px-4 py-8 text-center text-gray-400">
                {{ store.filter ? 'No entries match the filter' : 'No memory entries yet' }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
    <!-- Long-Term Memory (file-based) -->
    <div class="mt-10">
      <div class="flex items-center justify-between mb-4">
        <h2 class="text-xl font-bold text-gray-900">Long-Term Memory</h2>
        <button
          class="px-3 py-1.5 text-sm bg-white border border-gray-300 rounded-md shadow-sm hover:bg-gray-50 text-gray-700"
          :disabled="fileMemory.loading"
          @click="fileMemory.fetchPages()"
        >
          Refresh
        </button>
      </div>

      <div v-if="fileMemory.error" class="mb-4 p-3 rounded-md text-sm bg-red-50 text-red-700">
        {{ fileMemory.error }}
      </div>

      <div v-if="fileMemory.loading" class="text-gray-500">Loading memory pages…</div>

      <!-- File content viewer -->
      <div
        v-else-if="fileMemory.selected"
        class="bg-white rounded-lg shadow overflow-hidden"
      >
        <div class="px-4 py-3 border-b border-gray-200 flex items-center justify-between bg-gray-50">
          <span class="text-sm font-medium text-gray-700">{{ fileMemory.selected.path }}</span>
          <button
            class="text-sm text-blue-600 hover:text-blue-800"
            @click="fileMemory.clearSelected()"
          >
            Back
          </button>
        </div>
        <pre class="px-4 py-3 text-sm text-gray-800 whitespace-pre-wrap font-mono overflow-x-auto max-h-96 overflow-y-auto">{{ fileMemory.selected.content }}</pre>
      </div>

      <!-- Grouped list -->
      <div v-else class="space-y-4">
        <div
          v-for="type in TYPE_ORDER"
          :key="type"
          class="bg-white rounded-lg shadow overflow-hidden"
        >
          <div class="px-4 py-2 bg-gray-50 border-b border-gray-200 flex items-center justify-between">
            <span class="text-sm font-semibold text-gray-700">{{ TYPE_LABEL[type] }}</span>
            <span class="text-xs text-gray-500">
              {{ (fileMemory.grouped[type] || []).length }} page{{ (fileMemory.grouped[type] || []).length === 1 ? '' : 's' }}
            </span>
          </div>
          <div v-if="(fileMemory.grouped[type] || []).length === 0" class="px-4 py-3 text-sm text-gray-400">
            No {{ TYPE_LABEL[type].toLowerCase() }} memories yet.
          </div>
          <table v-else class="w-full text-sm">
            <tbody>
              <tr
                v-for="page in fileMemory.grouped[type]"
                :key="page.path"
                class="border-t border-gray-50 hover:bg-blue-50 cursor-pointer"
                @click="fileMemory.openPage(page.path)"
              >
                <td class="px-4 py-2 text-blue-700 font-medium w-1/3">{{ page.path }}</td>
                <td class="px-4 py-2 text-gray-700">{{ page.description || '—' }}</td>
                <td class="px-4 py-2 text-gray-400 text-xs whitespace-nowrap w-24">{{ formatBytes(page.size) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- Obsidian Knowledge Base -->
    <div class="mt-10">
      <h2 class="text-xl font-bold text-gray-900 mb-4">Obsidian Knowledge Base</h2>

      <!-- Search bar -->
      <div class="flex gap-2 mb-4">
        <input
          v-model="obsidianQuery"
          type="text"
          placeholder="Search Obsidian vault…"
          class="flex-1 max-w-sm px-3 py-2 text-sm border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-1 focus:ring-blue-500 focus:border-blue-500"
          @keydown.enter="searchObsidian()"
        />
        <select
          v-model="obsidianFolder"
          class="px-3 py-2 text-sm border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-1 focus:ring-blue-500"
        >
          <option value="">All folders</option>
          <option v-for="f in obsidian.folders" :key="f" :value="f">{{ f }}</option>
        </select>
        <button
          class="px-4 py-2 text-sm bg-blue-600 text-white rounded-md hover:bg-blue-700 disabled:bg-gray-300"
          :disabled="!obsidianQuery.trim() || obsidian.loading"
          @click="searchObsidian()"
        >
          Search
        </button>
      </div>

      <!-- Error -->
      <div v-if="obsidian.error" class="mb-4 p-3 rounded-md text-sm bg-red-50 text-red-700">
        {{ obsidian.error }}
      </div>

      <!-- Loading -->
      <div v-if="obsidian.loading" class="text-gray-500">Searching…</div>

      <!-- Note viewer -->
      <div v-else-if="obsidian.selectedNote" class="bg-white rounded-lg shadow overflow-hidden">
        <div class="px-4 py-3 border-b border-gray-200 flex items-center justify-between bg-gray-50">
          <span class="text-sm font-medium text-gray-700">{{ obsidian.selectedNote.path }}</span>
          <button
            class="text-sm text-blue-600 hover:text-blue-800"
            @click="obsidian.clearNote()"
          >
            Back to results
          </button>
        </div>
        <pre class="px-4 py-3 text-sm text-gray-800 whitespace-pre-wrap font-mono overflow-x-auto max-h-96 overflow-y-auto">{{ obsidian.selectedNote.content }}</pre>
      </div>

      <!-- Search results -->
      <div v-else-if="obsidian.results.length > 0" class="bg-white rounded-lg shadow overflow-hidden">
        <div class="overflow-x-auto">
          <table class="w-full text-sm">
            <thead>
              <tr class="text-left text-gray-500 border-b border-gray-200">
                <th class="px-4 pb-2 pt-3 font-medium">Note</th>
                <th class="px-4 pb-2 pt-3 font-medium">Folder</th>
                <th class="px-4 pb-2 pt-3 font-medium">Matching context</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="result in obsidian.results"
                :key="result.path"
                class="border-b border-gray-50 cursor-pointer hover:bg-blue-50"
                @click="obsidian.readNote(result.path)"
              >
                <td class="px-4 py-2 text-blue-600 font-medium">{{ result.name }}</td>
                <td class="px-4 py-2 text-gray-500">{{ result.folder || '—' }}</td>
                <td class="px-4 py-2 text-gray-600">
                  <span v-if="result.snippets.length">{{ result.snippets.slice(0, 3).join(' … ') }}</span>
                  <span v-else class="text-gray-400">—</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- Empty state (only after a search) -->
      <div
        v-else-if="!obsidian.loading && obsidianQuery && obsidian.results.length === 0 && !obsidian.error"
        class="text-gray-400 text-sm"
      >
        No notes found matching "{{ obsidianQuery }}"
      </div>
    </div>
  </div>
</template>
