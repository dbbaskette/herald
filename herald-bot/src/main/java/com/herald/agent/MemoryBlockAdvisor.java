package com.herald.agent;

import com.herald.memory.MemoryTools;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.core.Ordered;

/**
 * Advisor that injects the persistent memory block (key-value facts) into the system
 * prompt on each turn. This ensures the model always has access to stored knowledge
 * without requiring the caller to manage it.
 */
class MemoryBlockAdvisor implements CallAdvisor {

    private final MemoryTools memoryTools;

    MemoryBlockAdvisor(MemoryTools memoryTools) {
        this.memoryTools = memoryTools;
    }

    private static final ThreadLocal<Boolean> INJECTED = ThreadLocal.withInitial(() -> false);

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (INJECTED.get()) {
            return chain.nextCall(request);
        }
        INJECTED.set(true);
        try {
            String memoryBlock = memoryTools.formatForSystemPrompt();
            if (!memoryBlock.isEmpty()) {
                String delimited = "\n\n<memory>\n" + memoryBlock + "</memory>";
                request = request.mutate()
                        .prompt(request.prompt().augmentSystemMessage(
                                existing -> new SystemMessage(existing.getText() + delimited)))
                        .build();
            }
            return chain.nextCall(request);
        } finally {
            INJECTED.remove();
        }
    }

    @Override
    public String getName() {
        return "MemoryBlockAdvisor";
    }

    @Override
    public int getOrder() {
        // Run before MessageChatMemoryAdvisor (which uses DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER)
        // so memory block appears between system prompt and conversation history.
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
