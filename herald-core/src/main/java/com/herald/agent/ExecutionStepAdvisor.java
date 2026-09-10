package com.herald.agent;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/** Runs inside ToolCallAdvisor, once per provider round, guarding every tool callback. */
public final class ExecutionStepAdvisor implements CallAdvisor, StreamAdvisor {
    private final Runnable budgetCheck;
    public ExecutionStepAdvisor() { this(() -> {}); }
    public ExecutionStepAdvisor(Runnable budgetCheck) { this.budgetCheck = budgetCheck; }
    @Override public String getName() { return getClass().getSimpleName(); }
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE - 1; }
    private ExecutionState state(ChatClientRequest request) {
        var value = request.context().get(ExecutionState.KEY);
        if (!(value instanceof ExecutionState state)) throw new IllegalStateException("Execution boundary advisor is required");
        return state;
    }
    private ChatClientRequest prepare(ChatClientRequest request, ExecutionState state) {
        state.beforeModel();
        try (var ignored = ExecutionState.attach(state)) { budgetCheck.run(); }
        return request;
    }
    @Override public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        var state = state(request);
        var response = chain.nextCall(prepare(request, state));
        state.account(response.chatResponse());
        return response;
    }
    @Override public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            var state = state(request);
            var usage = new AtomicReference<org.springframework.ai.chat.model.ChatResponse>();
            var accounted = new java.util.concurrent.atomic.AtomicBoolean();
            Runnable partial = () -> {
                if (usage.get() != null && accounted.compareAndSet(false, true)) {
                    try { state.account(usage.get()); } catch (ExecutionLimitException stopped) { /* original termination wins */ }
                }
            };
            return chain.nextStream(prepare(request, state))
                    .doOnNext(response -> {
                        state.check();
                        if (response.chatResponse() != null && response.chatResponse().getMetadata().getUsage() != null)
                            usage.set(response.chatResponse());
                    })
                    .concatWith(Flux.defer(() -> {
                        if (accounted.compareAndSet(false, true)) state.account(usage.get());
                        return Flux.empty();
                    }))
                    .doOnError(failure -> partial.run())
                    .doOnCancel(partial);
        });
    }
}
