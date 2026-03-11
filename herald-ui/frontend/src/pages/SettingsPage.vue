<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useSettingsStore, settingDefs } from '@/stores/settings'

const store = useSettingsStore()
const form = ref<Record<string, string>>({})

const gwsStatus = ref<{ installed: boolean; clientConfigured: boolean; authenticated: boolean; message?: string } | null>(null)
const gwsLoading = ref(false)
const gwsActionMessage = ref('')

async function fetchGwsStatus() {
  gwsLoading.value = true
  try {
    const res = await fetch('/api/gws/status')
    if (res.ok) gwsStatus.value = await res.json()
  } catch { /* ignore */ }
  finally { gwsLoading.value = false }
}

async function gwsLogin() {
  gwsActionMessage.value = ''
  try {
    const res = await fetch('/api/gws/login', { method: 'POST' })
    const data = await res.json()
    gwsActionMessage.value = data.message || ''
    // Poll for auth completion
    if (data.status === 'launched') {
      let attempts = 0
      const poll = setInterval(async () => {
        attempts++
        await fetchGwsStatus()
        if (gwsStatus.value?.authenticated || attempts > 60) {
          clearInterval(poll)
          if (gwsStatus.value?.authenticated) gwsActionMessage.value = 'Google account connected!'
        }
      }, 2000)
    }
  } catch { gwsActionMessage.value = 'Failed to launch login' }
}

async function gwsLogout() {
  gwsActionMessage.value = ''
  try {
    const res = await fetch('/api/gws/logout', { method: 'POST' })
    const data = await res.json()
    gwsActionMessage.value = data.message || ''
    await fetchGwsStatus()
  } catch { gwsActionMessage.value = 'Failed to disconnect' }
}

onMounted(async () => {
  await store.fetchSettings()
  for (const def of settingDefs) {
    form.value[def.key] = store.settings[def.key] ?? ''
  }
  fetchGwsStatus()
})

async function save() {
  // Only send changed values
  const updates: Record<string, string> = {}
  for (const def of settingDefs) {
    const current = store.settings[def.key] ?? ''
    const newVal = form.value[def.key] ?? ''
    if (newVal !== current) {
      updates[def.key] = newVal
    }
  }
  if (Object.keys(updates).length === 0) return
  await store.saveSettings(updates)
  // Sync form with saved state
  for (const def of settingDefs) {
    form.value[def.key] = store.settings[def.key] ?? ''
  }
}

function reset() {
  for (const def of settingDefs) {
    form.value[def.key] = store.settings[def.key] ?? ''
  }
}

function hasChanges(): boolean {
  for (const def of settingDefs) {
    const current = store.settings[def.key] ?? ''
    if ((form.value[def.key] ?? '') !== current) return true
  }
  return false
}
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-2xl font-bold text-gray-900">Settings</h1>
    </div>

    <div v-if="store.loading" class="text-gray-500">Loading settings...</div>

    <div v-else-if="store.error" class="p-4 bg-red-50 text-red-700 rounded-lg text-sm">
      Failed to load settings: {{ store.error }}
    </div>

    <form v-else @submit.prevent="save()">
      <div v-for="(defs, groupName) in store.groups" :key="groupName" class="mb-8">
        <h2 class="text-sm font-semibold text-gray-500 uppercase tracking-wider mb-4">
          {{ groupName }}
        </h2>
        <div class="bg-white rounded-lg shadow divide-y divide-gray-100">
          <div
            v-for="def in defs"
            :key="def.key"
            class="px-5 py-4 flex flex-col sm:flex-row sm:items-center gap-2"
          >
            <div class="sm:w-1/3">
              <label :for="'setting-' + def.key" class="text-sm font-medium text-gray-900">
                {{ def.label }}
              </label>
              <p class="text-xs text-gray-500 mt-0.5">{{ def.description }}</p>
            </div>
            <div class="sm:flex-1">
              <input
                :id="'setting-' + def.key"
                v-model="form[def.key]"
                type="text"
                :placeholder="def.placeholder"
                class="w-full px-3 py-2 text-sm border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-1 focus:ring-blue-500 focus:border-blue-500"
              />
            </div>
          </div>
        </div>
      </div>

      <!-- Google Workspace Auth -->
      <div class="mb-8">
        <h2 class="text-sm font-semibold text-gray-500 uppercase tracking-wider mb-4">
          Google Account
        </h2>
        <div class="bg-white rounded-lg shadow px-5 py-4">
          <div v-if="gwsLoading" class="text-sm text-gray-500">Checking Google connection...</div>
          <div v-else-if="gwsStatus">
            <!-- Status indicator -->
            <div class="flex items-center gap-3 mb-3">
              <span
                class="inline-block w-2.5 h-2.5 rounded-full"
                :class="gwsStatus.authenticated ? 'bg-green-500' : gwsStatus.installed ? 'bg-yellow-500' : 'bg-red-500'"
              ></span>
              <span class="text-sm font-medium text-gray-900">
                <template v-if="gwsStatus.authenticated">Connected — Gmail, Calendar, Drive</template>
                <template v-else-if="!gwsStatus.installed">gws CLI not installed</template>
                <template v-else-if="!gwsStatus.clientConfigured">OAuth credentials not configured — enter Client ID and Secret above, save, then connect</template>
                <template v-else>Not connected</template>
              </span>
            </div>

            <!-- Action buttons -->
            <div class="flex items-center gap-3">
              <button
                v-if="gwsStatus.installed && gwsStatus.clientConfigured && !gwsStatus.authenticated"
                class="px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-md shadow-sm hover:bg-blue-700"
                @click="gwsLogin()"
              >
                Connect Google Account
              </button>
              <button
                v-if="gwsStatus.authenticated"
                class="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md shadow-sm hover:bg-gray-50"
                @click="gwsLogout()"
              >
                Disconnect
              </button>
              <button
                class="px-3 py-2 text-sm text-gray-500 hover:text-gray-700"
                @click="fetchGwsStatus()"
              >
                Refresh status
              </button>
            </div>

            <!-- Action message -->
            <p v-if="gwsActionMessage" class="mt-2 text-sm text-blue-600">{{ gwsActionMessage }}</p>

            <!-- Install hint -->
            <p v-if="!gwsStatus.installed" class="mt-2 text-xs text-gray-500">
              Install with: <code class="bg-gray-100 px-1 py-0.5 rounded">brew install googleworkspace-cli</code>
            </p>
          </div>
        </div>
      </div>

      <!-- Action bar -->
      <div class="flex items-center gap-3 mt-6">
        <button
          type="submit"
          :disabled="store.saving || !hasChanges()"
          class="px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-md shadow-sm hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-40 disabled:cursor-not-allowed"
        >
          {{ store.saving ? 'Saving...' : 'Save Changes' }}
        </button>
        <button
          type="button"
          :disabled="!hasChanges()"
          class="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md shadow-sm hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed"
          @click="reset()"
        >
          Reset
        </button>
        <span
          v-if="store.saved"
          class="text-sm text-green-600 font-medium"
        >
          Settings saved
        </span>
      </div>

      <!-- Info note -->
      <div class="mt-6 p-4 bg-gray-50 rounded-lg border border-gray-200">
        <p class="text-xs text-gray-500">
          <span class="font-medium text-gray-700">Note:</span>
          Some settings (persona, timezone, max context tokens) take effect on the next bot restart.
          Obsidian vault path, weather location, and Google credentials take effect immediately.
        </p>
      </div>
    </form>
  </div>
</template>
