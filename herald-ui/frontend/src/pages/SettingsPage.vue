<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useSettingsStore, settingDefs } from '@/stores/settings'
import NowStripe from '@/components/NowStripe.vue'
import PageHeader from '@/components/PageHeader.vue'
import SectionCard from '@/components/SectionCard.vue'

const store = useSettingsStore()
const form = ref<Record<string, string>>({})

// Model switcher state
const modelStatus = ref<{ provider: string; model: string; available: Record<string, string> } | null>(null)
const modelLoading = ref(false)
const modelSwitching = ref(false)
const modelMessage = ref('')
const selectedProvider = ref('')
const selectedModel = ref('')

const availableProviders = computed(() =>
  modelStatus.value ? Object.keys(modelStatus.value.available).filter(k => k !== 'error') : []
)

async function fetchModelStatus() {
  modelLoading.value = true
  try {
    const res = await fetch('/api/model')
    if (res.ok) {
      modelStatus.value = await res.json()
      selectedProvider.value = modelStatus.value!.provider
      selectedModel.value = modelStatus.value!.model
    }
  } catch { /* bot offline */ }
  finally { modelLoading.value = false }
}

function onProviderChange() {
  if (modelStatus.value?.available[selectedProvider.value]) {
    selectedModel.value = modelStatus.value.available[selectedProvider.value]
  }
}

async function switchModel() {
  modelSwitching.value = true
  modelMessage.value = ''
  try {
    const res = await fetch('/api/model', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider: selectedProvider.value, model: selectedModel.value })
    })
    const data = await res.json()
    if (res.ok) {
      modelStatus.value = data
      modelMessage.value = `Switched to ${data.provider}/${data.model}`
    } else {
      modelMessage.value = data.available?.error || 'Switch failed'
    }
  } catch { modelMessage.value = 'Bot unreachable' }
  finally { modelSwitching.value = false }
}

const gwsStatus = ref<{ installed: boolean; clientConfigured: boolean; authenticated: boolean; message?: string } | null>(null)
const gwsLoading = ref(false)
const gwsActionMessage = ref('')
const gwsAuthUrl = ref('')
const clientSecretFileInput = ref<HTMLInputElement | null>(null)
const clientSecretMessage = ref('')

function uploadClientSecret() {
  clientSecretFileInput.value?.click()
}

async function onClientSecretFile(event: Event) {
  clientSecretMessage.value = ''
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return

  try {
    const text = await file.text()
    const json = JSON.parse(text)

    // Google client_secret.json has either "installed" or "web" key
    const creds = json.installed || json.web
    if (!creds?.client_id || !creds?.client_secret) {
      clientSecretMessage.value = 'Invalid file — no client_id/client_secret found'
      return
    }

    // Save to settings
    await store.saveSettings({
      'google.client-id': creds.client_id,
      'google.client-secret': creds.client_secret,
    })

    // Update form fields
    form.value['google.client-id'] = creds.client_id
    form.value['google.client-secret'] = creds.client_secret

    clientSecretMessage.value = 'Credentials imported successfully'
    await fetchGwsStatus()
  } catch (e: any) {
    clientSecretMessage.value = 'Failed to parse file: ' + e.message
  } finally {
    // Reset file input so the same file can be re-selected
    target.value = ''
  }
}

async function fetchGwsStatus() {
  gwsLoading.value = true
  try {
    const res = await fetch('/api/gws/status')
    if (res.ok) gwsStatus.value = await res.json()
  } catch { /* ignore */ }
  finally { gwsLoading.value = false }
}

let loginPollTimer: ReturnType<typeof setInterval> | null = null

