package com.herald.agent;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExecutionUsageIntegrationTest {
    static class FixtureTool {
        final AtomicInteger calls = new AtomicInteger();
        @Tool(description = "Return fixture data")
        String fixture() { calls.incrementAndGet(); return "fixture-result"; }
    }

    private ChatResponse response(boolean tool, int input, int output, long cacheRead, long cacheWrite) {
        var message = tool ? AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call", "function", "fixture", "{}"))).build()
                : new AssistantMessage("done");
        return new ChatResponse(List.of(new Generation(message)), ChatResponseMetadata.builder()
                .model("claude-fixture")
                .usage(new DefaultUsage(input, output, input + output, null, cacheRead, cacheWrite)).build());
    }

    private AgentService service(ChatModel model, FixtureTool tool, AgentTurnListener listener,
                                 ExecutionLimits limits) {
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        var client = ChatClient.builder(model).defaultTools(tool)
                .defaultAdvisors(ExecutionAdvisors.create(limits, () -> {})).build();
        var switcher = mock(ModelSwitcher.class);
        when(switcher.getActiveClient()).thenReturn(client);
        return new AgentService(switcher, listener);
    }

    private ExecutionLimits limits(long tokens, double cost) {
        return new ExecutionLimits(2, Duration.ofSeconds(5), tokens, cost, 1, 1);
    }

    private void recorded(AgentTurnListener listener, long input, long output, long read, long write) {
        verify(listener).recordTurn(eq("anthropic"), eq("claude-fixture"), eq(input), eq(output),
                eq(read), eq(write), anyLong(), anyList(), isNull());
        verifyNoMoreInteractions(listener);
    }

    @Test void costCappedResponseStillRecordsItsUsageBeforeRejectingTools() {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response(true, 1000, 1000, 0, 0));
        var tool = new FixtureTool();
        var listener = mock(AgentTurnListener.class);
        var agent = service(model, tool, listener, limits(10000, .001));
        assertThatThrownBy(() -> agent.chat("cap", "web-cap"))
                .isInstanceOf(ExecutionLimitException.class).hasMessageContaining("cost limit");
        assertThat(tool.calls).hasValue(0);
        recorded(listener, 1000, 1000, 0, 0);
    }

    @Test void secondRoundProviderFailureRetainsCompletedFirstRoundIncludingCacheUsage() {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response(true, 11, 7, 101, 103))
                .thenThrow(new IllegalStateException("second round failed"));
        var tool = new FixtureTool();
        var listener = mock(AgentTurnListener.class);
        var agent = service(model, tool, listener, limits(10000, 0));
        assertThatThrownBy(() -> agent.chat("fail", "web-failure")).hasMessageContaining("second round failed");
        assertThat(tool.calls).hasValue(1);
        recorded(listener, 11, 7, 101, 103);
    }

    @Test void successfulMultipleRoundsAreSummedAndRecordedExactlyOnce() {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response(true, 11, 7, 101, 103))
                .thenReturn(response(false, 13, 17, 107, 109));
        var listener = mock(AgentTurnListener.class);
        var agent = service(model, new FixtureTool(), listener, limits(10000, 0));
        assertThat(agent.chat("finish", "web-success")).isEqualTo("done");
        recorded(listener, 24, 24, 208, 212);
    }

    @Test void separateCacheInputsCountTowardTokenLimitAndRemainInMetrics() {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response(true, 1, 1, 50, 50));
        var listener = mock(AgentTurnListener.class);
        var tool = new FixtureTool();
        var agent = service(model, tool, listener, limits(100, 0));
        assertThatThrownBy(() -> agent.chat("cached", "web-cache"))
                .isInstanceOf(ExecutionLimitException.class).hasMessageContaining("token limit");
        assertThat(tool.calls).hasValue(0);
        recorded(listener, 1, 1, 50, 50);
    }

    @Test void cacheWritesUseConservativeCostEstimateBeforeAnyToolStarts() {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response(true, 0, 0, 0, 600));
        var listener = mock(AgentTurnListener.class);
        var tool = new FixtureTool();
        var agent = service(model, tool, listener, limits(10000, .001));
        assertThatThrownBy(() -> agent.chat("cache write", "web-cache-write"))
                .isInstanceOf(ExecutionLimitException.class).hasMessageContaining("cost limit");
        assertThat(tool.calls).hasValue(0);
        recorded(listener, 0, 0, 0, 600);
    }

    @Test void simultaneousConversationsHaveIndependentStepBudgetsAndUsage() throws Exception {
        var model = mock(ChatModel.class);
        var bothEntered = new CountDownLatch(2);
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            if (prompt.getInstructions().stream().noneMatch(ToolResponseMessage.class::isInstance)) {
                bothEntered.countDown();
                assertThat(bothEntered.await(2, TimeUnit.SECONDS)).isTrue();
                return response(true, 10, 5, 0, 0);
            }
            return response(false, 20, 7, 0, 0);
        });
        var listener = mock(AgentTurnListener.class);
        var tool = new FixtureTool();
        var agent = service(model, tool, listener, limits(50, 0));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> agent.chat("first", "web-first"));
            var second = executor.submit(() -> agent.chat("second", "web-second"));
            assertThat(first.get(4, TimeUnit.SECONDS)).isEqualTo("done");
            assertThat(second.get(4, TimeUnit.SECONDS)).isEqualTo("done");
        }
        assertThat(tool.calls).hasValue(2);
        verify(listener, times(2)).recordTurn(eq("anthropic"), eq("claude-fixture"), eq(30L), eq(12L),
                eq(0L), eq(0L), anyLong(), anyList(), isNull());
        verifyNoMoreInteractions(listener);
    }

    private ChatClient streamingClient(ChatModel model) {
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        return ChatClient.builder(model)
                .defaultAdvisors(ExecutionAdvisors.create(limits(10000, 0), () -> {})).build();
    }

    @Test void failedStreamRecordsLatestCumulativeUsageExactlyOnce() {
        var model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(reactor.core.publisher.Flux.concat(
                reactor.core.publisher.Flux.just(response(false, 11, 2, 101, 103),
                        response(false, 11, 7, 101, 103)),
                reactor.core.publisher.Flux.error(new IllegalStateException("stream failed"))));
        var reports = new java.util.concurrent.CopyOnWriteArrayList<ExecutionUsage>();
        assertThatThrownBy(() -> streamingClient(model).prompt("stream failure")
                .advisors(a -> a.param(ExecutionUsage.OBSERVER,
                        (java.util.function.Consumer<ExecutionUsage>) reports::add))
                .stream().chatResponse().collectList().block(Duration.ofSeconds(2)))
                .hasMessageContaining("stream failed");
        assertThat(reports).containsExactly(new ExecutionUsage(List.of(
                new ExecutionUsage.ModelUsage("claude-fixture", 11, 7, 101, 103))));
    }

    @Test void cancelledStreamRecordsLatestCumulativeUsageExactlyOnce() throws Exception {
        var model = mock(ChatModel.class);
        var emitted = new CountDownLatch(2);
        when(model.stream(any(Prompt.class))).thenReturn(reactor.core.publisher.Flux.concat(
                reactor.core.publisher.Flux.just(response(false, 13, 2, 107, 109),
                        response(false, 13, 17, 107, 109)),
                reactor.core.publisher.Flux.never()));
        var reports = new java.util.concurrent.CopyOnWriteArrayList<ExecutionUsage>();
        var subscription = streamingClient(model).prompt("stream cancellation")
                .advisors(a -> a.param(ExecutionUsage.OBSERVER,
                        (java.util.function.Consumer<ExecutionUsage>) reports::add))
                .stream().chatResponse().doOnNext(ignored -> emitted.countDown()).subscribe();
        try {
            assertThat(emitted.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            subscription.dispose();
        }
        assertThat(reports).containsExactly(new ExecutionUsage(List.of(
                new ExecutionUsage.ModelUsage("claude-fixture", 13, 17, 107, 109))));
    }

}
