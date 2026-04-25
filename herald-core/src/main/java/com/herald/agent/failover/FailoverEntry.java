package com.herald.agent.failover;

import java.util.Objects;

import org.springframework.ai.chat.model.ChatModel;

/**
 * One rung of the failover chain — a {@link ChatModel} plus the labels used
 * for logging, metrics, and display ({@code /model status}).
 *
 * <p>The chain is ordered: index 0 is the primary, subsequent entries are
 * tried in sequence when earlier entries raise retryable errors.</p>
 *
 * @param provider provider key (e.g. {@code "anthropic"}, {@code "openai"})
 * @param model    model id within that provider (e.g. {@code "claude-sonnet-4-5"})
 * @param delegate the pre-configured Spring AI {@link ChatModel} to call
 */
public record FailoverEntry(String provider, String model, ChatModel delegate) {

    public FailoverEntry {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(delegate, "delegate");
    }

    /** Convenience label for logs: {@code "anthropic/claude-sonnet-4-5"}. */
    public String label() {
        return provider + "/" + model;
    }
}