async function gwsLogin() {
  gwsActionMessage.value = ''
  gwsAuthUrl.value = ''
  try {
    const res = await fetch('/api/gws/login', { method: 'POST' })
    const data = await res.json()
    gwsActionMessage.value = data.message || ''
    // Open the auth URL in a new tab if provided
    if (data.authUrl) {
      gwsAuthUrl.value = data.authUrl
      window.open(data.authUrl, '_blank')
    }
    // Poll for auth completion
    if (data.status === 'launched' || data.status === 'already_running') {
      if (loginPollTimer) clearInterval(loginPollTimer)
      let attempts = 0
      loginPollTimer = setInterval(async () => {
        attempts++
        await fetchGwsStatus()
        if (gwsStatus.value?.authenticated || attempts > 60) {
          if (loginPollTimer) { clearInterval(loginPollTimer); loginPollTimer = null }
          if (gwsStatus.value?.authenticated) gwsActionMessage.value = 'Google account connected!'
          else if (attempts > 60) gwsActionMessage.value = 'Timed out waiting for authorization.'
        }
      }, 3000)
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
  fetchModelStatus()
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
  <div class="settings-page">
    <NowStripe />
    <PageHeader title="Settings" path="/settings" />

    <div v-if="store.loading" class="text-muted">Loading settings...</div>

    <div v-else-if="store.error" class="alert-error card">
      Failed to load settings: {{ store.error }}
    </div>

    <form v-else @submit.prevent="save()">
      <SectionCard
        v-for="(defs, groupName) in store.groups"
        :key="groupName"
        :label="String(groupName)"
        class="settings-group settings-group--rows"
      >
        <div
          v-for="def in defs"
          :key="def.key"
          class="setting-row"
        >
          <div class="setting-label-col">
            <label :for="'setting-' + def.key" class="setting-label">
              {{ def.label }}
            </label>
            <p class="setting-desc">{{ def.description }}</p>
          </div>
          <div class="setting-input-col">
            <input
              :id="'setting-' + def.key"
              v-model="form[def.key]"
              :type="def.secret ? 'password' : 'text'"
              :placeholder="def.placeholder"
              :autocomplete="def.secret ? 'off' : undefined"
              class="input"
            />
          </div>
        </div>
      </SectionCard>

      <!-- Model & Provider -->
      <SectionCard label="Model & Provider" tone="gold" glyph="gold" class="settings-group">
        <div v-if="modelLoading" class="text-muted text-sm">Checking model status...</div>
        <div v-else-if="!modelStatus" class="text-muted text-sm">Bot is offline — model switching unavailable</div>
        <div v-else>
            <div class="model-active">
              <span class="status-dot status-dot--live"></span>
              <span class="text-sm font-medium">
                Active: {{ modelStatus.provider }}/{{ modelStatus.model }}
              </span>
            </div>

            <div class="model-selectors">
              <div class="selector-col">
                <label for="model-provider" class="filter-label">Provider</label>
                <select id="model-provider" v-model="selectedProvider" @change="onProviderChange" class="input">
                  <option v-for="p in availableProviders" :key="p" :value="p">{{ p }}</option>
                </select>
              </div>
              <div class="selector-col flex-1">
                <label for="model-name" class="filter-label">Model</label>
                <input id="model-name" v-model="selectedModel" type="text" class="input" />
              </div>
            </div>

            <div class="model-actions">
              <button
                type="button"
                :disabled="modelSwitching || (selectedProvider === modelStatus.provider && selectedModel === modelStatus.model)"
                class="btn-primary"
                @click="switchModel"
              >
                {{ modelSwitching ? 'Switching...' : 'Switch Model' }}
              </button>
              <span v-if="modelMessage" class="text-sm" :class="modelMessage.startsWith('Switched') ? 'status-ok' : 'status-err'">
                {{ modelMessage }}
              </span>
            </div>
        </div>
      </SectionCard>

      <!-- Google Workspace Auth -->
      <SectionCard label="Google Account" tone="info" glyph="info" class="settings-group">
        <div v-if="gwsLoading" class="text-muted text-sm">Checking Google connection...</div>
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

            <!-- Upload client_secret.json -->
            <div v-if="!gwsStatus.clientConfigured && gwsStatus.installed" class="mb-4 p-3 bg-gray-50 rounded-lg border border-gray-200">
              <p class="text-sm text-gray-700 mb-2">
                Upload your <code class="bg-gray-100 px-1 py-0.5 rounded text-xs">client_secret.json</code> from Google Cloud Console
                (APIs &amp; Services &rarr; Credentials &rarr; OAuth 2.0 Client ID &rarr; Download JSON)
              </p>
              <input
                ref="clientSecretFileInput"
                type="file"
                accept=".json,application/json"
                class="hidden"
                @change="onClientSecretFile"
              />
              <button
                type="button"
                class="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md shadow-sm hover:bg-gray-50"
                @click="uploadClientSecret()"
              >
                Upload client_secret.json
              </button>
              <span v-if="clientSecretMessage" class="ml-3 text-sm" :class="clientSecretMessage.includes('success') ? 'text-green-600' : 'text-red-600'">
                {{ clientSecretMessage }}
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
            <p v-if="gwsAuthUrl" class="mt-1 text-xs text-gray-500">
              If the browser didn't open: <a :href="gwsAuthUrl" target="_blank" class="text-blue-600 underline break-all">{{ gwsAuthUrl }}</a>
            </p>

            <!-- Install hint -->
            <p v-if="!gwsStatus.installed" class="mt-2 text-xs text-gray-500">
              Install with: <code class="bg-gray-100 px-1 py-0.5 rounded">brew install googleworkspace-cli</code>
            </p>
          </div>
      </SectionCard>

      <!-- Action bar -->
      <div class="action-bar">
        <button type="submit" :disabled="store.saving || !hasChanges()" class="btn-primary">
          {{ store.saving ? 'Saving...' : 'Save Changes' }}
        </button>
        <button type="button" :disabled="!hasChanges()" class="btn-secondary" @click="reset()">
          Reset
        </button>
        <span v-if="store.saved" class="status-ok text-sm font-medium">Settings saved</span>
      </div>

      <div class="info-note card">
        <p class="info-text">
          <strong>Note:</strong>
          Some settings (persona, timezone, max context tokens) take effect on the next bot restart.
          Obsidian vault path, weather location, and Google credentials take effect immediately.
        </p>
      </div>
    </form>
  </div>
</template>

<style scoped>
.settings-page { max-width: 980px; }

.text-muted { color: var(--color-text-muted); }
.text-sm { font-size: 0.875rem; }
.font-medium { font-weight: 500; }
.flex-1 { flex: 1; }

.alert-error {
  padding: 16px;
  color: #dc2626;
  margin-bottom: 16px;
}

.settings-group {
  margin-bottom: 28px;
}

/* Dynamic groups render row-style rather than padded body — cancel the
   SectionCard body padding so .setting-row's own padding rules the spacing. */
.settings-group--rows :deep(.section-card__body) {
  padding: 0;
}

.setting-row {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px 20px;
  border-bottom: 1px solid var(--paper-3);
}

.setting-row:last-child {
  border-bottom: none;
}

@media (min-width: 640px) {
  .setting-row {
    flex-direction: row;
    align-items: center;
  }
}

.setting-label-col {
  flex: 0 0 33%;
}

.setting-label {
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--color-text-primary);
}

.setting-desc {
  font-size: 0.75rem;
  color: var(--color-text-muted);
  margin: 2px 0 0;
}

.setting-input-col {
  flex: 1;
}

.filter-label {
  display: block;
  font-size: 0.75rem;
  color: var(--color-text-muted);
  margin-bottom: 4px;
}

.model-active {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}

.model-selectors {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 12px;
}

@media (min-width: 640px) {
  .model-selectors {
    flex-direction: row;
  }
}

.selector-col {
  flex: 0 0 33%;
}

.model-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.status-ok { color: #15803d; }
.status-err { color: #dc2626; }

.action-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 24px;
}

.info-note {
  margin-top: 24px;
  padding: 16px;
  background: var(--color-surface);
}

.info-text {
  margin: 0;
  font-size: 0.75rem;
  line-height: 1.5;
  color: var(--color-text-muted);
}
</style>
