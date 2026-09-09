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
  resolve({ ok: true })
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
  resolve[1]!({ ok: true, text: async () => 'current' })
  await current
  resolve[0]!({ ok: true, text: async () => 'old' })
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
  resolve({ ok: true, text: async () => 'two' })
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
  resolve[1]!({ ok: true, text: async () => 'current' })
  await current
  expect(store.loading).toBe(false)
})
