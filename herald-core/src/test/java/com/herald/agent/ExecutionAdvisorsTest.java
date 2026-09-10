package com.herald.agent;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.ai.tool.annotation.Tool;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class ExecutionAdvisorsTest {
    static class FixtureTool {
        final AtomicInteger calls = new AtomicInteger();
        @Tool(description = "Increment fixture") String fixture() { calls.incrementAndGet(); return "fixture-result"; }
    }
    private ChatResponse toolCall() {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call", "function", "fixture", "{}"))).build())));
    }
    private ChatClient client(ChatModel model, Object tool, ExecutionLimits limits) {
        when(model.getOptions()).thenReturn(org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build());
        return ChatClient.builder(model).defaultTools(tool)
                .defaultAdvisors(ExecutionAdvisors.create(limits, () -> {})).build();
    }
    private ExecutionLimits limits(int steps, Duration deadline) {
        return new ExecutionLimits(steps, deadline, 10000, 0, 0, 0);
    }
    @Test void chainedToolsReachSynthesisAndPerTurnStateResets() {
        var model = mock(ChatModel.class);
        var tool = new FixtureTool();
        when(model.call(any(Prompt.class))).thenAnswer(inv -> {
            Prompt prompt = inv.getArgument(0);
            if (prompt.getInstructions().stream().noneMatch(ToolResponseMessage.class::isInstance)) return toolCall();
            assertThat(prompt.getInstructions()).anyMatch(message -> message instanceof ToolResponseMessage result
                    && result.getResponses().stream().anyMatch(r -> r.responseData().contains("fixture-result")));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("synthesized"))));
        });
        var client = client(model, tool, limits(2, Duration.ofSeconds(5)));
        assertThat(client.prompt("first").call().content()).isEqualTo("synthesized");
        assertThat(client.prompt("second").call().content()).isEqualTo("synthesized");
        assertThat(tool.calls).hasValue(2);
    }
    @Test void loopingModelStopsAtExactStepLimit() {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenAnswer(inv -> toolCall());
        var tool = new FixtureTool();
        assertThatThrownBy(() -> client(model, tool, limits(2, Duration.ofSeconds(5))).prompt("loop").call().content())
                .isInstanceOf(ExecutionLimitException.class).hasMessageContaining("step limit");
        verify(model, times(2)).call(any(Prompt.class));
        assertThat(tool.calls).hasValue(2);
    }
    @Test void reportedCostStopsBeforeRequestedTools() {
        var model = mock(ChatModel.class);
        var response = toolCall();
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(response.getResults(),
                ChatResponseMetadata.builder().usage(new DefaultUsage(1000, 1000)).build()));
        var tool = new FixtureTool();
        var cap = new ExecutionLimits(10, Duration.ofSeconds(5), 10000, .001, 1, 1);
        assertThatThrownBy(() -> client(model, tool, cap).prompt("cost").call().content())
                .isInstanceOf(ExecutionLimitException.class).hasMessageContaining("cost limit");
        assertThat(tool.calls).hasValue(0);
    }
    @Test void deadlineInterruptsBlockingProviderAndPreventsLateTools() throws Exception {
        var model = mock(ChatModel.class);
        var interrupted = new CountDownLatch(1);
        when(model.call(any(Prompt.class))).thenAnswer(inv -> {
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException e) { interrupted.countDown(); }
            return toolCall(); // A provider that returns after interruption must not start tools.
        });
        var tool = new FixtureTool();
        assertThatThrownBy(() -> client(model, tool, limits(10, Duration.ofMillis(150))).prompt("wait").call().content())
                .isInstanceOf(ExecutionLimitException.class).hasMessageContaining("deadline");
        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(tool.calls).hasValue(0);
    }
    @Test void callerCancellationInterruptsWorkerAndPreservesConversationOwnership() throws Exception {
        var model = mock(ChatModel.class);
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        when(model.call(any(Prompt.class))).thenAnswer(inv -> {
            assertThat(ChatChannelContext.getConversationId()).isEqualTo("web-fixture");
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException e) { interrupted.countDown(); }
            return toolCall();
        });
        var tool = new FixtureTool();
        var client = client(model, tool, limits(10, Duration.ofSeconds(10)));
        var caller = Thread.ofVirtual().start(() -> {
            ChatChannelContext.set(ChatChannelContext.Channel.WEB, "web-fixture");
            try { assertThatThrownBy(() -> client.prompt("cancel").call().content())
                    .isInstanceOf(ExecutionLimitException.class).hasMessageContaining("cancelled"); }
            finally { ChatChannelContext.clear(); }
        });
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        caller.interrupt(); caller.join(2000);
        assertThat(caller.isAlive()).isFalse();
        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(tool.calls).hasValue(0);
    }
    @Test void providerFailureDoesNotExecuteToolsOrPoisonNextTurn() {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("fixture failure"))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("recovered")))));
        var tool = new FixtureTool();
        var client = client(model, tool, limits(2, Duration.ofSeconds(5)));
        assertThatThrownBy(() -> client.prompt("fail").call().content()).hasMessageContaining("fixture failure");
        assertThat(client.prompt("retry").call().content()).isEqualTo("recovered");
        assertThat(tool.calls).hasValue(0);
    }
    @Test void budgetPolicyIsCheckedOnEveryRound() {
        var model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenAnswer(inv -> toolCall());
        var checks = new AtomicInteger();
        var tool = new FixtureTool();
        var client = ChatClient.builder(model).defaultTools(tool)
                .defaultAdvisors(ExecutionAdvisors.create(limits(5, Duration.ofSeconds(5)), () -> {
                    if (checks.incrementAndGet() == 2) throw new ExecutionLimitException("budget paused");
                })).build();
        assertThatThrownBy(() -> client.prompt("budget").call().content()).hasMessageContaining("budget paused");
        assertThat(checks).hasValue(2);
        verify(model, times(1)).call(any(Prompt.class));
        assertThat(tool.calls).hasValue(1);
    }
    @Test void streamingUsesAbsoluteDeadlineEvenWhileEventsKeepArriving() throws Exception {
        var boundary = new ExecutionBoundaryAdvisor(limits(10, Duration.ofMillis(120)));
        var chain = mock(org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain.class);
        var stopped = new CountDownLatch(1);
        when(chain.nextStream(any())).thenReturn(reactor.core.publisher.Flux.interval(Duration.ofMillis(10))
                .map(i -> org.springframework.ai.chat.client.ChatClientResponse.builder().build())
                .doFinally(signal -> stopped.countDown()));
        var request = org.springframework.ai.chat.client.ChatClientRequest.builder().prompt(new Prompt("stream")).build();
        assertThatThrownBy(() -> boundary.adviseStream(request, chain).blockLast(Duration.ofSeconds(2)))
                .isInstanceOf(ExecutionLimitException.class).hasMessageContaining("deadline");
        assertThat(stopped.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test void streamingLoopStopsBeforeToolsWhenReportedCostExceedsCap() {
        var model = mock(ChatModel.class);
        var response = new ChatResponse(toolCall().getResults(),
                ChatResponseMetadata.builder().usage(new DefaultUsage(1000, 1000)).build());
        when(model.stream(any(Prompt.class))).thenReturn(reactor.core.publisher.Flux.just(response));
        var tool = new FixtureTool();
        var cap = new ExecutionLimits(10, Duration.ofSeconds(5), 10000, .001, 1, 1);
        var client = client(model, tool, cap);
        assertThatThrownBy(() -> client.prompt("stream cost").stream().chatResponse().collectList().block(Duration.ofSeconds(3)))
                .isInstanceOf(ExecutionLimitException.class).hasMessageContaining("cost limit");
        assertThat(tool.calls).hasValue(0);
    }

}
