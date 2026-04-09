package com.herald.agent;

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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import java.util.ArrayList;
import java.util.List;

/**
 * Advisor that monitors conversation history token usage and compacts old messages
 * when the estimated token count exceeds 80% of the configured context window.
 *
 * <p>When compaction triggers, the oldest messages are removed from chat history.
 * The LLM-generated summary is logged but not persisted — long-term facts should
 * be saved to AutoMemoryTools by the agent during the conversation.</p>
 *
 * <p>Must run before {@code OneShotMemoryAdvisor} so that compaction happens
 * before history is loaded into the prompt.</p>
 */
class ContextCompactionAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompactionAdvisor.class);
    static final double CEILING_RATIO = 0.8;
    static final int CHARS_PER_TOKEN = 4;

    private final ChatMemory chatMemory;
    private final ChatModel summaryModel;
    private final int maxContextTokens;

    ContextCompactionAdvisor(ChatMemory chatMemory, ChatModel summaryModel, int maxContextTokens) {
        this.chatMemory = chatMemory;
        this.summaryModel = summaryModel;
        this.maxContextTokens = maxContextTokens;
    }

    private static final ThreadLocal<Boolean> COMPACTION_DONE = ThreadLocal.withInitial(() -> false);

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (COMPACTION_DONE.get()) {
            return chain.nextCall(request);
        }
        COMPACTION_DONE.set(true);

        try {
            String conversationId = resolveConversationId(request);
            List<Message> history = chatMemory.get(conversationId);

            int estimatedTokens = estimateTokens(history);
            int ceiling = (int) (maxContextTokens * CEILING_RATIO);

            if (estimatedTokens > ceiling) {
                compactHistory(conversationId, history, ceiling);
            }

            return chain.nextCall(request);
        } finally {
            COMPACTION_DONE.remove();
        }
    }

    @Override
    public String getName() {
        return "ContextCompactionAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 150;
    }

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

        // Replace history with remaining messages
        List<Message> remaining = new ArrayList<>(history.subList(splitIndex, history.size()));
        chatMemory.clear(conversationId);
        if (!remaining.isEmpty()) {
            chatMemory.add(conversationId, remaining);
        }

        log.info("Compacted context: removed {} messages (~{} tokens). "
                        + "Remaining: {} messages (~{} tokens)",
                splitIndex, removedTokens,
                remaining.size(), estimateTokens(remaining));
    }

    private static final String SUMMARY_PROMPT = """
            Summarize the following conversation into a concise paragraph. Focus on:
            - What topics were discussed
            - What decisions were made
            - What actions were taken or requested
            - Any important context that would be needed to continue the conversation

            Keep the summary under 200 words. Be specific — include names, file paths, \
            and technical details that matter. Do not include filler or generic phrases.

            Conversation:
            %s
            """;

    private String formatTranscript(List<Message> messages) {
        var sb = new StringBuilder();
        for (Message msg : messages) {
            String role = switch (msg) {
                case UserMessage ignored -> "User";
                case AssistantMessage ignored -> "Assistant";
                default -> "System";
            };
            String text = msg.getText();
            if (text != null && !text.isBlank()) {
                String truncated = text.length() > 500
                        ? text.substring(0, 500) + "..."
                        : text;
                sb.append(role).append(": ").append(truncated).append("\n\n");
            }
        }
        return sb.toString().stripTrailing();
    }

    private String resolveConversationId(ChatClientRequest request) {
        Object id = request.context().get("chat_memory_conversation_id");
        return id != null ? id.toString() : AgentService.DEFAULT_CONVERSATION_ID;
    }
}
