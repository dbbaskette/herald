<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useSettingsStore, settingDefs } from '@/stores/settings'

const store = useSettingsStore()
const form = ref<Record<string, string>>({})

onMounted(async () => {
  await store.fetchSettings()
  // Initialize form with current values
  for (const def of settingDefs) {
    form.value[def.key] = store.settings[def.key] ?? ''
  }
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
          Obsidian vault path and weather location take effect immediately.
        </p>
      </div>
    </form>
  </div>
</template>
