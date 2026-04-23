package com.herald.agent;

import java.util.List;

/**
 * Callback interface for recording agent turn metrics.
 * Implementations live outside herald-core (e.g. AgentMetrics in herald-bot)
 * so the core module stays free of persistence dependencies.
 */
public interface AgentTurnListener {

    /**
     * Record metrics for a completed agent turn.
     *
     * @param cacheReadTokens prompt tokens served from Anthropic's prompt cache
     *                        (charged at 0.25×). 0 for non-Anthropic turns.
     * @param cacheWriteTokens prompt tokens that populated the cache this turn
     *                         (charged at ~1.25×). 0 for non-Anthropic turns.
     */
    default void recordTurn(String provider, String model, long tokensIn, long tokensOut,
                            long cacheReadTokens, long cacheWriteTokens,
                            long latencyMs, List<String> toolCalls, String subagentId) {
        // Default delegates to the legacy signature so older implementations
        // keep working. Implementations should override this to record cache
        // tokens.
        recordTurn(provider, model, tokensIn, tokensOut, latencyMs, toolCalls, subagentId);
    }

    /**
     * Legacy signature without cache-token fields. Prefer the overload that
     * accepts {@code cacheReadTokens}/{@code cacheWriteTokens}.
     */
    void recordTurn(String provider, String model, long tokensIn, long tokensOut,
                    long latencyMs, List<String> toolCalls, String subagentId);

    /**
     * Derive the provider name from a model identifier.
     */
    static String deriveProvider(String model) {
        if (model == null || model.isBlank()) {
            return "unknown";
        }
        String lower = model.toLowerCase();
        if (lower.startsWith("claude") || lower.contains("anthropic")) {
            return "anthropic";
        }
        if (lower.startsWith("gpt") || lower.startsWith("o1") || lower.startsWith("o3")
                || lower.startsWith("o4") || lower.contains("openai")) {
            return "openai";
        }
        if (lower.startsWith("llama") || lower.startsWith("mistral") || lower.startsWith("gemma")
                || lower.startsWith("phi") || lower.startsWith("qwen") || lower.startsWith("codellama")) {
            return "ollama";
        }
        return "unknown";
    }
}
