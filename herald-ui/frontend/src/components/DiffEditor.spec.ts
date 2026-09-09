import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import DiffEditor from './DiffEditor.vue'

describe('DiffEditor', () => {
  it('reverts the editable document to the original and emits the update', async () => {
    const wrapper = mount(DiffEditor, {
      props: { original: 'bundled baseline\n', modified: 'local override\n' },
      attachTo: document.body,
    })

    const panes = wrapper.findAll('.cm-content')
    expect(panes).toHaveLength(2)
    expect(panes[0].text()).toContain('bundled baseline')
    expect(panes[1].text()).toContain('local override')

    await vi.waitFor(() => {
      expect(wrapper.find('button[aria-label="Revert this chunk"]').exists()).toBe(true)
    })
    const revert = wrapper.find('button[aria-label="Revert this chunk"]')
    await revert.trigger('mousedown')

    expect(wrapper.findAll('.cm-content')[0].text()).toContain('bundled baseline')
    expect(wrapper.findAll('.cm-content')[1].text()).toContain('bundled baseline')
    const updates = wrapper.emitted('update:modified') ?? []
    expect(updates[updates.length - 1]).toEqual(['bundled baseline\n'])
    wrapper.unmount()
  })

  it('does not expose mutation controls when the modified pane is read-only', () => {
    const wrapper = mount(DiffEditor, {
      props: { original: 'bundled\n', modified: 'local\n', readOnly: true },
      attachTo: document.body,
    })

    expect(wrapper.find('button[aria-label="Revert this chunk"]').exists()).toBe(false)
    expect(wrapper.findAll('.cm-content')[0].text()).toContain('bundled')
    expect(wrapper.findAll('.cm-content')[1].text()).toContain('local')
    wrapper.unmount()
  })
})
