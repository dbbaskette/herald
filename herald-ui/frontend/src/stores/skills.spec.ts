import { beforeEach, afterEach, it, expect, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useSkillsStore } from './skills'
beforeEach(() => setActivePinia(createPinia()))
afterEach(() => vi.unstubAllGlobals())
it('does not mark typing during a save as saved or replace a newer selected file', async () => {
  let resolve: (value: any) => void = () => {}
  vi.stubGlobal('fetch', vi.fn(() => new Promise(done => { resolve = done })))
  const store = useSkillsStore()
  store.selectedName = 'one'; store.savedContent = 'old'; store.editorContent = 'submitted'
  const save = store.saveSkill()
  store.editorContent = 'still typing'
  resolve({ ok: true, headers: new Headers({ ETag: '"version"' }) })
  expect(await save).toBe(true)
  expect(store.savedContent).toBe('submitted')
  expect(store.editorContent).toBe('still typing')
  expect(store.isDirty).toBe(true)
})
it('ignores slow file selection responses', async () => {
  const resolve: ((value: any) => void)[] = []
  vi.stubGlobal('fetch', vi.fn(() => new Promise(done => resolve.push(done))))
  const store = useSkillsStore()
  const old = store.selectSkill('old'), current = store.selectSkill('current')
  resolve[1]!({ ok: true, headers: new Headers({ ETag: '"version"' }), text: async () => 'current' })
  await current
  resolve[0]!({ ok: true, headers: new Headers({ ETag: '"version"' }), text: async () => 'old' })
  await old
  expect(store.selectedName).toBe('current')
  expect(store.editorContent).toBe('current')
})

it('keeps edits made while another skill is loading', async () => {
  let resolve: (value: any) => void = () => {}
  vi.stubGlobal('fetch', vi.fn(() => new Promise(done => { resolve = done })))
  const store = useSkillsStore()
  store.selectedName = 'one'; store.savedContent = 'original'; store.editorContent = 'original'
  const selection = store.selectSkill('two')
  store.editorContent = 'new draft'
  resolve({ ok: true, headers: new Headers({ ETag: '"version"' }), text: async () => 'two' })
  await selection
  expect(store.selectedName).toBe('one')
  expect(store.editorContent).toBe('new draft')
  expect(store.error).toContain('draft changed')
})

it('ignores old selection failures while a newer selection is pending', async () => {
  const reject: ((value: any) => void)[] = [], resolve: ((value: any) => void)[] = []
  vi.stubGlobal('fetch', vi.fn(() => new Promise((done, fail) => { resolve.push(done); reject.push(fail) })))
  const store = useSkillsStore()
  const old = store.selectSkill('old'), current = store.selectSkill('current')
  reject[0]!(new Error('old failure'))
  await old
  expect(store.error).toBeNull()
  expect(store.loading).toBe(true)
  resolve[1]!({ ok: true, headers: new Headers({ ETag: '"version"' }), text: async () => 'current' })
  await current
  expect(store.loading).toBe(false)
})

it('preserves both versions on conflict until explicit reconciliation', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(new Response('original', { headers: { ETag: '"one"' } }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ content: 'external', version: '"two"' }), { status: 412 }))
    .mockResolvedValueOnce(new Response('', { headers: { ETag: '"three"' } }))
  vi.stubGlobal('fetch', fetcher)
  const store = useSkillsStore()
  await store.selectSkill('one'); store.editorContent = 'my draft'
  expect(await store.saveSkill()).toBe(false)
  expect(store.editorContent).toBe('my draft'); expect(store.savedContent).toBe('original')
  expect(store.conflict?.content).toBe('external')
  expect(fetcher.mock.calls[1]![1].headers['If-Match']).toBe('"one"')
  store.resolveConflict(false)
  expect(store.editorContent).toBe('my draft'); expect(store.savedContent).toBe('external')
  expect(await store.saveSkill()).toBe(true)
  expect(fetcher.mock.calls[2]![1].headers['If-Match']).toBe('"two"')
})
it('preserves the draft on a failed save', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
  const store = useSkillsStore()
  store.selectedName = 'one'; store.savedContent = 'saved'; store.editorContent = 'draft'
  expect(await store.saveSkill()).toBe(false)
  expect(store.editorContent).toBe('draft'); expect(store.savedContent).toBe('saved')
})

it.each(['create', 'list'])('keeps edits made while the %s request for a new skill is pending', async stage => {
  let complete: (response: Response) => void = () => {}
  const pending = new Promise<Response>(resolve => { complete = resolve })
  const fetcher = vi.fn(async (_url: string, options?: RequestInit) => {
    if (options?.method === 'POST') return stage === 'create' ? pending : new Response('', { status: 201 })
    return stage === 'list' ? pending : new Response(JSON.stringify([{ name: 'new-skill' }]))
  })
  vi.stubGlobal('fetch', fetcher)
  const store = useSkillsStore()
  store.selectedName = 'existing'; store.savedContent = 'original'; store.editorContent = 'original'
  const creation = store.createSkill('new-skill')
  if (stage === 'list') await vi.waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2))
  store.editorContent = 'draft typed after closing the modal'
  complete(stage === 'create' ? new Response('', { status: 201 }) : new Response(JSON.stringify([{ name: 'new-skill' }])))
  expect(await creation).toBe(true)
  expect(store.selectedName).toBe('existing')
  expect(store.editorContent).toBe('draft typed after closing the modal')
  expect(store.savedContent).toBe('original')
  expect(store.skills.some(skill => skill.name === 'new-skill')).toBe(true)
  expect(fetcher).toHaveBeenCalledTimes(2)
})

it('does not override a newer selection when creation finishes', async () => {
  let complete: (response: Response) => void = () => {}
  const fetcher = vi.fn(async (url: string, options?: RequestInit) => {
    if (options?.method === 'POST') return new Promise<Response>(resolve => { complete = resolve })
    if (url === '/api/skills') return new Response(JSON.stringify([{ name: 'new-skill' }]))
    return new Response('same content', { headers: { ETag: '"other"' } })
  })
  vi.stubGlobal('fetch', fetcher)
  const store = useSkillsStore()
  store.selectedName = 'existing'; store.savedContent = 'same content'; store.editorContent = 'same content'
  const creation = store.createSkill('new-skill')
  await store.selectSkill('other')
  complete(new Response('', { status: 201 }))
  expect(await creation).toBe(true)
  expect(store.selectedName).toBe('other')
  expect(fetcher).toHaveBeenCalledTimes(3)
})
