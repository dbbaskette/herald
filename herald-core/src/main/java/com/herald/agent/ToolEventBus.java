package com.herald.agent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

/**
 * Pub/sub for tool-call observation events keyed by conversation id (#362).
 *
 * <p>{@link ObservingToolCallback} publishes {@code tool_start} / {@code tool_end}
 * events here whenever the agent invokes a wrapped {@link
 * org.springframework.ai.tool.ToolCallback}. Listeners (e.g. {@code ChatController})
 * subscribe to forward those events out on the chat SSE stream so the user sees
 * what tools the agent is running in real time.</p>
 *
 * <p>Listeners are weak in spirit — if no one's subscribed, events are dropped.
 * This is a transient observation channel, not a durable log.</p>
 */
@Component
public class ToolEventBus {

    public sealed interface ToolEvent permits ToolStart, ToolEnd {
        String conversationId();
        String callId();
        String toolName();
    }

    public record ToolStart(String conversationId, String callId, String toolName,
                            String argsPreview) implements ToolEvent {}

    public record ToolEnd(String conversationId, String callId, String toolName,
                          long elapsedMs, boolean ok,
                          String summary) implements ToolEvent {}

    private final Map<String, List<Consumer<ToolEvent>>> listeners = new ConcurrentHashMap<>();

    /** Subscribe to events for a conversation. Returns a token used to unsubscribe. */
    public Runnable subscribe(String conversationId, Consumer<ToolEvent> listener) {
        listeners.computeIfAbsent(conversationId, k -> new CopyOnWriteArrayList<>()).add(listener);
        return () -> unsubscribe(conversationId, listener);
    }

    /** Publish an event to all subscribers for the conversation. */
    public void publish(ToolEvent event) {
        if (event == null) return;
        List<Consumer<ToolEvent>> subs = listeners.get(event.conversationId());
        if (subs == null) return;
        for (Consumer<ToolEvent> l : subs) {
            try { l.accept(event); } catch (Exception ignored) {}
        }
    }

    private void unsubscribe(String conversationId, Consumer<ToolEvent> listener) {
        List<Consumer<ToolEvent>> subs = listeners.get(conversationId);
        if (subs == null) return;
        subs.remove(listener);
        if (subs.isEmpty()) listeners.remove(conversationId);
    }
}
