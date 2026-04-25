package com.herald.agent.failover;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-entry circuit breaker for the failover chain. After N consecutive
 * failures an entry is "opened" — skipped until {@code openFor} elapses —
 * then half-opens on the next attempt. One success closes the breaker.
 *
 * <p>Simple state: a failure counter + an {@code openedAt} instant. Kept
 * intentionally lock-light since {@link FailoverChatModel#call} is on the
 * hot path for every user turn.</p>
 */
public class FailoverCircuitBreaker {

    private final int failureThreshold;
    private final Duration openFor;
    private final ConcurrentMap<String, AtomicInteger> failures = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Instant> openedAt = new ConcurrentHashMap<>();

    public FailoverCircuitBreaker(int failureThreshold, Duration openFor) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be >= 1");
        }
        this.failureThreshold = failureThreshold;
        this.openFor = openFor == null ? Duration.ZERO : openFor;
    }

    /**
     * @return {@code true} if this entry is currently being skipped because
     *         it blew past {@code failureThreshold} within {@code openFor}.
     */
    public boolean isOpen(String key, Instant now) {
        Instant opened = openedAt.get(key);
        if (opened == null) {
            return false;
        }
        if (now.isAfter(opened.plus(openFor))) {
            // Half-open: one probe allowed through. Caller resets on success.
            openedAt.remove(key);
            failures.computeIfPresent(key, (k, v) -> {
                v.set(0);
                return v;
            });
            return false;
        }
        return true;
    }

    /** Record a failure. Opens the breaker if we've hit the threshold. */
    public void recordFailure(String key, Instant now) {
        AtomicInteger counter = failures.computeIfAbsent(key, k -> new AtomicInteger());
        int count = counter.incrementAndGet();
        if (count >= failureThreshold) {
            openedAt.put(key, now);
        }
    }

    /** Record a success — closes the breaker if open, resets the counter. */
    public void recordSuccess(String key) {
        failures.computeIfPresent(key, (k, v) -> {
            v.set(0);
            return v;
        });
        openedAt.remove(key);
    }

    /** Exposed for logging / {@code /model status}. */
    public int failureCount(String key) {
        AtomicInteger counter = failures.get(key);
        return counter == null ? 0 : counter.get();
    }
}
