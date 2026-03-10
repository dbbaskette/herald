<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useMessagesStore } from '@/stores/messages'
import type { ToolCall, SubagentCall } from '@/stores/messages'

const store = useMessagesStore()

const expandedTools = ref<Set<string>>(new Set())
const expandedSubagents = ref<Set<string>>(new Set())
const confirmingClear = ref(false)

onMounted(() => {
  store.fetchMessages()
})

function toggleToolCall(messageId: string, index: number) {
  const key = `${messageId}-tool-${index}`
  if (expandedTools.value.has(key)) {
    expandedTools.value.delete(key)
  } else {
    expandedTools.value.add(key)
  }
}

function isToolExpanded(messageId: string, index: number): boolean {
  return expandedTools.value.has(`${messageId}-tool-${index}`)
}

function toggleSubagent(messageId: string, index: number) {
  const key = `${messageId}-sub-${index}`
  if (expandedSubagents.value.has(key)) {
    expandedSubagents.value.delete(key)
  } else {
    expandedSubagents.value.add(key)
  }
}

function isSubagentExpanded(messageId: string, index: number): boolean {
  return expandedSubagents.value.has(`${messageId}-sub-${index}`)
}

async function executeClear() {
  await store.clearHistory()
  confirmingClear.value = false
}

function formatTime(ts: string | null): string {
  if (!ts) return '—'
  try {
    return new Date(ts).toLocaleString()
  } catch {
    return ts
  }
}

function roleBadgeClass(role: string): string {
  switch (role) {
    case 'user': return 'bg-blue-100 text-blue-800'
    case 'assistant': return 'bg-green-100 text-green-800'
    case 'system': return 'bg-gray-100 text-gray-800'
    default: return 'bg-gray-100 text-gray-800'
  }
}

