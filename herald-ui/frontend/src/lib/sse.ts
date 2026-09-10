export type ConnectionState = 'stopped' | 'connecting' | 'live' | 'reconnecting' | 'offline'

/** Owns the source and every retry; old callbacks become inert as soon as it closes. */
export function createSseConnection(url: string, options: {
  onState?: (state: ConnectionState) => void
  onMessage?: (event: MessageEvent) => void
  events?: Record<string, (event: MessageEvent) => void>
}) {
  let source: EventSource | null = null
  let timer: ReturnType<typeof setTimeout> | null = null
  let active = false
  let generation = 0
  let failures = 0
  const state = (value: ConnectionState) => options.onState?.(value)
  function close() {
    generation++
    source?.close()
    source = null
    if (timer !== null) clearTimeout(timer)
    timer = null
  }
  function open() {
    if (!active) return
    close()
    const current = generation
    const valid = () => active && current === generation
    state(failures ? 'reconnecting' : 'connecting')
    const fail = () => {
      if (!valid()) return
      close()
      failures++
      if (failures > 8) { state('offline'); return }
      state('reconnecting')
      timer = setTimeout(() => { timer = null; open() }, Math.min(1000 * 2 ** (failures - 1), 60000))
    }
    try {
      source = new EventSource(url)
      source.onopen = () => { if (valid()) state('live') }
      // A successful payload, not merely an open socket, resets the retry budget.
      source.onmessage = event => {
        if (!valid()) return
        options.onMessage?.(event)
      }
      for (const [name, handler] of Object.entries(options.events ?? {})) {
        source.addEventListener(name, event => { if (valid()) handler(event as MessageEvent) })
      }
      source.onerror = fail
    } catch { fail() }
  }
  return {
    start() { if (active) return; active = true; failures = 0; open() },
    stop() { active = false; close(); state('stopped') },
    retry() { if (!active) return; failures = 0; open() },
    acknowledge() { failures = 0 },
  }
}
