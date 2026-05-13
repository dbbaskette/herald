package com.herald.api;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Helper for {@link SseEmitter} send/complete operations that swallows the
 * two routine failure modes:
 *
 * <ul>
 *   <li>{@link IOException} — client disconnected mid-stream.</li>
 *   <li>{@link IllegalStateException} — emitter already completed or timed out.</li>
 * </ul>
 *
 * <p>Neither is recoverable, neither should crash the caller, and both are
 * logged at debug level so the noise stays out of normal runtime logs.</p>
 */
final class SseSender {

    private static final Logger log = LoggerFactory.getLogger(SseSender.class);

    private SseSender() {}

    /** Send a named event with a string payload. Safe to call after emitter completion. */
    static void send(SseEmitter emitter, String event, String data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data == null ? "" : data));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE send failed (event={}): {}", event, e.getMessage());
        }
    }

    /** Complete the emitter; swallow any failure (already-completed, IO error). */
    static void complete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("SSE complete failed: {}", e.getMessage());
        }
    }

    /** Complete with error; swallow any failure (already-completed, IO error). */
    static void completeWithError(SseEmitter emitter, Throwable cause) {
        try {
            emitter.completeWithError(cause);
        } catch (Exception e) {
            log.debug("SSE completeWithError failed: {}", e.getMessage());
        }
    }

    /**
     * Convenience: send a named event, then complete the emitter. Used by
     * controllers that want to deliver a single response and shut down.
     */
    static void sendAndComplete(SseEmitter emitter, String event, String data) {
        send(emitter, event, data);
        complete(emitter);
    }
}
