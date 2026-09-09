import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ConversationList from './ConversationList.vue'
import { useChatStore } from '@/stores/chat'
import { useConversationsStore } from '@/stores/conversations'

describe('ConversationList', () => {
  it('keeps select and delete as independent keyboard-operable buttons', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve([]),
    }))
    const pinia = createPinia()
    const wrapper = mount(ConversationList, { global: { plugins: [pinia] } })
    const conversations = useConversationsStore()
    const chat = useChatStore()
    conversations.items = [{
      id: 'conversation-1',
      title: 'Release notes',
      turnCount: 3,
      firstTurnAt: new Date().toISOString(),
      lastTurnAt: new Date().toISOString(),
    }]
    const switchConversation = vi.spyOn(chat, 'switchConversation').mockResolvedValue()
    const deleteConversation = vi.spyOn(conversations, 'deleteConversation').mockResolvedValue(true)
    await wrapper.vm.$nextTick()

    expect(wrapper.find('button button').exists()).toBe(false)
    const selectButton = wrapper.get('button.cl-item-select')
    const deleteButton = wrapper.get('button.cl-item-delete')
    expect(selectButton.element.tagName).toBe('BUTTON')
    expect(deleteButton.element.tagName).toBe('BUTTON')

    await selectButton.trigger('click')
    expect(switchConversation).toHaveBeenCalledOnce()

    switchConversation.mockClear()
    await deleteButton.trigger('click')
    expect(switchConversation).not.toHaveBeenCalled()
    await wrapper.get('.modal-footer .btn-danger').trigger('click')
    expect(deleteConversation).toHaveBeenCalledWith('conversation-1')
  })
})
