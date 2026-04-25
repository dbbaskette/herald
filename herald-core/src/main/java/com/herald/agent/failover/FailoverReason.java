package com.herald.agent.failover;

/**
 * Canonical reason an entry in the failover chain was skipped.
 *
 * <p>Provider SDKs surface errors as different exception types with different
 * status codes and messages. {@link FailoverErrorClassifier} maps them onto
 * this small, stable set so the failover loop can treat them uniformly and
 * config-driven retry rules can match on reason rather than exception class.</p>
 */
public enum FailoverReason {
    /** HTTP 429 — caller exceeded the provider's rate limit. */
    RATE_LIMIT,
    /** HTTP 5xx — provider is up but returning errors. */
    SERVER_ERROR,
    /** Socket/read timeout — provider didn't respond in time. */
    TIMEOUT,
    /** Connection refused, DNS failure, or provider endpoint unreachable. */
    UNAVAILABLE,
    /** Anything else — not retried by default. */
    OTHER
}
