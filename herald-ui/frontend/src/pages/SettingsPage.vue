<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useSettingsStore, settingDefs } from '@/stores/settings'
import { useModelStatus } from '@/composables/useModelStatus'
import NowStripe from '@/components/NowStripe.vue'
import PageHeader from '@/components/PageHeader.vue'
import SectionCard from '@/components/SectionCard.vue'

const store = useSettingsStore()
const form = ref<Record<string, string>>({})

// Model switcher — shared logic via the composable (same source as the chat
// header). The Settings form keeps its own selected provider/model fields.
const {
  modelStatus,
  loading: modelLoading,
  switching: modelSwitching,
  message: modelMessage,
  availableProviders,
  modelsFor,
  fetchStatus,
  switchModel: switchModelTo,
} = useModelStatus()
const selectedProvider = ref('')
const selectedModel = ref('')

// Catalog of selectable models for the chosen provider — powers the <datalist>.
const modelsForSelectedProvider = computed(() => modelsFor(selectedProvider.value))

async function fetchModelStatus() {
  await fetchStatus()
  if (modelStatus.value) {
    selectedProvider.value = modelStatus.value.provider
    selectedModel.value = modelStatus.value.model
  }
}

function onProviderChange() {
  const def = modelStatus.value?.available[selectedProvider.value]
  if (def) selectedModel.value = def
}

async function switchModel() {
  await switchModelTo(selectedProvider.value, selectedModel.value)
}

type GwsStatus = {
  installed: boolean
  clientConfigured?: boolean
  authenticated: boolean
  tokenValid?: boolean
  hasRefreshToken?: boolean
  user?: string
  projectId?: string
  authMethod?: string
  credentialSource?: string
  keyringBackend?: string
  scopeCount?: number
  scopes?: string[]
  enabledApiCount?: number
  enabledApis?: string[]
  message?: string
  error?: string
}
const gwsStatus = ref<GwsStatus | null>(null)

// Friendly mapping: full scope URL → short label, used in the "Connected"
// chip list so users see "Gmail, Calendar" not the raw OAuth URLs.
const SCOPE_LABELS: Record<string, string> = {
  'https://www.googleapis.com/auth/gmail.modify': 'Gmail',
  'https://www.googleapis.com/auth/gmail.readonly': 'Gmail (read)',
  'https://www.googleapis.com/auth/gmail.send': 'Gmail (send)',
  'https://www.googleapis.com/auth/calendar': 'Calendar',
  'https://www.googleapis.com/auth/calendar.readonly': 'Calendar (read)',
  'https://www.googleapis.com/auth/drive': 'Drive',
  'https://www.googleapis.com/auth/drive.file': 'Drive (file)',
  'https://www.googleapis.com/auth/drive.readonly': 'Drive (read)',
  'https://www.googleapis.com/auth/documents': 'Docs',
  'https://www.googleapis.com/auth/spreadsheets': 'Sheets',
  'https://www.googleapis.com/auth/presentations': 'Slides',
  'https://www.googleapis.com/auth/tasks': 'Tasks',
  'https://www.googleapis.com/auth/contacts.readonly': 'Contacts (read)',
  'https://www.googleapis.com/auth/contacts': 'Contacts',
  'https://www.googleapis.com/auth/userinfo.email': 'Email',
  'https://www.googleapis.com/auth/cloud-platform': 'Cloud Platform',
  'openid': 'OpenID',
  'email': 'Email',
}
function scopeLabel(s: string): string {
  return SCOPE_LABELS[s] || s.replace(/^https?:\/\/www\.googleapis\.com\/auth\//, '')
}
const connectedScopeLabels = computed(() => {
  const list = gwsStatus.value?.scopes ?? []
  // De-dup + drop the boilerplate scopes that just confirm sign-in
  const seen = new Set<string>()
  const out: string[] = []
  for (const s of list) {
    if (s === 'openid' || s === 'email' || s === 'https://www.googleapis.com/auth/userinfo.email') continue
    const label = scopeLabel(s)
    if (!seen.has(label)) { seen.add(label); out.push(label) }
  }
  return out
})
const gwsLoading = ref(false)
const gwsActionMessage = ref('')
const gwsAuthUrl = ref('')

// Client-secret upload removed — .env is the single source of truth.
// Users set GOOGLE_WORKSPACE_CLI_CLIENT_ID/SECRET in .env, then restart with
// ./run.sh all (which syncs ~/.config/gws/client_secret.json from .env).

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
                <input id="model-name" v-model="selectedModel" type="text" class="input"
                       list="model-catalog" autocomplete="off" />
                <datalist id="model-catalog">
                  <option v-for="m in modelsForSelectedProvider" :key="m" :value="m" />
                </datalist>
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
                <template v-if="gwsStatus.authenticated">
                  Connected<template v-if="gwsStatus.user"> as <span class="font-mono">{{ gwsStatus.user }}</span></template>
                </template>
                <template v-else-if="!gwsStatus.installed">gws CLI not installed</template>
                <template v-else-if="!gwsStatus.clientConfigured">OAuth credentials not configured — enter Client ID and Secret above, save, then connect</template>
                <template v-else-if="gwsStatus.hasRefreshToken && !gwsStatus.tokenValid">Token expired — reconnect to refresh</template>
                <template v-else>Not connected</template>
              </span>
            </div>

            <!-- Connected scopes + project (when authenticated) -->
            <div v-if="gwsStatus.authenticated" class="mb-3 text-xs text-gray-600">
              <div v-if="connectedScopeLabels.length" class="flex flex-wrap gap-1 mb-1">
                <span
                  v-for="label in connectedScopeLabels"
                  :key="label"
                  class="inline-block px-2 py-0.5 bg-blue-50 text-blue-700 rounded border border-blue-200"
                >{{ label }}</span>
              </div>
              <div v-if="gwsStatus.projectId" class="text-gray-500">
                Project: <span class="font-mono">{{ gwsStatus.projectId }}</span>
              </div>
            </div>

            <!-- Credentials missing — point at .env -->
            <div v-if="!gwsStatus.clientConfigured && gwsStatus.installed" class="mb-4 p-3 bg-gray-50 rounded-lg border border-gray-200">
              <p class="text-sm text-gray-700">
                Set
                <code class="bg-gray-100 px-1 py-0.5 rounded text-xs">GOOGLE_WORKSPACE_CLI_CLIENT_ID</code>
                and
                <code class="bg-gray-100 px-1 py-0.5 rounded text-xs">GOOGLE_WORKSPACE_CLI_CLIENT_SECRET</code>
                in <code class="bg-gray-100 px-1 py-0.5 rounded text-xs">.env</code>
                (download <code class="bg-gray-100 px-1 py-0.5 rounded text-xs">client_secret.json</code>
                from Google Cloud Console &rarr; Credentials &rarr; OAuth 2.0 Client ID,
                copy the two values), then restart with
                <code class="bg-gray-100 px-1 py-0.5 rounded text-xs">./run.sh all</code>.
              </p>
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
          Obsidian vault path and weather location take effect immediately.
          Google credentials live in <code>.env</code> — set them there and restart with <code>./run.sh all</code>.
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
