package com.herald.agent;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Owns one execution state around the upstream loop, including tools and memory. */
public final class ExecutionBoundaryAdvisor implements CallAdvisor, StreamAdvisor {
    private final ExecutionLimits limits;
    public ExecutionBoundaryAdvisor(ExecutionLimits limits) { this.limits = limits; }
    private ChatClientRequest bounded(ChatClientRequest request, ExecutionState state) {
        var prompt = request.prompt();
        if (prompt.getOptions() instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options) {
            prompt = new org.springframework.ai.chat.prompt.Prompt(prompt.getInstructions(),
                    options.mutate().toolContext(ExecutionState.KEY, state).build());
        }
        return request.mutate().prompt(prompt).context(ExecutionState.KEY, state).build();
    }
    @SuppressWarnings("unchecked")
    private ExecutionState newState(ChatClientRequest request) {
        var state = new ExecutionState(limits);
        if (request.context().get(ExecutionUsage.OBSERVER) instanceof java.util.function.Consumer<?> observer)
            state.observe((java.util.function.Consumer<ExecutionUsage>) observer);
        return state;
    }
    @Override public String getName() { return getClass().getSimpleName(); }
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
    @Override public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        var inherited = ExecutionState.current();
        boolean ownsState = inherited == null;
        var state = ownsState ? newState(request) : inherited;
        var bounded = bounded(request, state);
        var channel = ChatChannelContext.get();
        var conversation = ChatChannelContext.getConversationId();
        var work = new FutureTask<ChatClientResponse>(() -> {
            if (channel != null) ChatChannelContext.set(channel, conversation);
            try (var ignored = ExecutionState.attach(state)) { state.check(); return chain.nextCall(bounded); }
            finally { ChatChannelContext.clear(); }
        });
        var registration = state.onCancel(() -> work.cancel(true));
        Thread.ofVirtual().name("herald-agent-turn").start(work);
        try {
            return work.get(state.remainingNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw new ExecutionLimitException("overall deadline reached");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExecutionLimitException("execution cancelled");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            if (e.getCause() instanceof Error cause) throw cause;
            throw new IllegalStateException("Agent execution failed", e.getCause());
        } finally {
            registration.close();
            if (ownsState) state.cancel();
            work.cancel(true);
        }
    }
    @Override public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            var state = newState(request);
            var bounded = bounded(request, state);
            // Reuse one timer across chunks: timeout cancels upstream while cache prevents resets.
            var alarm = Mono.delay(limits.deadline()).cache();
            return chain.nextStream(bounded)
                    .timeout(alarm, ignored -> alarm)
                    .onErrorMap(TimeoutException.class, failure -> new ExecutionLimitException("overall deadline reached"))
                    .doFinally(signal -> state.cancel());
        });
    }
}
