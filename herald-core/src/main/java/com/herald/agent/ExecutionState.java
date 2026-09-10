package com.herald.agent;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.ai.chat.metadata.Usage;

public final class ExecutionState {
    private static final ThreadLocal<ExecutionState> CURRENT = new ThreadLocal<>();
    private final java.util.concurrent.ConcurrentMap<Object, Runnable> cancellationHooks = new java.util.concurrent.ConcurrentHashMap<>();
    public interface Scope extends AutoCloseable { @Override void close(); }
    public interface Registration extends AutoCloseable { @Override void close(); }
    public static ExecutionState current() { return CURRENT.get(); }
    public static Scope attach(ExecutionState state) {
        var previous = CURRENT.get();
        CURRENT.set(state);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }
    public Registration onCancel(Runnable hook) {
        Object key = new Object();
        cancellationHooks.put(key, hook);
        if (cancelled.get() && cancellationHooks.remove(key) != null) hook.run();
        return () -> cancellationHooks.remove(key);
    }
    void cancel() {
        cancelled.set(true);
        cancellationHooks.forEach((key, hook) -> {
            if (cancellationHooks.remove(key) != null) { try { hook.run(); } catch (RuntimeException ignored) {} }
        });
    }
    long remainingNanos() { return Math.max(1, limits.deadline().toNanos() - (System.nanoTime() - started)); }
    static final String KEY = ExecutionState.class.getName();
    final ExecutionLimits limits;
    final long started = System.nanoTime();
    final AtomicBoolean cancelled = new AtomicBoolean();
    private int steps;
    private long tokens;
    private double cost;
    private final java.util.Map<String, long[]> usageByModel = new java.util.LinkedHashMap<>();
    private java.util.function.Consumer<ExecutionUsage> observer = ignored -> {};
    void observe(java.util.function.Consumer<ExecutionUsage> observer) { this.observer = observer; }


    ExecutionState(ExecutionLimits limits) { this.limits = limits; }
    public synchronized void check() {
        if (cancelled.get() || Thread.currentThread().isInterrupted())
            throw new ExecutionLimitException("execution cancelled");
        if (System.nanoTime() - started >= limits.deadline().toNanos())
            throw new ExecutionLimitException("overall deadline reached");
        checkSpend();
    }
    synchronized void beforeModel() {
        check();
        if (steps >= limits.maxSteps()) throw new ExecutionLimitException("model step limit reached");
        checkSpend();
        steps++;
    }
    synchronized void account(org.springframework.ai.chat.model.ChatResponse response) {
        Usage usage = response == null ? null : response.getMetadata().getUsage();
        if (usage == null) {
            check();
            if (limits.maxCostUsd() > 0) throw new ExecutionLimitException("provider did not report usage for the configured cost cap");
            return;
        }
        long in = usage.getPromptTokens() == null ? 0 : Math.max(0, usage.getPromptTokens());
        long out = usage.getCompletionTokens() == null ? 0 : Math.max(0, usage.getCompletionTokens());
        long cacheRead = usage.getCacheReadInputTokens() == null ? 0 : Math.max(0, usage.getCacheReadInputTokens());
        long cacheWrite = usage.getCacheWriteInputTokens() == null ? 0 : Math.max(0, usage.getCacheWriteInputTokens());
        if (limits.maxCostUsd() > 0 && in + out + cacheRead + cacheWrite == 0)
            throw new ExecutionLimitException("provider did not report usage for the configured cost cap");
        String model = response.getMetadata().getModel() == null ? "unknown" : response.getMetadata().getModel();
        var totals = usageByModel.computeIfAbsent(model, ignored -> new long[4]);
        totals[0] += in; totals[1] += out; totals[2] += cacheRead; totals[3] += cacheWrite;
        observer.accept(usageSnapshot());
        // Cache input is separate for Anthropic; full read and double write rates are conservative.
        tokens += in + out + cacheRead + cacheWrite;
        cost += ((in + cacheRead + 2 * cacheWrite) * limits.inputUsdPerMillion() + out * limits.outputUsdPerMillion()) / 1_000_000;
        check();
        checkSpend();
    }
    public synchronized ExecutionUsage usageSnapshot() {
        return new ExecutionUsage(usageByModel.entrySet().stream().map(entry -> {
            var v = entry.getValue();
            return new ExecutionUsage.ModelUsage(entry.getKey(), v[0], v[1], v[2], v[3]);
        }).toList());
    }
    private void checkSpend() {
        if (tokens >= limits.maxTokens()) throw new ExecutionLimitException("token limit reached");
        if (limits.maxCostUsd() > 0 && cost >= limits.maxCostUsd())
            throw new ExecutionLimitException("estimated cost limit reached");
    }
}
