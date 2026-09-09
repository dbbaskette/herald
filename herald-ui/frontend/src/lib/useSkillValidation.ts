import { ref, watch, onScopeDispose, type Ref } from 'vue'
import { validateFrontmatter, type SkillValidation } from './skillValidation'

export function useSkillValidation(name: Ref<string | null>, content: Ref<string>) {
  const result = ref<SkillValidation>(validateFrontmatter(''))
  const pending = ref(false)
  const referenceError = ref('')
  let generation = 0
  let timer: ReturnType<typeof setTimeout> | undefined
  let request: AbortController | undefined
  watch([name, content], () => {
    const current = ++generation
    clearTimeout(timer)
    request?.abort()
    result.value = validateFrontmatter(content.value)
    referenceError.value = ''
    pending.value = !!name.value && result.value.valid
    if (!pending.value) return
    const selected = name.value, draft = content.value
    timer = setTimeout(async () => {
      request = new AbortController()
      try {
        const response = await fetch('/api/skills/validate', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ name: selected, content: draft }), signal: request.signal,
        })
        if (!response.ok) throw new Error('Reference checks are unavailable. YAML validation still works.')
        const data = await response.json()
        if (typeof data.valid !== 'boolean' || !Array.isArray(data.diagnostics)
            || typeof data.name !== 'string' || typeof data.description !== 'string'
            || !data.diagnostics.every((d: any) => ['error', 'warning'].includes(d.severity) && typeof d.message === 'string' && Number.isInteger(d.line) && d.line > 0 && Number.isInteger(d.column) && d.column > 0)) {
          throw new Error('Invalid validation response. Reference checks are unavailable.')
        }
        if (current === generation) result.value = data
      } catch (error) {
        if (current === generation) referenceError.value = error instanceof Error ? error.message : 'Reference checks are unavailable.'
      } finally { if (current === generation) pending.value = false }
    }, 350)
  }, { immediate: true })
  onScopeDispose(() => { ++generation; clearTimeout(timer); request?.abort() })
  return { result, pending, referenceError }
}
