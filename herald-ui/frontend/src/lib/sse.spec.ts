import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createSseConnection } from './sse'
describe('cancellable SSE', () => {
  let sources: any[]
  beforeEach(() => {
    vi.useFakeTimers(); sources = []
    vi.stubGlobal('EventSource', vi.fn(function () {
      const source = { onopen: null, onmessage: null, onerror: null, close: vi.fn(), addEventListener: vi.fn() }
      sources.push(source); return source
    }))
  })
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })
  it('starts idempotently, cancels retries and ignores retired callbacks', () => {
    const message = vi.fn(), state = vi.fn()
    const stream = createSseConnection('/events', { onMessage: message, onState: state })
    stream.start(); stream.start()
    expect(sources).toHaveLength(1)
    const source = sources[0]
    source.onerror(); source.onerror(); stream.stop()
    source.onmessage({ data: '{}' }); source.onopen(); vi.runAllTimers()
    expect(sources).toHaveLength(1)
    expect(message).not.toHaveBeenCalled()
    expect(state).toHaveBeenLastCalledWith('stopped')
  })
  it('caps delays, exhausts retries even for flapping sockets, and recovers explicitly', () => {
    const state = vi.fn(), stream = createSseConnection('/events', { onState: state })
    stream.start()
    for (const delay of [1000, 2000, 4000, 8000, 16000, 32000, 60000, 60000]) {
      const count = sources.length
      sources[sources.length - 1].onopen(); sources[sources.length - 1].onerror()
      vi.advanceTimersByTime(delay - 1); expect(sources).toHaveLength(count)
      vi.advanceTimersByTime(1); expect(sources).toHaveLength(count + 1)
    }
    sources[sources.length - 1].onerror()
    expect(state).toHaveBeenLastCalledWith('offline'); expect(vi.getTimerCount()).toBe(0)
    stream.retry(); sources[sources.length - 1].onopen()
    expect(state).toHaveBeenLastCalledWith('live')
    stream.acknowledge(); sources[sources.length - 1].onerror(); vi.advanceTimersByTime(1000)
    expect(sources).toHaveLength(11); stream.stop()
  })
})
