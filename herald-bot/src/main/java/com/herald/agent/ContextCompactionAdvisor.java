package com.herald.agent;

import com.herald.memory.MemoryTools;
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
import org.springframework.core.Ordered;

import java.util.ArrayList;
import java.util.List;

/**
 * Advisor that monitors conversation history token usage and compacts old messages
 * when the estimated token count exceeds 80% of the configured context window.
 *
 * <p>When compaction triggers, the oldest messages are summarized into a condensed
 * text and stored as a memory entry via {@link MemoryTools}, ensuring context is
 * never silently lost. The compacted messages are then removed from chat history.</p>
 *
 * <p>Must run before {@code MessageChatMemoryAdvisor} so that compaction happens
 * before history is loaded into the prompt.</p>
 */
class ContextCompactionAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompactionAdvisor.class);
    static final double CEILING_RATIO = 0.8;
    static final int CHARS_PER_TOKEN = 4;

    private final ChatMemory chatMemory;
    private final MemoryTools memoryTools;
    private final int maxContextTokens;

    ContextCompactionAdvisor(ChatMemory chatMemory, MemoryTools memoryTools, int maxContextTokens) {
        this.chatMemory = chatMemory;
        this.memoryTools = memoryTools;
        this.maxContextTokens = maxContextTokens;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String conversationId = resolveConversationId(request);
        List<Message> history = chatMemory.get(conversationId);

        int estimatedTokens = estimateTokens(history);
        int ceiling = (int) (maxContextTokens * CEILING_RATIO);

        if (estimatedTokens > ceiling) {
            compactHistory(conversationId, history, ceiling);
        }

        return chain.nextCall(request);
    }

    @Override
    public String getName() {
        return "ContextCompactionAdvisor";
    }

    @Override
    public int getOrder() {
        // Run after MemoryBlockAdvisor (HIGHEST_PRECEDENCE + 100) but before
        // MessageChatMemoryAdvisor so compaction happens before history is loaded into prompt.
        return Ordered.HIGHEST_PRECEDENCE + 150;
    }

    /**
     * Estimate the token count for a list of messages using a character-based heuristic.
     */
    static int estimateTokens(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        return messages.stream()
                .mapToInt(ContextCompactionAdvisor::estimateMessageTokens)
                .sum();
    }

    static int estimateMessageTokens(Message message) {
        String text = message.getText();
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.length() / CHARS_PER_TOKEN;
    }

    int getMaxContextTokens() {
        return maxContextTokens;
    }

    private void compactHistory(String conversationId, List<Message> history, int targetTokens) {
        int currentTokens = estimateTokens(history);
        int tokensToRemove = currentTokens - targetTokens;
        int removedTokens = 0;
        int splitIndex = 0;

        for (int i = 0; i < history.size() && removedTokens < tokensToRemove; i++) {
            removedTokens += estimateMessageTokens(history.get(i));
            splitIndex = i + 1;
        }

        if (splitIndex == 0) {
            return;
        }

        // Don't remove all messages — keep at least the most recent pair
        if (splitIndex >= history.size()) {
            splitIndex = Math.max(0, history.size() - 2);
        }

        if (splitIndex == 0) {
            return;
        }

        List<Message> toSummarize = history.subList(0, splitIndex);
        String summary = buildSummary(toSummarize);

        // Store as memory entry so context is never silently lost
        String key = "context_summary_" + System.currentTimeMillis();
        memoryTools.memory_set(key, summary);

        // Replace history with remaining messages
        List<Message> remaining = new ArrayList<>(history.subList(splitIndex, history.size()));
        chatMemory.clear(conversationId);
        if (!remaining.isEmpty()) {
            chatMemory.add(conversationId, remaining);
        }

        log.info("Compacted context: removed {} messages (~{} tokens), stored summary as '{}'. "
                        + "Remaining: {} messages (~{} tokens)",
                splitIndex, removedTokens, key,
                remaining.size(), estimateTokens(remaining));
    }

    private String buildSummary(List<Message> messages) {
        var sb = new StringBuilder("Prior conversation summary (auto-compacted): ");
        for (Message msg : messages) {
            String role = switch (msg) {
                case UserMessage ignored -> "User";
                case AssistantMessage ignored -> "Assistant";
                default -> "System";
            };
            String text = msg.getText();
            if (text != null && !text.isBlank()) {
                String truncated = text.length() > 300
                        ? text.substring(0, 300) + "..."
                        : text;
                sb.append(role).append(": ").append(truncated).append(" | ");
            }
        }
        return sb.toString().stripTrailing();
    }

    private String resolveConversationId(ChatClientRequest request) {
        Object id = request.context().get("chat_memory_conversation_id");
        return id != null ? id.toString() : AgentService.DEFAULT_CONVERSATION_ID;
    }
}