function formatJson(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-2xl font-bold text-gray-900">Conversation History</h1>
      <div class="flex gap-2">
        <div v-if="confirmingClear" class="flex gap-1 items-center">
          <span class="text-sm text-red-600">Clear all history?</span>
          <button
            class="px-3 py-1.5 text-sm bg-red-600 text-white rounded-md shadow-sm hover:bg-red-700"
            @click="executeClear()"
          >
            Confirm
          </button>
          <button
            class="px-3 py-1.5 text-sm bg-white border border-gray-300 rounded-md shadow-sm hover:bg-gray-50 text-gray-700"
            @click="confirmingClear = false"
          >
            Cancel
          </button>
        </div>
        <button
          v-else
          class="px-3 py-1.5 text-sm bg-red-600 text-white rounded-md shadow-sm hover:bg-red-700"
          @click="confirmingClear = true"
        >
          Clear History
        </button>
      </div>
    </div>

    <!-- Filters -->
    <div class="mb-4 flex flex-wrap gap-3 items-end">
      <div class="flex-1 min-w-[200px]">
        <label class="block text-xs text-gray-500 mb-1">Search</label>
        <input
          v-model="store.search"
          type="text"
          placeholder="Search messages…"
          class="w-full px-3 py-2 text-sm border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-1 focus:ring-blue-500 focus:border-blue-500"
          @keydown.enter="store.applyFilters()"
        />
      </div>
      <div>
        <label class="block text-xs text-gray-500 mb-1">From</label>
        <input
          v-model="store.startDate"
          type="date"
          class="px-3 py-2 text-sm border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-1 focus:ring-blue-500 focus:border-blue-500"
        />
      </div>
      <div>
        <label class="block text-xs text-gray-500 mb-1">To</label>
        <input
          v-model="store.endDate"
          type="date"
          class="px-3 py-2 text-sm border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-1 focus:ring-blue-500 focus:border-blue-500"
        />
      </div>
      <button
        class="px-3 py-2 text-sm bg-blue-600 text-white rounded-md shadow-sm hover:bg-blue-700"
        @click="store.applyFilters()"
      >
        Search
      </button>
      <button
        class="px-3 py-2 text-sm bg-white border border-gray-300 rounded-md shadow-sm hover:bg-gray-50 text-gray-700"
        @click="store.clearFilters()"
      >
        Clear Filters
      </button>
    </div>

    <!-- Loading state -->
    <div v-if="store.loading" class="text-gray-500">Loading messages…</div>

    <div v-else>
      <!-- Messages list -->
      <div class="space-y-3">
        <div
          v-for="msg in store.messages"
          :key="msg.id"
          class="bg-white rounded-lg shadow p-4"
        >
          <!-- Message header -->
          <div class="flex items-center gap-3 mb-2">
            <span
              class="px-2 py-0.5 text-xs font-medium rounded-full"
              :class="roleBadgeClass(msg.role)"
            >
              {{ msg.role }}
            </span>
            <span class="text-xs text-gray-500">{{ formatTime(msg.timestamp) }}</span>
          </div>

          <!-- Message content -->
          <p class="text-sm text-gray-800 whitespace-pre-wrap">{{ msg.content }}</p>

          <!-- Tool calls -->
          <div v-if="msg.toolCalls && msg.toolCalls.length > 0" class="mt-3">
            <p class="text-xs font-medium text-gray-500 mb-1">Tool Calls ({{ msg.toolCalls.length }})</p>
            <div
              v-for="(tool, ti) in msg.toolCalls"
              :key="ti"
              class="border border-gray-200 rounded-md mb-1"
            >
              <button
                class="w-full flex items-center gap-2 px-3 py-2 text-sm text-left hover:bg-gray-50"
                @click="toggleToolCall(msg.id, ti)"
              >
                <svg
                  class="w-3 h-3 text-gray-400 transition-transform"
                  :class="{ 'rotate-90': isToolExpanded(msg.id, ti) }"
                  fill="currentColor" viewBox="0 0 20 20"
                >
                  <path fill-rule="evenodd" d="M7.293 14.707a1 1 0 010-1.414L10.586 10 7.293 6.707a1 1 0 011.414-1.414l4 4a1 1 0 010 1.414l-4 4a1 1 0 01-1.414 0z" clip-rule="evenodd" />
                </svg>
                <span class="font-mono text-blue-700">{{ tool.name }}</span>
              </button>
              <div v-if="isToolExpanded(msg.id, ti)" class="px-3 pb-3 border-t border-gray-100">
                <div class="mt-2">
                  <p class="text-xs font-medium text-gray-500 mb-1">Inputs</p>
                  <pre class="text-xs bg-gray-50 rounded p-2 overflow-x-auto">{{ formatJson(tool.inputs) }}</pre>
                </div>
                <div class="mt-2">
                  <p class="text-xs font-medium text-gray-500 mb-1">Outputs</p>
                  <pre class="text-xs bg-gray-50 rounded p-2 overflow-x-auto">{{ formatJson(tool.outputs) }}</pre>
                </div>
              </div>
            </div>
          </div>

          <!-- Subagent calls -->
          <div v-if="msg.subagentCalls && msg.subagentCalls.length > 0" class="mt-3">
            <p class="text-xs font-medium text-gray-500 mb-1">Subagent Calls ({{ msg.subagentCalls.length }})</p>
            <div
              v-for="(sub, si) in msg.subagentCalls"
              :key="si"
              class="border border-purple-200 rounded-md mb-1"
            >
              <button
                class="w-full flex items-center gap-2 px-3 py-2 text-sm text-left hover:bg-purple-50"
                @click="toggleSubagent(msg.id, si)"
              >
                <svg
                  class="w-3 h-3 text-purple-400 transition-transform"
                  :class="{ 'rotate-90': isSubagentExpanded(msg.id, si) }"
                  fill="currentColor" viewBox="0 0 20 20"
                >
                  <path fill-rule="evenodd" d="M7.293 14.707a1 1 0 010-1.414L10.586 10 7.293 6.707a1 1 0 011.414-1.414l4 4a1 1 0 010 1.414l-4 4a1 1 0 01-1.414 0z" clip-rule="evenodd" />
                </svg>
                <span class="font-mono text-purple-700">{{ sub.name }}</span>
              </button>
              <div v-if="isSubagentExpanded(msg.id, si)" class="px-3 pb-3 border-t border-purple-100">
                <div v-if="sub.toolCalls && sub.toolCalls.length > 0" class="mt-2">
                  <p class="text-xs font-medium text-gray-500 mb-1">Tool Calls</p>
                  <div
                    v-for="(stool, sti) in sub.toolCalls"
                    :key="sti"
                    class="ml-2 mb-2"
                  >
                    <p class="text-xs font-mono text-blue-700">{{ stool.name }}</p>
                    <pre class="text-xs bg-gray-50 rounded p-2 overflow-x-auto mt-1">Inputs: {{ formatJson(stool.inputs) }}</pre>
                    <pre class="text-xs bg-gray-50 rounded p-2 overflow-x-auto mt-1">Outputs: {{ formatJson(stool.outputs) }}</pre>
                  </div>
                </div>
                <div class="mt-2">
                  <p class="text-xs font-medium text-gray-500 mb-1">Result</p>
                  <pre class="text-xs bg-gray-50 rounded p-2 overflow-x-auto">{{ sub.result }}</pre>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- Empty state -->
        <div v-if="store.messages.length === 0" class="bg-white rounded-lg shadow p-8 text-center text-gray-400">
          {{ store.search || store.startDate || store.endDate ? 'No messages match the filters' : 'No conversation history yet' }}
        </div>
      </div>

      <!-- Pagination -->
      <div v-if="store.totalPages > 1" class="flex items-center justify-between mt-4">
        <p class="text-sm text-gray-500">
          Page {{ store.currentPage + 1 }} of {{ store.totalPages }} ({{ store.totalElements }} messages)
        </p>
        <div class="flex gap-2">
          <button
            class="px-3 py-1.5 text-sm bg-white border border-gray-300 rounded-md shadow-sm hover:bg-gray-50 text-gray-700 disabled:opacity-50 disabled:cursor-not-allowed"
            :disabled="!store.hasPrevPage"
            @click="store.prevPage()"
          >
            Previous
          </button>
          <button
            class="px-3 py-1.5 text-sm bg-white border border-gray-300 rounded-md shadow-sm hover:bg-gray-50 text-gray-700 disabled:opacity-50 disabled:cursor-not-allowed"
            :disabled="!store.hasNextPage"
            @click="store.nextPage()"
          >
            Next
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
