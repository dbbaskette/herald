package com.herald.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Advisor that monitors conversation history token usage and compacts old messages
 * when the estimated token count exceeds 80% of the configured context window.
 *
 * <p>When compaction triggers, the oldest messages are removed from chat history and
 * an LLM-generated summary of the dropped portion is written to {@code log.md}
 * (a {@code COMPACT} event) and {@code hot.md} (overwritten with the full summary
 * as session-continuity context).</p>
 *
 * <p>Must run before {@code OneShotMemoryAdvisor} so that compaction happens
 * before history is loaded into the prompt.</p>
 */
public class ContextCompactionAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompactionAdvisor.class);
    static final double CEILING_RATIO = 0.8;
    static final int CHARS_PER_TOKEN = 4;

    private final ChatMemory chatMemory;
    private final ChatModel summaryModel;
    private final int maxContextTokens;
    private final Path logFile;
    private final Path hotFile;

    ContextCompactionAdvisor(ChatMemory chatMemory, ChatModel summaryModel, int maxContextTokens) {
        this(chatMemory, summaryModel, maxContextTokens, null, null);
    }

    ContextCompactionAdvisor(ChatMemory chatMemory, ChatModel summaryModel, int maxContextTokens,
                             Path logFile, Path hotFile) {
        this.chatMemory = chatMemory;
        this.summaryModel = summaryModel;
        this.maxContextTokens = maxContextTokens;
        this.logFile = logFile;
        this.hotFile = hotFile;
    }

    private static final ThreadLocal<Boolean> COMPACTION_DONE = ThreadLocal.withInitial(() -> false);

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (COMPACTION_DONE.get()) {
            return chain.nextCall(request);
        }
        COMPACTION_DONE.set(true);

        try {
            compactIfNeeded(request);
            return chain.nextCall(request);
        } finally {
            COMPACTION_DONE.remove();
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        if (COMPACTION_DONE.get()) {
            return chain.nextStream(request);
        }
        COMPACTION_DONE.set(true);
        compactIfNeeded(request);
        return chain.nextStream(request)
                .doFinally(signal -> COMPACTION_DONE.remove());
    }

    private void compactIfNeeded(ChatClientRequest request) {
        String conversationId = resolveConversationId(request);
        List<Message> history = chatMemory.get(conversationId);

        int estimatedTokens = estimateTokens(history);
        int ceiling = (int) (maxContextTokens * CEILING_RATIO);

        if (estimatedTokens > ceiling) {
            compactHistory(conversationId, history, ceiling);
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

    /**
     * Force a compaction run on the given conversation, bypassing the token-ceiling
     * check. Useful for the {@code /compact} slash command (#307) when the user
     * wants to summarize without waiting for the 80% threshold.
     *
     * @return a short human-readable report describing what happened
     */
    public String forceCompact(String conversationId) {
        List<Message> history = chatMemory.get(conversationId);
        if (history == null || history.isEmpty()) {
            return "No conversation history to compact.";
        }
        int before = estimateTokens(history);
        int msgsBefore = history.size();
        // Drive the target down so the loop always removes at least the oldest
        // half. Keeps compaction useful even well under the token ceiling.
        int target = Math.max(0, before / 2);
        compactHistory(conversationId, history, target);
        List<Message> after = chatMemory.get(conversationId);
        int tokensAfter = estimateTokens(after);
        return String.format("Compacted %d → %d messages (~%d → %d tokens).",
                msgsBefore, after == null ? 0 : after.size(), before, tokensAfter);
    }

    /**
     * Current token usage estimate and the ceiling that would trigger automatic
     * compaction. Returned in a small record for the {@code /compact status}
     * command.
     */
    public CompactionStatus getStatus(String conversationId) {
        List<Message> history = chatMemory.get(conversationId);
        int tokens = estimateTokens(history);
        int ceiling = (int) (maxContextTokens * CEILING_RATIO);
        return new CompactionStatus(
                history == null ? 0 : history.size(),
                tokens,
                maxContextTokens,
                ceiling);
    }

    public record CompactionStatus(int messageCount, int estimatedTokens,
                                   int maxContextTokens, int ceilingTokens) {}

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

        List<Message> dropped = new ArrayList<>(history.subList(0, splitIndex));
        List<Message> remaining = new ArrayList<>(history.subList(splitIndex, history.size()));

        String summary = generateSummary(dropped);

        chatMemory.clear(conversationId);
        if (!remaining.isEmpty()) {
            chatMemory.add(conversationId, remaining);
        }

        log.info("Compacted context: removed {} messages (~{} tokens). "
                        + "Remaining: {} messages (~{} tokens)",
                splitIndex, removedTokens,
                remaining.size(), estimateTokens(remaining));

        persistSummary(summary, splitIndex, removedTokens, remaining.size());
    }

    private String generateSummary(List<Message> dropped) {
        if (summaryModel == null || dropped.isEmpty()) {
            return "";
        }
        try {
            String transcript = formatTranscript(dropped);
            String prompt = String.format(SUMMARY_PROMPT, transcript);
            String summary = summaryModel.call(new Prompt(prompt))
                    .getResult().getOutput().getText();
            return summary == null ? "" : summary.strip();
        } catch (Exception e) {
            log.warn("Failed to generate compaction summary: {}", e.getMessage());
            return "";
        }
    }

    private void persistSummary(String summary, int removedMessages, int removedTokens,
                                int remainingMessages) {
        if (logFile != null) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("removed_msgs", Integer.toString(removedMessages));
            fields.put("removed_tokens", Integer.toString(removedTokens));
            fields.put("kept_msgs", Integer.toString(remainingMessages));
            if (!summary.isEmpty()) {
                fields.put("summary", summary);
            }
            MemoryLogWriter.appendEvent(logFile, "COMPACT", fields);
        }
        if (hotFile != null && !summary.isEmpty()) {
            MemoryLogWriter.writeHot(hotFile, summary);
        }
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
