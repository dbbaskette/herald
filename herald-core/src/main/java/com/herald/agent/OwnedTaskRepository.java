package com.herald.agent;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springaicommunity.agent.tools.task.repository.BackgroundTask;
import org.springaicommunity.agent.tools.task.repository.TaskRepository;

/** Upstream task records with interruptible workers and originating-turn ownership. */
public final class OwnedTaskRepository implements TaskRepository, AutoCloseable {
    private record Entry(ExecutionState owner, BackgroundTask task, FutureTask<Void> work,
                         AtomicReference<ExecutionState.Registration> registration) {}
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private volatile boolean closed;

    @Override public BackgroundTask putTask(String id, Supplier<String> supplier) {
        if (closed) throw new IllegalStateException("Task repository closed");
        var owner = ExecutionState.current();
        if (owner != null) owner.check();
        var channel = ChatChannelContext.get();
        var conversation = ChatChannelContext.getConversationId();
        var result = new CompletableFuture<String>();
        var registration = new AtomicReference<ExecutionState.Registration>();
        var work = new FutureTask<Void>(() -> {
            try (var scope = ExecutionState.attach(owner)) {
                if (channel != null) ChatChannelContext.set(channel, conversation);
                if (owner != null) owner.check();
                result.complete(supplier.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            } finally { ChatChannelContext.clear(); }
            return null;
        }) {
            @Override protected void done() {
                if (isCancelled()) result.cancel(false);
                if (isCancelled()) {
                    entries.computeIfPresent(id, (key, entry) -> entry.work == this ? null : entry);
                    var hook = registration.getAndSet(null);
                    if (hook != null) hook.close();
                }
            }
        };
        var task = new BackgroundTask(id, result);
        var entry = new Entry(owner, task, work, registration);
        if (entries.putIfAbsent(id, entry) != null) throw new IllegalArgumentException("Task already exists");
        result.whenComplete((ignored, failure) -> { if (result.isCancelled()) work.cancel(true); });
        if (owner != null) {
            registration.set(owner.onCancel(() -> { entries.remove(id, entry); work.cancel(true); }));
            if (work.isCancelled()) { var hook = registration.getAndSet(null); if (hook != null) hook.close(); }
        }
        if (closed) work.cancel(true);
        Thread.ofVirtual().name("herald-worker").start(work);
        return task;
    }
    @Override public BackgroundTask getTasks(String id) {
        var entry = entries.get(id);
        return entry != null && entry.owner == ExecutionState.current() ? entry.task : null;
    }
    @Override public void removeTask(String id) {
        var entry = entries.get(id);
        if (entry != null && entry.owner == ExecutionState.current() && entries.remove(id, entry)) cancel(entry);
    }
    @Override public void clear() {
        var owner = ExecutionState.current();
        entries.forEach((id, entry) -> {
            if (entry.owner == owner && entries.remove(id, entry)) cancel(entry);
        });
    }
    private void cancel(Entry entry) {
        entry.work.cancel(true);
        var hook = entry.registration.getAndSet(null);
        if (hook != null) hook.close();
    }
    @Override public void close() {
        closed = true;
        entries.forEach((id, entry) -> cancel(entry));
        entries.clear();
    }
}
