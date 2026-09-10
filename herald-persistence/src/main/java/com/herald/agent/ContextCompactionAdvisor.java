package com.herald.agent;

import com.herald.config.HeraldConfig.CompactionStrategy;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
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
 * <p>Eviction preserves complete user turns. Recursive mode retains a tagged
 * synthetic summary turn and writes continuity files; sliding mode drops old
 * complete turns without calling a model. Failed summaries leave history intact.</p>
 *
 * <p>Must run before {@code OneShotMemoryAdvisor} so that compaction happens
 * before history is loaded into the prompt.</p>
 */
public class ContextCompactionAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompactionAdvisor.class);
    static final double CEILING_RATIO = 0.8;
    static final int CHARS_PER_TOKEN = 4;

    public static final String SYNTHETIC = "herald.compaction.synthetic";
    private final CompactionStrategy strategy;
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
        this(chatMemory, summaryModel, maxContextTokens, logFile, hotFile, CompactionStrategy.RECURSIVE_SUMMARY);
    }

    public ContextCompactionAdvisor(ChatMemory chatMemory, ChatModel summaryModel, int maxContextTokens,
                                    Path logFile, Path hotFile, CompactionStrategy strategy) {
        this.strategy = strategy == null ? CompactionStrategy.RECURSIVE_SUMMARY : strategy;
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
        return Flux.deferContextual(context -> {
            String key = "herald.compaction.done";
            if (context.hasKey(key)) return chain.nextStream(request);
            compactIfNeeded(request);
            return chain.nextStream(request).contextWrite(c -> c.put(key, true));
        });
    }

    private void compactIfNeeded(ChatClientRequest request) {
        String conversationId = resolveConversationId(request);
        synchronized (chatMemory) {
            List<Message> history = chatMemory.get(conversationId);
            int ceiling = (int) (maxContextTokens * CEILING_RATIO);
            if (estimateTokens(history) > ceiling) compactHistory(conversationId, history, ceiling);
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
        int chars = message.getText() == null ? 0 : message.getText().length();
        if (message instanceof AssistantMessage assistant) {
            for (var call : assistant.getToolCalls()) chars += call.arguments().length() + call.name().length();
        }
        if (message instanceof ToolResponseMessage tool) {
            for (var response : tool.getResponses()) chars += response.responseData().length();
        }
        return (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }

    /**
     * Force a compaction run on the given conversation, bypassing the token-ceiling
     * check. Useful for the {@code /compact} slash command (#307) when the user
     * wants to summarize without waiting for the 80% threshold.
     *
     * @return a short human-readable report describing what happened
     */
    public String forceCompact(String conversationId) {
        synchronized (chatMemory) {
            List<Message> history = chatMemory.get(conversationId);
            if (history == null || history.isEmpty()) {
                return "No conversation history to compact.";
            }
            int before = estimateTokens(history);
            int msgsBefore = history.size();
            // Request half the current token estimate, subject to safe turn boundaries.
            int target = Math.max(0, before / 2);
            if (!compactHistory(conversationId, history, target)) {
                return "History unchanged: no safe complete turn to remove, or summary unavailable.";
            }
            List<Message> after = chatMemory.get(conversationId);
            int tokensAfter = estimateTokens(after);
            return String.format("Compacted %d → %d messages (~%d → %d tokens).",
                    msgsBefore, after == null ? 0 : after.size(), before, tokensAfter);
        }
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

    private boolean compactHistory(String conversationId, List<Message> history, int targetTokens) {
        // System instructions are never summarized or evicted. A previous synthetic
        // turn is included in the next summary, but is not a real eviction boundary.
        List<Message> system = history.stream().filter(SystemMessage.class::isInstance).toList();
        List<Message> dialogue = history.stream().filter(m -> !(m instanceof SystemMessage)).toList();
        int remove = estimateTokens(history) - targetTokens;
        int prospective = 0;
        while (prospective < dialogue.size() && remove > 0) {
            remove -= estimateMessageTokens(dialogue.get(prospective++));
        }
        int split = TurnSafeChatMemory.priorUserBoundary(dialogue, prospective);
        if (split <= 0 || dialogue.subList(0, split).stream().noneMatch(TurnSafeChatMemory::isRealUser)) {
            return false;
        }
        List<Message> dropped = dialogue.subList(0, split);
        String summary = strategy == CompactionStrategy.SLIDING_WINDOW ? "" : generateSummary(dropped);
        // Fail closed: model failures and empty output must not destroy context.
        if (strategy != CompactionStrategy.SLIDING_WINDOW && summary.isBlank()) return false;
        List<Message> remaining = new ArrayList<>(system);
        if (!summary.isBlank()) {
            remaining.add(UserMessage.builder().text("Summary of earlier conversation (context only):")
                    .metadata(Map.of(SYNTHETIC, true)).build());
            remaining.add(AssistantMessage.builder().content(summary)
                    .properties(Map.of(SYNTHETIC, true)).build());
        }
        remaining.addAll(dialogue.subList(split, dialogue.size()));
        if (chatMemory instanceof TurnSafeChatMemory memory) {
            memory.replace(conversationId, remaining);
        } else {
            // Compatibility with externally supplied ChatMemory implementations.
            chatMemory.clear(conversationId);
            try {
                chatMemory.add(conversationId, remaining);
            } catch (RuntimeException failure) {
                chatMemory.clear(conversationId);
                chatMemory.add(conversationId, history);
                throw failure;
            }
        }
        log.info("Compacted context: removed {} messages; kept {} messages", split, remaining.size());
        persistSummary(summary, split, estimateTokens(dropped), remaining.size());
        return true;
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
                case ToolResponseMessage ignored -> "Tool";
                default -> "System";
            };
            String text = msg.getText();
            if (msg instanceof ToolResponseMessage tool) text = tool.getResponses().toString();
            if (msg instanceof AssistantMessage assistant && assistant.hasToolCalls()) text = text + assistant.getToolCalls();
            if (text != null && !text.isBlank()) {
                sb.append(role).append(": ").append(text).append("\n\n");
            }
        }
        return sb.toString().stripTrailing();
    }

    private String resolveConversationId(ChatClientRequest request) {
        Object id = request.context().get("chat_memory_conversation_id");
        return id != null ? id.toString() : AgentService.DEFAULT_CONVERSATION_ID;
    }
}
