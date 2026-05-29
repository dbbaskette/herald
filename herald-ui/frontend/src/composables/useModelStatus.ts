import { ref, computed } from 'vue'

export interface ModelStatus {
  provider: string
  model: string
  available: Record<string, string>
  catalog?: Record<string, string[]>
}

/**
 * Shared model-switcher state + actions, consumed by both the chat console
 * header dropdown and the Settings page form. Keeps the `/api/model` fetch,
 * per-provider catalog logic, and switch call in one place so the two UIs
 * can't drift (they used to each carry their own copy).
 */
export function useModelStatus() {
  const modelStatus = ref<ModelStatus | null>(null)
  const loading = ref(false)
  const switching = ref(false)
  const rescanning = ref(false)
  const message = ref('')

  const availableProviders = computed(() =>
    modelStatus.value ? Object.keys(modelStatus.value.available).filter((k) => k !== 'error') : []
  )

  /** Selectable models for a provider — catalog if present, else the single default. */
  function modelsFor(provider: string): string[] {
    const cat = modelStatus.value?.catalog?.[provider]
    if (cat && cat.length) return cat
    const def = modelStatus.value?.available[provider]
    return def ? [def] : []
  }

  async function fetchStatus(): Promise<void> {
    loading.value = true
    try {
      const res = await fetch('/api/model')
      if (res.ok) modelStatus.value = await res.json()
    } catch {
      /* bot offline — leave modelStatus null */
    } finally {
      loading.value = false
    }
  }

  /** Switch to provider/model. Returns true on success. No-op when already active. */
  async function switchModel(provider: string, model: string): Promise<boolean> {
    if (!modelStatus.value || !model) return false
    if (provider === modelStatus.value.provider && model === modelStatus.value.model) return true
    switching.value = true
    message.value = ''
    try {
      const res = await fetch('/api/model', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ provider, model }),
      })
      const data = await res.json()
      if (res.ok) {
        modelStatus.value = data
        message.value = `Switched to ${data.provider}/${data.model}`
        return true
      }
      message.value = data?.available?.error || 'Switch failed'
      return false
    } catch {
      message.value = 'Bot unreachable'
      return false
    } finally {
      switching.value = false
    }
  }

  /**
   * Re-query LM Studio for its loaded models and refresh the catalog. Lets the
   * user swap a model in LM Studio and pick it here without restarting the bot.
   */
  async function rescanLmStudio(): Promise<void> {
    rescanning.value = true
    message.value = ''
    try {
      const res = await fetch('/api/model/rescan', { method: 'POST' })
      const data = await res.json()
      if (res.ok) {
        modelStatus.value = data
        const count = data?.catalog?.lmstudio?.length ?? 0
        message.value = `Rescanned LM Studio — ${count} model(s) available`
      } else {
        message.value = data?.error || 'Rescan failed'
      }
    } catch {
      message.value = 'Bot unreachable'
    } finally {
      rescanning.value = false
    }
  }

  return {
    modelStatus,
    loading,
    switching,
    rescanning,
    message,
    availableProviders,
    modelsFor,
    fetchStatus,
    switchModel,
    rescanLmStudio,
  }
}
