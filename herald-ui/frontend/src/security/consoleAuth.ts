import { ref } from 'vue'
export const authenticated = ref(false)
export const authEnabled = ref(false)
export const authChecked = ref(false)
export const authError = ref('')
let csrf = ''
let installed = false
let originalFetch: typeof fetch

/** Add only the CSRF nonce, never the bearer token, to same-origin API requests. */
export function installConsoleAuth() {
  if (installed) return
  installed = true
  originalFetch = window.fetch.bind(window)
  window.fetch = async (input, init) => {
    const target = new URL(input instanceof Request ? input.url : String(input), window.location.href)
    const ours = target.origin === window.location.origin && (target.pathname.startsWith('/api/') || target.pathname === '/auth/session')
    let options = init
    if (ours && csrf) {
      const headers = new Headers(init?.headers ?? (input instanceof Request ? input.headers : undefined))
      headers.set('X-Herald-CSRF', csrf)
      options = { ...init, headers }
    }
    const response = await originalFetch(input, options)
    if (ours && target.pathname.startsWith('/api/') && response.status === 401) {
      authenticated.value = false
      authError.value = 'Your session ended. Sign in to continue; your draft is still here.'
    }
    return response
  }
}
function apply(data: any) {
  if (typeof data?.enabled !== 'boolean' || typeof data?.authenticated !== 'boolean' || typeof data?.csrf !== 'string') throw new Error('Invalid session response.')
  authEnabled.value = data.enabled; authenticated.value = data.authenticated; csrf = data.csrf
  authChecked.value = true
}
export async function checkSession() {
  try {
    const response = await fetch('/auth/session', { cache: 'no-store' })
    if (!response.ok) throw new Error('Cannot check console access. Retry when the console is available.')
    apply(await response.json()); authError.value = ''
  } catch (error) { authError.value = error instanceof Error ? error.message : 'Cannot check console access.' }
}
export async function signIn(token: string) {
  authError.value = ''
  try {
    const response = await fetch('/auth/session', { method: 'POST', headers: { Authorization: `Bearer ${token}` } })
    if (!response.ok) throw new Error(response.status === 401 ? 'The console token was not accepted.' : 'Sign-in failed. Please retry.')
    // Verify cookie roundtrip; browsers can reject Secure cookies on a plain HTTP origin.
    await response.json()
    const status = await fetch('/auth/session', { cache: 'no-store' })
    if (!status.ok) throw new Error('Cannot verify console session.')
    apply(await status.json())
    if (!authenticated.value) throw new Error('Session cookie was not accepted. Use HTTPS, or configure insecure cookies only for a loopback SSH tunnel.')
    return true
  } catch (error) { authError.value = error instanceof Error ? error.message : 'Sign-in failed.'; return false }
}
export async function signOut() {
  try {
    const response = await fetch('/auth/session', { method: 'DELETE' })
    if (!response.ok) throw new Error('Sign-out failed.')
    authenticated.value = false; csrf = ''; return true
  } catch { authError.value = 'Sign-out failed. Please retry.'; return false }
}
