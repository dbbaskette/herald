package com.herald.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;

/**
 * Thin wrapper around the main ChatClient that provides a simple call interface
 * with error handling for the Telegram transport layer.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    public static final String DEFAULT_CONVERSATION_ID = "default";

    /** Strip reasoning tags emitted by some local models (Qwen, DeepSeek, etc.) */
    private static final Pattern THINK_TAGS = Pattern.compile("<think>.*?</think>\\s*", Pattern.DOTALL);

    private final ModelSwitcher modelSwitcher;
    @Nullable
    private final AgentTurnListener agentTurnListener;

    public AgentService(ModelSwitcher modelSwitcher, @Nullable AgentTurnListener agentTurnListener) {
        this.modelSwitcher = modelSwitcher;
        this.agentTurnListener = agentTurnListener;
    }

    /**
     * Send a user message through the agent loop and return the final response.
     * The ChatClient handles tool calls, memory, and conversation history via advisors.
     */
    public String chat(String userMessage) {
        return chat(userMessage, DEFAULT_CONVERSATION_ID);
    }

    /**
     * Send a user message with a specific conversation ID.
     */
    public String chat(String userMessage, String conversationId) {
        log.info("Agent processing message (conversation={}): {}",
                conversationId, userMessage.substring(0, Math.min(userMessage.length(), 50)));

        long startTime = System.nanoTime();

        String model = "unknown";
        long tokensIn = 0;
        long tokensOut = 0;
        long cacheReadTokens = 0;
        long cacheWriteTokens = 0;
        List<String> toolCalls = Collections.emptyList();
        ChatResponse chatResponse = null;

        try {
            chatResponse = modelSwitcher.getActiveClient().prompt()
                    .user(userMessage)
                    .advisors(a -> a.param("chat_memory_conversation_id", conversationId))
                    .call()
                    .chatResponse();

            if (chatResponse != null && chatResponse.getMetadata() != null) {
                Usage usage = chatResponse.getMetadata().getUsage();
                if (usage != null) {
                    tokensIn = usage.getPromptTokens() != null ? usage.getPromptTokens().longValue() : 0;
                    tokensOut = usage.getCompletionTokens() != null ? usage.getCompletionTokens().longValue() : 0;
                    long[] cache = extractCacheTokens(usage.getNativeUsage());
                    cacheReadTokens = cache[0];
                    cacheWriteTokens = cache[1];
                }
                if (chatResponse.getMetadata().getModel() != null) {
                    model = chatResponse.getMetadata().getModel();
                }
            }

            // Extract tool call names from all generations
            toolCalls = extractToolCalls(chatResponse);
        } finally {
            long latencyMs = (System.nanoTime() - startTime) / 1_000_000;
            if (agentTurnListener != null) {
                String provider = AgentTurnListener.deriveProvider(model);
                agentTurnListener.recordTurn(provider, model, tokensIn, tokensOut,
                        cacheReadTokens, cacheWriteTokens, latencyMs, toolCalls, null);
            }
        }

        String content = chatResponse != null && chatResponse.getResult() != null
                ? chatResponse.getResult().getOutput().getText()
                : null;

        log.info("Agent response generated (conversation={}), length={}",
                conversationId, content != null ? content.length() : 0);

        return content != null ? stripThinkTags(content) : "";
    }

    /**
     * Stream the agent response as text chunks. Advisors handle memory, tool calls, and
     * context injection just as in {@link #chat}. Emits incremental assistant text as the
     * model produces it; the Flux completes once the turn is fully finished (including any
     * tool-call iterations). Metrics are recorded once at completion via the
     * {@link AgentTurnListener}.
     *
     * <p>Note: think-tag stripping is NOT applied per-chunk — callers should accumulate
     * the full text and apply {@link #stripThinkTags} before persisting or displaying the
     * final reply. Chunks may contain partial text of a {@code <think>...</think>} block.</p>
     */
    public Flux<String> streamChat(String userMessage, String conversationId) {
        log.info("Agent streaming message (conversation={}): {}",
                conversationId, userMessage.substring(0, Math.min(userMessage.length(), 50)));

        long startTime = System.nanoTime();
        AtomicReference<String> modelRef = new AtomicReference<>("unknown");
        AtomicLong tokensInRef = new AtomicLong(0);
        AtomicLong tokensOutRef = new AtomicLong(0);
        AtomicLong cacheReadRef = new AtomicLong(0);
        AtomicLong cacheWriteRef = new AtomicLong(0);
        AtomicReference<List<String>> toolCallsRef = new AtomicReference<>(Collections.emptyList());
        AtomicLong totalChars = new AtomicLong(0);

        Flux<ChatResponse> responses = modelSwitcher.getActiveClient().prompt()
                .user(userMessage)
                .advisors(a -> a.param("chat_memory_conversation_id", conversationId))
                .stream()
                .chatResponse();

        return responses
                .doOnNext(chatResponse -> {
                    if (chatResponse.getMetadata() != null) {
                        Usage usage = chatResponse.getMetadata().getUsage();
                        if (usage != null) {
                            if (usage.getPromptTokens() != null) {
                                tokensInRef.set(usage.getPromptTokens().longValue());
                            }
                            if (usage.getCompletionTokens() != null) {
                                tokensOutRef.set(usage.getCompletionTokens().longValue());
                            }
                            long[] cache = extractCacheTokens(usage.getNativeUsage());
                            if (cache[0] > 0) cacheReadRef.set(cache[0]);
                            if (cache[1] > 0) cacheWriteRef.set(cache[1]);
                        }
                        if (chatResponse.getMetadata().getModel() != null) {
                            modelRef.set(chatResponse.getMetadata().getModel());
                        }
                    }
                    List<String> calls = extractToolCalls(chatResponse);
                    if (!calls.isEmpty()) {
                        List<String> combined = new ArrayList<>(toolCallsRef.get());
                        combined.addAll(calls);
                        toolCallsRef.set(combined);
                    }
                })
                .map(chatResponse -> {
                    if (chatResponse.getResult() == null || chatResponse.getResult().getOutput() == null) {
                        return "";
                    }
                    String text = chatResponse.getResult().getOutput().getText();
                    return text != null ? text : "";
                })
                .filter(s -> !s.isEmpty())
                .doOnNext(chunk -> totalChars.addAndGet(chunk.length()))
                .doOnError(err -> log.error("Stream error (conversation={}): {}",
                        conversationId, err.getMessage(), err))
                .doFinally(signal -> {
                    long latencyMs = (System.nanoTime() - startTime) / 1_000_000;
                    log.info("Agent stream finished (conversation={}, signal={}), chars={}",
                            conversationId, signal, totalChars.get());
                    if (agentTurnListener != null) {
                        String model = modelRef.get();
                        String provider = AgentTurnListener.deriveProvider(model);
                        agentTurnListener.recordTurn(provider, model,
                                tokensInRef.get(), tokensOutRef.get(),
                                cacheReadRef.get(), cacheWriteRef.get(),
                                latencyMs, toolCallsRef.get(), null);
                    }
                });
    }

    public static String stripThinkTags(String text) {
        return THINK_TAGS.matcher(text).replaceAll("").strip();
    }

    /**
     * Extracts cache-read and cache-write token counts from a provider's native
     * {@code Usage} object. For Anthropic, these are {@code cacheReadInputTokens}
     * and {@code cacheCreationInputTokens} respectively. Other providers return
     * {@code {0, 0}} — their native usage shape doesn't expose cache metrics and
     * Herald currently only uses prompt caching on Anthropic.
     *
     * <p>Uses reflection so herald-core doesn't have to reference the concrete
     * Anthropic types at call sites (the dependency is transitive regardless,
     * but this keeps the method generic).</p>
     *
     * @return {@code long[2]} — index 0 is cache-read, index 1 is cache-write
     */
    static long[] extractCacheTokens(Object nativeUsage) {
        if (nativeUsage == null) {
            return new long[]{0L, 0L};
        }
        long read = invokeTokenAccessor(nativeUsage, "cacheReadInputTokens");
        long write = invokeTokenAccessor(nativeUsage, "cacheCreationInputTokens");
        return new long[]{read, write};
    }

    private static long invokeTokenAccessor(Object usage, String methodName) {
        try {
            var method = usage.getClass().getMethod(methodName);
            Object result = method.invoke(usage);
            if (result instanceof Number n) {
                return n.longValue();
            }
        } catch (NoSuchMethodException e) {
            // Non-Anthropic native usage shape — not an error, just no cache data.
        } catch (Exception e) {
            log.debug("Failed to read {} from native usage: {}", methodName, e.getMessage());
        }
        return 0L;
    }

    private List<String> extractToolCalls(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResults() == null) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        for (Generation generation : chatResponse.getResults()) {
            if (generation.getOutput() instanceof AssistantMessage assistant
                    && assistant.getToolCalls() != null) {
                for (AssistantMessage.ToolCall tc : assistant.getToolCalls()) {
                    names.add(tc.name());
                }
            }
        }
        return names;
    }
}
