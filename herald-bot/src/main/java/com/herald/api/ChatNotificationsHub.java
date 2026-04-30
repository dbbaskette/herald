package com.herald.api;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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
 * subscribed to {@link ChatController#notifications} for the active
 * conversation; the hub forwards new messages as SSE events.</p>
 *
 * <p>If no listener is connected when a result is ready, the message is
 * buffered in a small per-conversation queue (capped at 32 entries) and
 * delivered when a listener subscribes. This handles the case where the user
 * navigated away briefly during a long task. After the buffer is flushed it's
 * empty again — no persistence; this is a transient delivery mechanism, not a
 * durable inbox.</p>
 */
@Component
public class ChatNotificationsHub {

    private static final Logger log = LoggerFactory.getLogger(ChatNotificationsHub.class);
    private static final int MAX_BUFFERED_PER_CONVERSATION = 32;

    private final Map<String, List<SseEmitter>> listeners = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentLinkedQueue<Notification>> buffers = new ConcurrentHashMap<>();

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
            try { emitter.complete(); } catch (Exception ignored) {}
            detach.run();
        });
        emitter.onError(t -> detach.run());

        // Drain any buffered messages that arrived before this listener attached.
        ConcurrentLinkedQueue<Notification> buffered = buffers.remove(conversationId);
        if (buffered != null) {
            for (Notification n : buffered) {
                deliverOne(emitter, n, conversationId);
            }
        }
        return emitter;
    }

    /**
     * Push a notification to all current listeners for the conversation. If
     * none, the message is buffered (bounded) for delivery when one subscribes.
     */
    public void publish(String conversationId, String type, String content) {
        Notification n = new Notification(type, content);
        List<SseEmitter> all = listeners.get(conversationId);
        if (all == null || all.isEmpty()) {
            buffers.computeIfAbsent(conversationId, k -> new ConcurrentLinkedQueue<>()).add(n);
            // Trim to bound memory.
            ConcurrentLinkedQueue<Notification> q = buffers.get(conversationId);
            while (q.size() > MAX_BUFFERED_PER_CONVERSATION) {
                q.poll();
            }
            log.debug("Buffered notification for {} (no listener)", conversationId);
            return;
        }
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
            try { emitter.complete(); } catch (Exception ignored) {}
        }
    }

    private record Notification(String type, String content) {}
}
