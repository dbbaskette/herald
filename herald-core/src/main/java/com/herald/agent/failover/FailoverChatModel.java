package com.herald.agent.failover;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;

/**
 * {@link ChatModel} that transparently cycles through a config-driven chain
 * of {@link FailoverEntry entries} when the primary entry throws a retryable
 * error (rate-limit, server error, timeout, or provider unavailable).
 *
 * <p>See {@code herald.agent.model-failover} in {@code application.yaml}
 * for configuration. Behavior:</p>
 *
 * <ul>
 *   <li>Calls entry 0; on a retryable failure, calls entry 1; and so on.</li>
 *   <li>Non-retryable failures ({@link FailoverReason#OTHER}) rethrow without
 *       touching the next entry — they're almost always caller bugs, not
 *       provider availability problems.</li>
 *   <li>After exhausting the chain, rethrows the last exception so the caller
 *       sees the same failure mode as without failover enabled.</li>
 *   <li>A per-entry {@link FailoverCircuitBreaker} skips entries that have
 *       failed {@code failure-threshold} times in a row, for {@code open-for}.</li>
 *   <li>Streaming ({@link #stream(Prompt)}) cannot safely fail over mid-stream
 *       without duplicating tokens, so we fail over only when the first emission
 *       itself errors. Anything emitted to the subscriber commits us to that
 *       entry — any later error propagates unchanged.</li>
 * </ul>
 */
public class FailoverChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(FailoverChatModel.class);

    private final List<FailoverEntry> chain;
    private final Set<FailoverReason> retryOn;
    private final FailoverCircuitBreaker circuitBreaker;
    private final Clock clock;
    private final Consumer<FailoverEvent> eventSink;

    public FailoverChatModel(List<FailoverEntry> chain,
                             Set<FailoverReason> retryOn,
                             FailoverCircuitBreaker circuitBreaker,
                             Clock clock,
                             Consumer<FailoverEvent> eventSink) {
        if (chain == null || chain.isEmpty()) {
            throw new IllegalArgumentException("Failover chain must contain at least one entry");
        }
        this.chain = List.copyOf(chain);
        this.retryOn = retryOn == null || retryOn.isEmpty()
                ? EnumSet.of(FailoverReason.RATE_LIMIT, FailoverReason.SERVER_ERROR,
                        FailoverReason.TIMEOUT, FailoverReason.UNAVAILABLE)
                : EnumSet.copyOf(retryOn);
        this.circuitBreaker = circuitBreaker == null
                ? new FailoverCircuitBreaker(3, java.time.Duration.ofSeconds(60))
                : circuitBreaker;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.eventSink = eventSink == null ? event -> { } : eventSink;
    }

    /**
     * Event emitted every time an entry fails and we consider failing over.
     * Subscribers can persist counters, notify Telegram, or feed {@code /model status}.
     */
    public record FailoverEvent(FailoverEntry from, FailoverEntry to, FailoverReason reason,
                                Throwable cause) {
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        RuntimeException lastError = null;
        List<String> attempted = new ArrayList<>(chain.size());
        for (int i = 0; i < chain.size(); i++) {
            FailoverEntry entry = chain.get(i);
            Instant now = clock.instant();
            if (circuitBreaker.isOpen(entry.label(), now)) {
                log.debug("Skipping {} — circuit breaker open", entry.label());
                attempted.add(entry.label() + "[open]");
                continue;
            }
            try {
                ChatResponse response = entry.delegate().call(prompt);
                circuitBreaker.recordSuccess(entry.label());
                if (i > 0) {
                    log.info("Failover succeeded on entry #{} ({}) after trying {}", i, entry.label(), attempted);
                }
                return response;
            } catch (RuntimeException e) {
                lastError = e;
                FailoverReason reason = FailoverErrorClassifier.classify(e);
                circuitBreaker.recordFailure(entry.label(), now);
                attempted.add(entry.label() + "[" + reason + "]");
                if (!retryOn.contains(reason) || i == chain.size() - 1) {
                    // Terminal — either non-retryable or last entry.
                    log.warn("Failover chain exhausted at {} (reason={}, attempted={})",
                            entry.label(), reason, attempted);
                    throw e;
                }
                FailoverEntry next = chain.get(i + 1);
                log.warn("Failover triggered: {} -> {} (reason={})",
                        entry.label(), next.label(), reason);
                eventSink.accept(new FailoverEvent(entry, next, reason, e));
            }
        }
        // Chain exhausted. Defensive — the loop above should have already rethrown.
        if (lastError != null) {
            throw lastError;
        }
        throw new IllegalStateException("Failover chain exhausted with no error recorded");
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // Streaming failover is best-effort — we can only fail over if the
        // error arrives before any token has been emitted. Once we've shipped
        // tokens to the subscriber, duplicating them from a different provider
        // would corrupt the reply. So we arm failover only for the first
        // subscription; anything afterward propagates straight through.
        return streamWithFailover(prompt, 0);
    }

    private Flux<ChatResponse> streamWithFailover(Prompt prompt, int index) {
        if (index >= chain.size()) {
            return Flux.error(new IllegalStateException(
                    "Failover chain exhausted for streaming call"));
        }
        FailoverEntry entry = chain.get(index);
        Instant now = clock.instant();
        if (circuitBreaker.isOpen(entry.label(), now)) {
            log.debug("Skipping {} for stream — circuit breaker open", entry.label());
            return streamWithFailover(prompt, index + 1);
        }
        final boolean[] emitted = { false };
        return entry.delegate().stream(prompt)
                .doOnNext(r -> emitted[0] = true)
                .doOnComplete(() -> circuitBreaker.recordSuccess(entry.label()))
                .onErrorResume(throwable -> {
                    FailoverReason reason = FailoverErrorClassifier.classify(throwable);
                    circuitBreaker.recordFailure(entry.label(), clock.instant());
                    if (emitted[0] || !retryOn.contains(reason) || index == chain.size() - 1) {
                        log.warn("Stream failover unavailable on {} (reason={}, emitted={})",
                                entry.label(), reason, emitted[0]);
                        return Flux.error(throwable);
                    }
                    FailoverEntry next = chain.get(index + 1);
                    log.warn("Stream failover triggered: {} -> {} (reason={})",
                            entry.label(), next.label(), reason);
                    eventSink.accept(new FailoverEvent(entry, next, reason,
                            throwable instanceof RuntimeException re ? re : new RuntimeException(throwable)));
                    return streamWithFailover(prompt, index + 1);
                });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return chain.get(0).delegate().getDefaultOptions();
    }

    /** Exposes the chain to tools / {@code /model status}. Immutable copy. */
    public List<FailoverEntry> chain() {
        return chain;
    }

    /** Exposes the active retry policy (read-only). */
    public Set<FailoverReason> retryOn() {
        return Set.copyOf(retryOn);
    }
}
