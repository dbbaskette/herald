package com.herald.api;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Per-conversation SSE notification hub.
 *
 * <p>The web chat dispatches long-running document-processing turns to a
 * background virtual thread (markitdown / wiki-ingest can take minutes), then
 * pushes the final assistant message here once it's ready. Browsers stay
 * subscribed to {@link ChatController#notifications(String)} for the active
 * conversation; the hub forwards new messages as SSE events.</p>
 *
 * <p>Delivery is connect-or-miss: if no listener is registered when a result
 * is ready, the message is dropped (logged at info level). Earlier versions
 * buffered the last 32 messages per conversation to handle "user navigated
 * away mid-task" — that buffer was retired in #358 because the chat history
 * store already captures the assistant reply, so a reconnecting client can
 * recover via the regular history endpoint instead of the hub's transient
 * queue.</p>
 */
@Component
public class ChatNotificationsHub {

    private static final Logger log = LoggerFactory.getLogger(ChatNotificationsHub.class);

    private final Map<String, List<SseEmitter>> listeners = new ConcurrentHashMap<>();

    public SseEmitter register(String conversationId, long timeoutMs) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        listeners.computeIfAbsent(conversationId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        Runnable detach = () -> {
            List<SseEmitter> all = listeners.get(conversationId);
            if (all != null) {
                all.remove(emitter);
                if (all.isEmpty()) {
                    listeners.remove(conversationId);
                }
            }
        };
        emitter.onCompletion(detach);
        emitter.onTimeout(() -> {
            SseSender.complete(emitter);
            detach.run();
        });
        emitter.onError(t -> detach.run());

        return emitter;
    }

    /**
     * Push a notification to all current listeners for the conversation. If
     * none are connected, the message is dropped — the chat history store is
     * the source of truth, and the reconnecting client can refresh from there.
     */
    public void publish(String conversationId, String type, String content) {
        List<SseEmitter> all = listeners.get(conversationId);
        if (all == null || all.isEmpty()) {
            log.info("No listener for conversation {} — dropping {} notification",
                    conversationId, type);
            return;
        }
        Notification n = new Notification(type, content);
        Iterator<SseEmitter> it = all.iterator();
        while (it.hasNext()) {
            SseEmitter emitter = it.next();
            deliverOne(emitter, n, conversationId);
        }
    }

    private void deliverOne(SseEmitter emitter, Notification n, String conversationId) {
        try {
            emitter.send(SseEmitter.event().name(n.type).data(n.content));
        } catch (IOException | IllegalStateException e) {
            log.debug("Failed to deliver notification to {}: {}",
                    conversationId, e.getMessage());
            SseSender.complete(emitter);
        }
    }

    private record Notification(String type, String content) {}
}
