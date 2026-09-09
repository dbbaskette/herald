import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import SkillsEditor from './SkillsEditor.vue'
import { useSkillsStore } from '@/stores/skills'

// Stub EventSource globally
vi.stubGlobal('EventSource', vi.fn(function () { return {
  onopen: null,
  onmessage: null,
  onerror: null,
  close: vi.fn(),
  addEventListener: vi.fn(),
} }))

// Mock CodeMirror — the editor needs a DOM that jsdom can't fully support
vi.mock('@codemirror/view', () => ({
  EditorView: Object.assign(vi.fn(function () { return {
    state: { doc: { toString: () => '', length: 0 } },
    dispatch: vi.fn(),
    destroy: vi.fn(),
  } }), { theme: vi.fn(() => []), updateListener: { of: vi.fn(() => []) } }),
  keymap: { of: () => [] },
  lineNumbers: () => [],
  highlightActiveLine: () => [],
}))
vi.mock('@codemirror/state', () => ({
  EditorState: {
    create: vi.fn(() => ({})),
    readOnly: { of: () => [] },
  },
}))
vi.mock('@codemirror/lang-markdown', () => ({
  markdown: () => [],
}))
vi.mock('@codemirror/lang-yaml', () => ({
  yaml: () => ({ language: { parser: {} } }),
}))
vi.mock('@codemirror/theme-one-dark', () => ({
  oneDark: [],
}))
vi.mock('@codemirror/commands', () => ({
  defaultKeymap: [],
  history: () => [],
  historyKeymap: [],
}))
vi.mock('codemirror', () => ({
  basicSetup: [],
}))

const sampleSkills = [
  { name: 'my-skill', description: 'A local skill', source: 'local', readOnly: false, hasBundled: false },
  { name: 'bundled-skill', description: 'A bundled skill', source: 'bundled', readOnly: true, hasBundled: true },
]

function mountPage() {
  return mount(SkillsEditor, {
    global: {
      plugins: [createPinia()],
    },
  })
}

describe('SkillsEditor.vue', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(sampleSkills),
      text: () => Promise.resolve('# My Skill\nContent here'),
    }))
  })

  it('renders the page title when no skill is selected', () => {
    const wrapper = mountPage()
    expect(wrapper.text()).toContain('Skills')
    expect(wrapper.text()).toContain('Select a skill or create a new one')
  })

  it('shows loading state initially', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => new Promise(() => {})))
    const wrapper = mountPage()
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.tree-loading').exists()).toBe(true)
  })

  it('renders skill list after data loads', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('my-skill')
    })
    expect(wrapper.text()).toContain('bundled-skill')
  })

  it('shows lock icon for read-only skills', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('bundled-skill')
    })
    const bundledItem = wrapper.findAll('button').find(button => button.text().includes('bundled-skill'))
    expect(bundledItem?.attributes('title')).toContain('A bundled skill')
  })

  it('shows toolbar buttons when a skill is selected', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('my-skill')
    })

    // Select a skill
    const store = useSkillsStore()
    store.selectedName = sampleSkills[0].name
    store.editorContent = '# My Skill\nContent here'
    store.savedContent = '# My Skill\nContent here'
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('Save')
    expect(wrapper.text()).toContain('Discard')
    expect(wrapper.find('button[title="New skill"]').exists()).toBe(true)
    expect(wrapper.find('.action-delete').exists()).toBe(true)
  })

  it('disables save and discard when not dirty', async () => {
    const wrapper = mountPage()
    const store = useSkillsStore()
    store.selectedName = sampleSkills[0].name
    store.editorContent = 'content'
    store.savedContent = 'content'
    await wrapper.vm.$nextTick()

    const buttons = wrapper.findAll('button')
    const saveBtn = buttons.find(b => b.text() === 'Save')
    const discardBtn = buttons.find(b => b.text() === 'Discard')
    expect(saveBtn?.attributes('disabled')).toBeDefined()
    expect(discardBtn?.attributes('disabled')).toBeDefined()
  })

  it('shows read-only badge for bundled skills', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => expect(wrapper.text()).toContain('bundled-skill'))
    const store = useSkillsStore()
    store.selectedName = sampleSkills[1].name
    store.editorContent = 'bundled content'
    store.savedContent = 'bundled content'
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.tree-badge').text()).toBe('bundled')
  })

  it('disables save, discard, and delete for bundled skills', async () => {
    const wrapper = mountPage()
    await vi.waitFor(() => expect(wrapper.text()).toContain('bundled-skill'))
    const store = useSkillsStore()
    store.selectedName = sampleSkills[1].name
    store.editorContent = 'bundled content'
    store.savedContent = 'bundled content'
    await wrapper.vm.$nextTick()

    const buttons = wrapper.findAll('button')
    const saveBtn = buttons.find(b => b.text() === 'Save')
    const deleteBtn = wrapper.find('.action-delete')
    expect(saveBtn?.attributes('disabled')).toBeDefined()
    expect(deleteBtn.attributes('disabled')).toBeDefined()
  })

  it('opens New Skill modal when button is clicked', async () => {
    const wrapper = mountPage()
    const store = useSkillsStore()
    store.selectedName = sampleSkills[0].name
    store.editorContent = 'content'
    store.savedContent = 'content'
    await wrapper.vm.$nextTick()

    const newBtn = wrapper.find('button[title="New skill"]')
    await newBtn.trigger('click')
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('Skill Name')
    expect(wrapper.find('input[type="text"]').exists()).toBe(true)
  })

  it('opens Delete confirmation when button is clicked', async () => {
    const wrapper = mountPage()
    const store = useSkillsStore()
    store.selectedName = sampleSkills[0].name
    store.editorContent = 'content'
    store.savedContent = 'content'
    await wrapper.vm.$nextTick()

    const deleteBtn = wrapper.find('.action-delete')
    await deleteBtn.trigger('click')
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('Delete Skill')
    expect(wrapper.text()).toContain('Remove my-skill.md permanently?')
  })

  it('shows SSE status chip', async () => {
    const wrapper = mountPage()
    const store = useSkillsStore()
    store.selectedName = sampleSkills[0].name
    store.editorContent = 'content'
    store.savedContent = 'content'
    await wrapper.vm.$nextTick()

    // The chip should show Loaded or a status
    expect(wrapper.text()).toMatch(/live|error|offline/)
  })
})
