import { describe, expect, it, vi, afterEach } from 'vitest'
import { effectScope, ref, nextTick } from 'vue'
import { validateFrontmatter } from './skillValidation'
import { useSkillValidation } from './useSkillValidation'
const content = '---\nname: test\ndescription: >\n  A folded\n  description.\n---\nBody'
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })
describe('skill validation', () => {
  it('previews exact folded fields and catches positioned duplicates', () => {
    expect(validateFrontmatter(content)).toMatchObject({ valid: true, name: 'test', description: 'A folded description.' })
    expect(validateFrontmatter('---\nname: one\nname: two\ndescription: hi\n---').diagnostics[0]).toMatchObject({ severity: 'error', line: 3 })
  })
  it('rejects malformed, missing and non-string frontmatter', () => {
    for (const text of ['Body', '---\nname: t', '---\nname: test\n---', '---\nname: [test]\ndescription: hi\n---', '---\nname: test\ndescription: [\n---']) expect(validateFrontmatter(text).valid).toBe(false)
  })
  it('cancels requests and ignores late results after draft changes or unmount', async () => {
    vi.useFakeTimers()
    const resolvers: ((value: any) => void)[] = []
    const fetchMock = vi.fn((_url: string, _options: RequestInit) => new Promise(resolve => resolvers.push(resolve)))
    vi.stubGlobal('fetch', fetchMock)
    const scope = effectScope(), name = ref<string | null>('test'), draft = ref(content)
    const validation = scope.run(() => useSkillValidation(name, draft))!
    await vi.advanceTimersByTimeAsync(350)
    draft.value = 'broken'
    await nextTick()
    expect(fetchMock.mock.calls[0]![1].signal!.aborted).toBe(true)
    resolvers[0]!({ ok: true, json: async () => ({ ...validateFrontmatter(content), name: 'old' }) })
    await vi.advanceTimersByTimeAsync(0)
    expect(validation.result.value.valid).toBe(false)
    draft.value = content
    await nextTick()
    scope.stop()
    await vi.advanceTimersByTimeAsync(1000)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
