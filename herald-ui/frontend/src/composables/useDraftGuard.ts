import { onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'

/** A failed save (or edits made during a save) must never authorize leaving. */
export function useDraftGuard(dirty: () => boolean, save: () => Promise<boolean>, discard: () => void) {
  const open = ref(false)
  const busy = ref(false)
  let resolve: ((allowed: boolean) => void) | undefined
  function allow(): Promise<boolean> {
    if (busy.value || open.value) return Promise.resolve(false)
    if (!dirty()) return Promise.resolve(true)
    open.value = true
    return new Promise(answer => { resolve = answer })
  }
  async function choose(choice: 'save' | 'discard' | 'stay') {
    if (busy.value) return
    busy.value = true
    let allowed = false
    try {
      if (choice === 'discard') { discard(); allowed = true }
      if (choice === 'save') allowed = await save() && !dirty()
    } finally {
      busy.value = false
      open.value = false
      resolve?.(allowed)
      resolve = undefined
    }
  }
  function unload(event: BeforeUnloadEvent) {
    if (dirty()) { event.preventDefault(); event.returnValue = '' }
  }
  onBeforeRouteLeave(() => allow())
  onMounted(() => window.addEventListener('beforeunload', unload))
  onBeforeUnmount(() => { window.removeEventListener('beforeunload', unload); resolve?.(false) })
  return { open, busy, allow, choose }
}
