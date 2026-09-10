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
  // Google client-id / client-secret removed — .env is the single source of
  // truth (run.sh syncs ~/.config/gws/client_secret.json from it). Connect /
  // Disconnect lives in the "Google Account" panel further down the page.
]

export interface SettingStatus {
  saved: string
  effective: string | null
  source: string
  environmentVariable: string | null
  application: 'unknown' | 'matches-runtime' | 'configuration-managed'
  restartRequired: boolean
}
export interface SettingsResult {
  success: boolean
  savedSettings: Record<string, string>
  settings: Record<string, SettingStatus>
  validationErrors: Record<string, string>
  error: string | null
  message: string
}
export type SaveOutcome = { success: true } | { success: false; reason: 'busy' | 'validation' | 'request' }

export function validateSettings(values: Record<string, string>): Record<string, string> {
  const errors: Record<string, string> = {}
  for (const [key, value] of Object.entries(values)) {
    if (value.length > 4096) errors[key] = 'Use at most 4096 characters.'
    else if (key === 'agent.max-context-tokens' && (!/^\d+$/.test(value) || Number(value) < 1 || Number(value) > 2_000_000))
      errors[key] = 'Enter a whole number from 1 to 2000000.'
    else if (key === 'cron.timezone') {
      try {
        if (!value || value.trim() !== value) throw new Error('Invalid timezone')
        // Java also accepts explicit fixed offsets, which Intl may not support.
        const offset = /^(?:UTC|GMT|UT)?([+-])(\d{2})(?::?(\d{2}))?$/.exec(value)
        if (offset) {
          const hours = Number(offset[2]); const minutes = Number(offset[3] ?? 0)
          if (hours > 18 || minutes > 59 || (hours === 18 && minutes !== 0)) throw new Error('Invalid offset')
        } else new Intl.DateTimeFormat('en-US', { timeZone: value }).format()
      } catch { errors[key] = 'Enter a valid timezone, for example America/New_York or UTC.' }
    }
  }
  return errors
}

export const useSettingsStore = defineStore('settings', () => {
  const settings = ref<Record<string, string>>({})
  const loading = ref(false)
  const saving = ref(false)
  const loadError = ref<string | null>(null)
  const saveError = ref<string | null>(null)
  const validationErrors = ref<Record<string, string>>({})
  const statuses = ref<Record<string, SettingStatus>>({})
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
    loadError.value = null
    try {
      const res = await fetch(API)
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data: SettingsResult = await res.json()
      if (!data.success || !data.savedSettings || !data.settings) throw new Error(data.message || 'Invalid settings response')
      settings.value = data.savedSettings
      statuses.value = data.settings
      return true
    } catch (e: any) {
      loadError.value = e.message
      return false
    } finally {
      loading.value = false
    }
  }

  async function saveSettings(updates: Record<string, string>): Promise<SaveOutcome> {
    if (saving.value) return { success: false, reason: 'busy' }
    validationErrors.value = validateSettings(updates)
    if (Object.keys(validationErrors.value).length) return { success: false, reason: 'validation' }
    saving.value = true
    saved.value = false
    try {
      const res = await fetch(API, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(updates),
      })
      const data: SettingsResult = await res.json()
      if (!res.ok || !data.success) {
        validationErrors.value = data.validationErrors ?? {}
        throw new Error(data.message || `Save failed (HTTP ${res.status})`)
      }
      if (!data.savedSettings || !data.settings) throw new Error('Invalid save response')
      settings.value = data.savedSettings
      statuses.value = data.settings
      saveError.value = null
      saved.value = true
      return { success: true }
    } catch (e: any) {
      saveError.value = e.message
      return { success: false, reason: 'request' }
    } finally {
      saving.value = false
    }
  }

  return { settings, statuses, loading, saving, loadError, saveError, validationErrors, saved, groups, fetchSettings, saveSettings }
})
