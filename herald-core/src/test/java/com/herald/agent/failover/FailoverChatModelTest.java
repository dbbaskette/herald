package com.herald.agent.failover;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FailoverChatModelTest {

    private static FailoverEntry entry(String label, ChatModel delegate) {
        String[] parts = label.split("/");
        return new FailoverEntry(parts[0], parts[1], delegate);
    }

    @Test
    void rejectsEmptyChain() {
        assertThatThrownBy(() -> new FailoverChatModel(List.of(), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void callReturnsFirstEntryResponseOnSuccess() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel backup = mock(ChatModel.class);
        ChatResponse response = mock(ChatResponse.class);
        when(primary.call(any(Prompt.class))).thenReturn(response);

        FailoverChatModel model = new FailoverChatModel(
                List.of(entry("anthropic/sonnet", primary), entry("openai/gpt-4o", backup)),
                null, null, null, null);

        assertThat(model.call(new Prompt("hi"))).isSameAs(response);
        verify(primary).call(any(Prompt.class));
        verify(backup, times(0)).call(any(Prompt.class));
    }

    @Test
    void callFailsOverOnRateLimit() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel backup = mock(ChatModel.class);
        ChatResponse response = mock(ChatResponse.class);
        when(primary.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("429 Too Many Requests"));
        when(backup.call(any(Prompt.class))).thenReturn(response);

        FailoverChatModel model = new FailoverChatModel(
                List.of(entry("anthropic/sonnet", primary), entry("openai/gpt-4o", backup)),
                null, null, null, null);

        assertThat(model.call(new Prompt("hi"))).isSameAs(response);
        verify(primary).call(any(Prompt.class));
        verify(backup).call(any(Prompt.class));
    }

    @Test
    void callRethrowsNonRetryableErrorsImmediately() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel backup = mock(ChatModel.class);
        when(primary.call(any(Prompt.class)))
                .thenThrow(new IllegalArgumentException("bad prompt"));

        FailoverChatModel model = new FailoverChatModel(
                List.of(entry("anthropic/sonnet", primary), entry("openai/gpt-4o", backup)),
                null, null, null, null);

        assertThatThrownBy(() -> model.call(new Prompt("hi")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(backup, times(0)).call(any(Prompt.class));
    }

    @Test
    void callRethrowsLastErrorAfterChainExhausted() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel backup = mock(ChatModel.class);
        when(primary.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("429 Too Many Requests"));
        when(backup.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("503 Service Unavailable"));

        FailoverChatModel model = new FailoverChatModel(
                List.of(entry("anthropic/sonnet", primary), entry("openai/gpt-4o", backup)),
                null, null, null, null);

        assertThatThrownBy(() -> model.call(new Prompt("hi")))
                .hasMessageContaining("503");
    }

    @Test
    void emitsEventOnEachFailover() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel backup = mock(ChatModel.class);
        ChatResponse response = mock(ChatResponse.class);
        when(primary.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("429 Too Many Requests"));
        when(backup.call(any(Prompt.class))).thenReturn(response);

        List<FailoverChatModel.FailoverEvent> events = new ArrayList<>();
        FailoverChatModel model = new FailoverChatModel(
                List.of(entry("anthropic/sonnet", primary), entry("openai/gpt-4o", backup)),
                null, null, null, events::add);

        model.call(new Prompt("hi"));
        assertThat(events).hasSize(1);
        assertThat(events.get(0).from().label()).isEqualTo("anthropic/sonnet");
        assertThat(events.get(0).to().label()).isEqualTo("openai/gpt-4o");
        assertThat(events.get(0).reason()).isEqualTo(FailoverReason.RATE_LIMIT);
    }

    @Test
    void circuitBreakerSkipsOpenEntry() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel backup = mock(ChatModel.class);
        ChatResponse response = mock(ChatResponse.class);
        when(backup.call(any(Prompt.class))).thenReturn(response);

        FailoverCircuitBreaker breaker = new FailoverCircuitBreaker(1, Duration.ofHours(1));
        breaker.recordFailure("anthropic/sonnet", Instant.parse("2026-04-23T11:00:00Z"));

        FailoverChatModel model = new FailoverChatModel(
                List.of(entry("anthropic/sonnet", primary), entry("openai/gpt-4o", backup)),
                null,
                breaker,
                Clock.fixed(Instant.parse("2026-04-23T11:30:00Z"), ZoneOffset.UTC),
                null);

        assertThat(model.call(new Prompt("hi"))).isSameAs(response);
        // Primary should have been skipped entirely.
        verify(primary, times(0)).call(any(Prompt.class));
    }

    @Test
    void respectsCustomRetryOnSet() {
        // Only retry on server errors; rate limit falls through to OTHER-equivalent behavior.
        ChatModel primary = mock(ChatModel.class);
        ChatModel backup = mock(ChatModel.class);
        when(primary.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("429 Too Many Requests"));

        FailoverChatModel model = new FailoverChatModel(
                List.of(entry("anthropic/sonnet", primary), entry("openai/gpt-4o", backup)),
                EnumSet.of(FailoverReason.SERVER_ERROR),
                null, null, null);

        assertThatThrownBy(() -> model.call(new Prompt("hi")))
                .hasMessageContaining("429");
        verify(backup, times(0)).call(any(Prompt.class));
    }

    @Test
    void streamFailsOverBeforeFirstEmission() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel backup = mock(ChatModel.class);
        ChatResponse r1 = mock(ChatResponse.class);
        ChatResponse r2 = mock(ChatResponse.class);
        when(primary.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new RuntimeException("429 Too Many Requests")));
        when(backup.stream(any(Prompt.class))).thenReturn(Flux.just(r1, r2));

        FailoverChatModel model = new FailoverChatModel(
                List.of(entry("anthropic/sonnet", primary), entry("openai/gpt-4o", backup)),
                null, null, null, null);

        List<ChatResponse> emitted = model.stream(new Prompt("hi"))
                .collectList().block(Duration.ofSeconds(2));
        assertThat(emitted).containsExactly(r1, r2);
    }

    @Test
    void streamDoesNotFailOverAfterEmission() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel backup = mock(ChatModel.class);
        ChatResponse r1 = mock(ChatResponse.class);
        when(primary.stream(any(Prompt.class)))
                .thenReturn(Flux.just(r1).concatWith(Flux.error(new RuntimeException("429"))));

        FailoverChatModel model = new FailoverChatModel(
                List.of(entry("anthropic/sonnet", primary), entry("openai/gpt-4o", backup)),
                null, null, null, null);

        List<ChatResponse> collected = new ArrayList<>();
        Throwable[] captured = new Throwable[1];
        model.stream(new Prompt("hi"))
                .doOnNext(collected::add)
                .doOnError(err -> captured[0] = err)
                .onErrorResume(err -> Flux.empty())
                .collectList().block(Duration.ofSeconds(2));

        assertThat(collected).containsExactly(r1);
        assertThat(captured[0]).hasMessageContaining("429");
        verify(backup, times(0)).stream(any(Prompt.class));
    }

    @Test
    void chainOrderPreservedOnExposure() {
        ChatModel a = mock(ChatModel.class);
        ChatModel b = mock(ChatModel.class);
        FailoverChatModel model = new FailoverChatModel(
                List.of(entry("x/y", a), entry("z/w", b)), null, null, null, null);

        assertThat(model.chain()).extracting(FailoverEntry::label).containsExactly("x/y", "z/w");
    }

    @Test
    void successResetsCircuitBreakerCounter() {
        ChatModel primary = mock(ChatModel.class);
        ChatResponse response = mock(ChatResponse.class);
        AtomicInteger callCount = new AtomicInteger();
        when(primary.call(any(Prompt.class))).thenAnswer(inv -> {
            if (callCount.incrementAndGet() == 1) {
                throw new RuntimeException("429 Too Many Requests");
            }
            return response;
        });
        ChatModel backup = mock(ChatModel.class);
        when(backup.call(any(Prompt.class))).thenReturn(response);

        FailoverCircuitBreaker breaker = new FailoverCircuitBreaker(5, Duration.ofSeconds(60));
        FailoverChatModel model = new FailoverChatModel(
                List.of(entry("anthropic/sonnet", primary), entry("openai/gpt-4o", backup)),
                null, breaker, null, null);

        // First call fails over, bumping the primary's failure count.
        model.call(new Prompt("hi"));
        assertThat(breaker.failureCount("anthropic/sonnet")).isEqualTo(1);

        // Second call succeeds on primary — breaker should reset.
        model.call(new Prompt("hi"));
        assertThat(breaker.failureCount("anthropic/sonnet")).isZero();
    }
}
