<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useMemoryStore } from '@/stores/memory'

const store = useMemoryStore()

const newKey = ref('')
const newValue = ref('')
const editingKey = ref<string | null>(null)
const editValue = ref('')
const deletingKey = ref<string | null>(null)
const importInput = ref<HTMLInputElement | null>(null)
const importStatus = ref<{ imported: number; errors: string[] } | null>(null)

onMounted(() => {
  store.fetchEntries()
})

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
  </div>
</template>
