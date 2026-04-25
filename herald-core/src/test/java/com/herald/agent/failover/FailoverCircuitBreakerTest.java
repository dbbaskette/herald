package com.herald.agent.failover;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FailoverCircuitBreakerTest {

    private static final Instant T0 = Instant.parse("2026-04-23T12:00:00Z");

    @Test
    void opensAfterThresholdFailures() {
        FailoverCircuitBreaker breaker = new FailoverCircuitBreaker(3, Duration.ofSeconds(60));

        assertThat(breaker.isOpen("anthropic/sonnet", T0)).isFalse();
        breaker.recordFailure("anthropic/sonnet", T0);
        assertThat(breaker.isOpen("anthropic/sonnet", T0)).isFalse();
        breaker.recordFailure("anthropic/sonnet", T0);
        assertThat(breaker.isOpen("anthropic/sonnet", T0)).isFalse();
        breaker.recordFailure("anthropic/sonnet", T0);
        assertThat(breaker.isOpen("anthropic/sonnet", T0)).isTrue();
    }

    @Test
    void halfOpensAfterOpenForElapses() {
        FailoverCircuitBreaker breaker = new FailoverCircuitBreaker(2, Duration.ofSeconds(60));
        breaker.recordFailure("openai/gpt-4o", T0);
        breaker.recordFailure("openai/gpt-4o", T0);

        assertThat(breaker.isOpen("openai/gpt-4o", T0)).isTrue();
        // Within the window — still open.
        assertThat(breaker.isOpen("openai/gpt-4o", T0.plusSeconds(30))).isTrue();
        // After the window — half-open (returns false, counter reset).
        assertThat(breaker.isOpen("openai/gpt-4o", T0.plusSeconds(61))).isFalse();
        assertThat(breaker.failureCount("openai/gpt-4o")).isZero();
    }

    @Test
    void successClosesAndResets() {
        FailoverCircuitBreaker breaker = new FailoverCircuitBreaker(2, Duration.ofSeconds(60));
        breaker.recordFailure("anthropic/sonnet", T0);
        breaker.recordFailure("anthropic/sonnet", T0);
        assertThat(breaker.isOpen("anthropic/sonnet", T0)).isTrue();

        breaker.recordSuccess("anthropic/sonnet");
        assertThat(breaker.isOpen("anthropic/sonnet", T0)).isFalse();
        assertThat(breaker.failureCount("anthropic/sonnet")).isZero();
    }

    @Test
    void keysAreIndependent() {
        FailoverCircuitBreaker breaker = new FailoverCircuitBreaker(2, Duration.ofSeconds(60));
        breaker.recordFailure("a", T0);
        breaker.recordFailure("a", T0);

        assertThat(breaker.isOpen("a", T0)).isTrue();
        assertThat(breaker.isOpen("b", T0)).isFalse();
    }

    @Test
    void rejectsZeroThreshold() {
        assertThatThrownBy(() -> new FailoverCircuitBreaker(0, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullDurationDefaultsToZero() {
        FailoverCircuitBreaker breaker = new FailoverCircuitBreaker(1, null);
        breaker.recordFailure("x", T0);
        // With zero open-for, the breaker half-opens on the very next check.
        assertThat(breaker.isOpen("x", T0.plusNanos(1))).isFalse();
    }
}
