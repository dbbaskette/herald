import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { useSkillReloadSse } from './useSkillReloadSse'

describe('skill reload lifecycle', () => {
  it('validates events and cancels retry and reload timeout after unmount', () => {
    vi.useFakeTimers()
    const listeners: Record<string, (event: any) => void> = {}
    const source = { onopen: null, onerror: null as any, onmessage: null, close: vi.fn(), addEventListener: (name: string, fn: (event: any) => void) => { listeners[name] = fn } }
    const construct = vi.fn(function () { return source })
    vi.stubGlobal('EventSource', construct)
    let reload!: ReturnType<typeof useSkillReloadSse>
    const wrapper = mount(defineComponent({ setup() { reload = useSkillReloadSse(); return () => null } }))
    reload.setReloading()
    listeners['skill-reload']!({ data: '{"timestamp":42}' })
    expect(reload.status.value).toBe('reloading')
    listeners['skill-reload']!({ data: '{"timestamp":"2026-09-09T10:00:00Z"}' })
    expect(reload.status.value).toBe('loaded')
    expect(reload.lastLoadedAt.value).toBe('2026-09-09T10:00:00Z')
    reload.setReloading()
    vi.advanceTimersByTime(10000)
    expect(reload.status.value).toBe('error')
    listeners.status!({ data: '{"timestamp":"2026-09-09T10:01:00Z"}' })
    expect(reload.status.value).toBe('error')
    source.onerror()
    wrapper.unmount()
    listeners['skill-reload']!({ data: '{"timestamp":"2026-09-09T11:00:00Z"}' })
    vi.runAllTimers()
    expect(construct).toHaveBeenCalledTimes(1)
    expect(vi.getTimerCount()).toBe(0)
    expect(reload.lastLoadedAt.value).toBe('2026-09-09T10:00:00Z')
    vi.useRealTimers(); vi.unstubAllGlobals()
  })
})
