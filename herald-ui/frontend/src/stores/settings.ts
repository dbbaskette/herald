import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

const API = '/api/settings'

export interface SettingDef {
  key: string
  label: string
  description: string
  placeholder: string
  group: string
  secret?: boolean
}

export const settingDefs: SettingDef[] = [
  {
    key: 'agent.persona',
    label: 'Agent Persona',
    description: 'Name the agent uses to identify itself',
    placeholder: 'Herald',
    group: 'Agent',
  },
  {
    key: 'agent.max-context-tokens',
    label: 'Max Context Tokens',
    description: 'Context window limit before compaction triggers',
    placeholder: '200000',
    group: 'Agent',
  },
  {
    key: 'cron.timezone',
    label: 'Timezone',
    description: 'Timezone for scheduled jobs and briefings',
    placeholder: 'America/New_York',
    group: 'Agent',
  },
  {
    key: 'obsidian.vault-path',
    label: 'Obsidian Vault Path',
    description: 'Path to the Herald-Memory Obsidian vault',
    placeholder: '~/Documents/Herald-Memory',
    group: 'Integrations',
  },
  {
    key: 'weather.location',
    label: 'Weather Location',
    description: 'Default location for weather lookups',
    placeholder: 'Raleigh, NC',
    group: 'Integrations',
  },
  {
    key: 'google.client-id',
    label: 'Google OAuth Client ID',
    description: 'From Google Cloud Console → APIs & Services → Credentials',
    placeholder: 'xxxx.apps.googleusercontent.com',
    group: 'Google Workspace',
  },
  {
    key: 'google.client-secret',
    label: 'Google OAuth Client Secret',
    description: 'From the same OAuth 2.0 Client ID',
    placeholder: 'GOCSPX-...',
    group: 'Google Workspace',
    secret: true,
  },
]

export const useSettingsStore = defineStore('settings', () => {
  const settings = ref<Record<string, string>>({})
  const loading = ref(false)
  const saving = ref(false)
  const error = ref<string | null>(null)
  const saved = ref(false)

  const groups = computed(() => {
    const map: Record<string, SettingDef[]> = {}
    for (const def of settingDefs) {
      if (!map[def.group]) map[def.group] = []
      map[def.group].push(def)
    }
    return map
  })

  async function fetchSettings() {
    loading.value = true
    error.value = null
    try {
      const res = await fetch(API)
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      settings.value = await res.json()
    } catch (e: any) {
      error.value = e.message
    } finally {
      loading.value = false
    }
  }

  async function saveSettings(updates: Record<string, string>) {
    saving.value = true
    error.value = null
    saved.value = false
    try {
      const res = await fetch(API, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(updates),
      })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      settings.value = await res.json()
      saved.value = true
      setTimeout(() => { saved.value = false }, 3000)
    } catch (e: any) {
      error.value = e.message
    } finally {
      saving.value = false
    }
  }

  return { settings, loading, saving, error, saved, groups, fetchSettings, saveSettings }
})
