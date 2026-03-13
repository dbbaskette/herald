package com.herald.agent;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

/**
 * A memory advisor that loads and saves conversation history exactly <b>once</b> per
 * top-level request, regardless of how many tool-call iterations occur.
 *
 * <p>Spring AI's built-in {@code MessageChatMemoryAdvisor} re-runs on every iteration
 * of the {@code ToolCallAdvisor} loop (because the full advisor chain is re-entered).
 * Each iteration loads history, adds new tool messages, and saves — causing exponential
 * message growth in the database. This advisor uses a {@link ThreadLocal} flag to ensure
 * memory is loaded before the first model call and saved after the final response, with
 * all intermediate tool iterations passed through untouched.</p>
 */
class OneShotMemoryAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(OneShotMemoryAdvisor.class);
    private static final String CONVERSATION_ID_KEY = "chat_memory_conversation_id";
    private static final String DEFAULT_CONVERSATION_ID = "default";

    private static final ThreadLocal<Boolean> MEMORY_LOADED = ThreadLocal.withInitial(() -> false);

    private final ChatMemory chatMemory;
    private final int maxMessages;

    OneShotMemoryAdvisor(ChatMemory chatMemory, int maxMessages) {
        this.chatMemory = chatMemory;
        this.maxMessages = maxMessages;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (MEMORY_LOADED.get()) {
            // Tool-call re-entry: skip memory load/save, pass through
            return chain.nextCall(request);
        }

        // First invocation: load memory, run the full chain (including all tool iterations),
        // then save the new messages.
        MEMORY_LOADED.set(true);

        String conversationId = resolveConversationId(request);

        try {
            // Capture the user message(s) from THIS turn immediately — before the chain
            // runs, because ToolCallAdvisor mutates the prompt's instruction list during
            // tool-call iterations, which can duplicate user messages.
            List<Message> originalUserMessages = request.prompt().getInstructions().stream()
                    .filter(UserMessage.class::isInstance)
                    .toList();

            // Load conversation history (only the most recent messages) and prepend to the prompt
            List<Message> history = chatMemory.get(conversationId);
            if (history.size() > maxMessages) {
                history = history.subList(history.size() - maxMessages, history.size());
            }
            List<Message> currentMessages = request.prompt().getInstructions();

            List<Message> combined = new ArrayList<>(history.size() + currentMessages.size());
            combined.addAll(history);
            combined.addAll(currentMessages);

            request = request.mutate()
                    .prompt(new Prompt(combined, request.prompt().getOptions()))
                    .build();

            // Run the full chain (ToolCallAdvisor loop happens here)
            ChatClientResponse response = chain.nextCall(request);

            // Save only the captured user message + assistant response
            saveNewMessages(conversationId, originalUserMessages, response);

            return response;
        } finally {
            MEMORY_LOADED.remove();
        }
    }

    private void saveNewMessages(String conversationId, List<Message> userMessages, ChatClientResponse response) {
        try {
            List<Message> toSave = new ArrayList<>(userMessages);

            // Save the final assistant response (skip empty tool-call-only responses)
            if (response != null && response.chatResponse() != null
                    && response.chatResponse().getResult() != null) {
                AssistantMessage output = response.chatResponse().getResult().getOutput();
                if (output != null && output.getText() != null && !output.getText().isBlank()) {
                    toSave.add(output);
                }
            }

            if (!toSave.isEmpty()) {
                chatMemory.add(conversationId, toSave);
            }
        } catch (Exception e) {
            log.warn("Failed to save conversation memory: {}", e.getMessage());
        }
    }

    private String resolveConversationId(ChatClientRequest request) {
        Object id = request.context().get(CONVERSATION_ID_KEY);
        return id instanceof String s && !s.isBlank() ? s : DEFAULT_CONVERSATION_ID;
    }

    @Override
    public String getName() {
        return "OneShotMemoryAdvisor";
    }

    @Override
    public int getOrder() {
        // Same position as MessageChatMemoryAdvisor
        return Ordered.LOWEST_PRECEDENCE - 100;
    }
}
