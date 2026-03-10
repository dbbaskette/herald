<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
import { useStatusStore } from '@/stores/status'

const store = useStatusStore()

onMounted(async () => {
  await store.fetchStatus()
  store.connectSSE()
})

onUnmounted(() => {
  store.disconnectSSE()
})

function statusDot(ok: boolean): string {
  return ok ? 'bg-green-500' : 'bg-red-500'
}

function mcpStatusColor(status: string): string {
  if (status === 'connected') return 'text-green-600'
  if (status === 'error') return 'text-red-600'
  return 'text-gray-400'
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
      <h1 class="text-2xl font-bold text-gray-900">System Status</h1>
      <div class="flex items-center gap-2 text-sm">
        <span
          class="inline-block w-2 h-2 rounded-full"
          :class="store.connected ? 'bg-green-500' : 'bg-gray-400'"
        />
        <span class="text-gray-500">{{ store.connected ? 'Live' : 'Disconnected' }}</span>
      </div>
    </div>

    <!-- Loading state -->
    <div v-if="store.loading" class="text-gray-500">Loading status…</div>

    <template v-else>
      <!-- Status Cards Grid -->
      <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4 mb-8">

        <!-- Bot Card -->
        <div class="bg-white rounded-lg shadow p-4">
          <div class="flex items-center gap-2 mb-3">
            <span class="inline-block w-2.5 h-2.5 rounded-full" :class="statusDot(store.status.bot.running)" />
            <h2 class="text-sm font-semibold text-gray-700 uppercase tracking-wide">Bot</h2>
          </div>
          <dl class="space-y-1 text-sm">
            <div class="flex justify-between">
              <dt class="text-gray-500">Status</dt>
              <dd class="font-medium" :class="store.status.bot.running ? 'text-green-700' : 'text-red-700'">
                {{ store.status.bot.running ? 'Running' : 'Stopped' }}
              </dd>
            </div>
            <div class="flex justify-between">
              <dt class="text-gray-500">PID</dt>
              <dd class="text-gray-900">{{ store.status.bot.pid ?? '—' }}</dd>
            </div>
            <div class="flex justify-between">
              <dt class="text-gray-500">Uptime</dt>
              <dd class="text-gray-900">{{ store.status.bot.uptime }}</dd>
            </div>
            <div class="flex justify-between">
              <dt class="text-gray-500">Restarts</dt>
              <dd class="text-gray-900">{{ store.status.bot.restartCount }}</dd>
            </div>
          </dl>
        </div>

        <!-- Model Card -->
        <div class="bg-white rounded-lg shadow p-4">
          <div class="flex items-center gap-2 mb-3">
            <span class="inline-block w-2.5 h-2.5 rounded-full bg-blue-500" />
            <h2 class="text-sm font-semibold text-gray-700 uppercase tracking-wide">Model</h2>
          </div>
          <dl class="space-y-1 text-sm">
            <div class="flex justify-between">
              <dt class="text-gray-500">Name</dt>
              <dd class="text-gray-900">{{ store.status.model.name }}</dd>
            </div>
            <div class="flex justify-between">
              <dt class="text-gray-500">Requests today</dt>
              <dd class="text-gray-900">{{ store.status.model.requestsToday }}</dd>
            </div>
            <div class="flex justify-between">
              <dt class="text-gray-500">Est. token spend</dt>
              <dd class="text-gray-900">{{ store.status.model.estimatedTokenSpend }}</dd>
            </div>
          </dl>
        </div>

        <!-- Skills Card -->
        <div class="bg-white rounded-lg shadow p-4">
          <div class="flex items-center gap-2 mb-3">
            <span class="inline-block w-2.5 h-2.5 rounded-full bg-purple-500" />
            <h2 class="text-sm font-semibold text-gray-700 uppercase tracking-wide">Skills</h2>
          </div>
          <dl class="space-y-1 text-sm">
            <div class="flex justify-between">
              <dt class="text-gray-500">Total loaded</dt>
              <dd class="text-gray-900">{{ store.status.skills.totalLoaded }}</dd>
            </div>
            <div class="flex justify-between">
              <dt class="text-gray-500">Last reload</dt>
              <dd class="text-gray-900">{{ formatTime(store.status.skills.lastReload) }}</dd>
            </div>
            <div v-if="store.status.skills.parseErrors.length" class="pt-1">
              <dt class="text-red-600 text-xs font-medium">Parse errors</dt>
              <dd v-for="(err, i) in store.status.skills.parseErrors" :key="i" class="text-red-500 text-xs">
                {{ err }}
              </dd>
            </div>
          </dl>
        </div>

        <!-- Memory Card -->
        <div class="bg-white rounded-lg shadow p-4">
          <div class="flex items-center gap-2 mb-3">
            <span class="inline-block w-2.5 h-2.5 rounded-full bg-yellow-500" />
            <h2 class="text-sm font-semibold text-gray-700 uppercase tracking-wide">Memory</h2>
          </div>
          <dl class="space-y-1 text-sm">
            <div class="flex justify-between">
              <dt class="text-gray-500">Entries</dt>
              <dd class="text-gray-900">{{ store.status.memory.entryCount }}</dd>
            </div>
            <div class="flex justify-between">
              <dt class="text-gray-500">DB file size</dt>
              <dd class="text-gray-900">{{ store.status.memory.databaseFileSize }}</dd>
            </div>
          </dl>
        </div>

        <!-- MCP Connections Card -->
        <div class="bg-white rounded-lg shadow p-4 md:col-span-2">
          <div class="flex items-center gap-2 mb-3">
            <span class="inline-block w-2.5 h-2.5 rounded-full bg-indigo-500" />
            <h2 class="text-sm font-semibold text-gray-700 uppercase tracking-wide">MCP Connections</h2>
          </div>
          <div v-if="store.status.mcp.length === 0" class="text-sm text-gray-400">No MCP servers configured</div>
          <div v-else class="space-y-2">
            <div
              v-for="server in store.status.mcp"
              :key="server.name"
              class="flex items-center justify-between text-sm border-b border-gray-100 pb-1 last:border-0"
            >
              <div class="flex items-center gap-2">
                <span class="font-medium text-gray-900">{{ server.name }}</span>
                <span class="text-xs" :class="mcpStatusColor(server.status)">{{ server.status }}</span>
              </div>
              <div class="flex gap-4 text-gray-500 text-xs">
                <span>{{ server.toolCount }} tools</span>
                <span>Last ping: {{ formatTime(server.lastPing) }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Cron Jobs -->
      <div v-if="store.status.cron.length" class="bg-white rounded-lg shadow p-4 mb-8">
        <h2 class="text-sm font-semibold text-gray-700 uppercase tracking-wide mb-3">Cron Jobs</h2>
        <div class="overflow-x-auto">
          <table class="w-full text-sm">
            <thead>
              <tr class="text-left text-gray-500 border-b border-gray-200">
                <th class="pb-2 font-medium">Job</th>
                <th class="pb-2 font-medium">Next Run</th>
                <th class="pb-2 font-medium">Last Run</th>
                <th class="pb-2 font-medium">Last Result</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="job in store.status.cron" :key="job.name" class="border-b border-gray-50">
                <td class="py-1.5 text-gray-900 font-medium">{{ job.name }}</td>
                <td class="py-1.5 text-gray-600">{{ formatTime(job.nextRun) }}</td>
                <td class="py-1.5 text-gray-600">{{ formatTime(job.lastRun) }}</td>
                <td class="py-1.5">
                  <span
                    v-if="job.lastResult"
                    class="text-xs px-1.5 py-0.5 rounded"
                    :class="job.lastResult === 'success' ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'"
                  >
                    {{ job.lastResult }}
                  </span>
                  <span v-else class="text-gray-400">—</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- Recent Activity Feed -->
      <div class="bg-white rounded-lg shadow p-4">
        <h2 class="text-sm font-semibold text-gray-700 uppercase tracking-wide mb-3">Recent Activity</h2>
        <div v-if="store.status.recentActivity.length === 0" class="text-sm text-gray-400">
          No recent activity
        </div>
        <div v-else class="space-y-3">
          <div
            v-for="(entry, i) in store.status.recentActivity"
            :key="i"
            class="border-b border-gray-100 pb-2 last:border-0"
          >
            <div class="flex items-center justify-between mb-1">
              <span class="text-xs text-gray-400">{{ formatTime(entry.timestamp) }}</span>
              <div v-if="entry.toolCalls.length" class="flex gap-1">
                <span
                  v-for="(tool, j) in entry.toolCalls"
                  :key="j"
                  class="text-xs bg-gray-100 text-gray-600 px-1.5 py-0.5 rounded"
                >
                  {{ tool }}
                </span>
              </div>
            </div>
            <p class="text-sm text-gray-700 truncate">{{ entry.messagePreview }}</p>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
