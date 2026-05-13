package com.herald.config;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Central registry of Herald-wide limits and well-known paths/IDs.
 *
 * <p>Previously these constants were scattered across {@code ChatController},
 * {@code ChatNotificationsHub}, {@code TelegramPoller}, and {@code AgentService}
 * with several drift-prone duplicates (a 10 MB vs 20 MB file cap, two different
 * "default" conversation IDs, two definitions of the uploads dir). Anything that
 * truly is a Herald-wide invariant lives here.</p>
 *
 * <p>Values that should be operator-tunable per deployment (e.g. the chat-stream
 * timeout, which interacts with the Spring MVC async timeout in
 * {@code application.yaml}) should be promoted to {@code @ConfigurationProperties}
 * later. For now they're constants because they only have one correct value.</p>
 */
public final class HeraldLimits {

    private HeraldLimits() {}

    // ── Conversation identifiers ─────────────────────────────────────────

    /**
     * Default conversation ID used when no caller-supplied ID is available
     * (single-user Telegram default; legacy callers; tests).
     */
    public static final String DEFAULT_CONVERSATION_ID = "default";

    /**
     * Conversation ID assigned to the web-console fallback when the browser
     * doesn't generate a per-tab ID. Kept distinct from
     * {@link #DEFAULT_CONVERSATION_ID} so web and Telegram histories don't bleed
     * together by accident.
     */
    public static final String WEB_CONVERSATION_ID = "web-console";

    // ── File uploads ─────────────────────────────────────────────────────

    /**
     * Per-file size cap for uploads (Telegram media + web multipart). Unified
     * at 20 MB after the audit in #357 found Telegram capping at 10 MB and the
     * web side at 20 MB with no documented reason for the split.
     */
    public static final long MAX_UPLOAD_BYTES = 20L * 1024 * 1024;

    /** Where saved uploads land. Shared by Telegram + web chat. */
    public static final Path UPLOADS_DIR =
            Path.of(System.getProperty("user.home"), ".herald", "uploads");

    // ── SSE / streaming timeouts ─────────────────────────────────────────

    /**
     * SseEmitter timeout for individual chat-streaming turns
     * ({@code /api/chat/stream}, {@code /api/chat/stream-multipart}). Long
     * tool chains (markitdown + wiki-ingest) can need several minutes; 5 min
     * is the documented max single-turn budget.
     *
     * <p>The Spring MVC async timeout in {@code herald-ui/application.yaml}
     * must be {@code >=} this value, or the UI proxy will close the stream
     * before the upstream completes.</p>
     */
    public static final Duration STREAM_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Long-poll lifetime for the {@code /api/chat/notifications} channel.
     * The browser auto-reconnects on timeout; 30 min is a balance between
     * keeping idle sessions alive and freeing zombie listeners.
     */
    public static final Duration NOTIFICATIONS_TIMEOUT = Duration.ofMinutes(30);

    // ── Convenience accessors (millis) for legacy SseEmitter ctor ────────

    public static long streamTimeoutMs() { return STREAM_TIMEOUT.toMillis(); }
    public static long notificationsTimeoutMs() { return NOTIFICATIONS_TIMEOUT.toMillis(); }
}
