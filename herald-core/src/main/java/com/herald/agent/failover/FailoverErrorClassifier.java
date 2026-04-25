package com.herald.agent.failover;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Classifies provider exceptions into {@link FailoverReason} buckets so the
 * failover chain can apply config-driven retry rules uniformly across
 * Anthropic, OpenAI, Gemini, Ollama, and LM Studio.
 *
 * <p>Spring AI wraps provider errors in {@code RuntimeException} subclasses
 * that carry the HTTP status in the message ({@code "429 Too Many Requests"})
 * or nest the originating {@code WebClientResponseException}. We walk the
 * cause chain, sniff both the class hierarchy and the message for telltale
 * strings, and fall through to {@link FailoverReason#OTHER} — which the
 * retry loop treats as terminal unless the user opts in to OTHER.</p>
 */
public final class FailoverErrorClassifier {

    private FailoverErrorClassifier() {
    }

    /**
     * @return the most specific {@link FailoverReason} that matches anywhere
     *         in {@code throwable}'s cause chain, or {@link FailoverReason#OTHER}
     *         if nothing matches.
     */
    public static FailoverReason classify(Throwable throwable) {
        if (throwable == null) {
            return FailoverReason.OTHER;
        }
        // Walk cause chain — Spring AI often wraps the real cause twice.
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            FailoverReason reason = classifySingle(t);
            if (reason != FailoverReason.OTHER) {
                return reason;
            }
            // Defensive — break self-referential cause loops.
            if (t.getCause() == t) {
                break;
            }
        }
        return FailoverReason.OTHER;
    }

    private static FailoverReason classifySingle(Throwable t) {
        // Socket-level errors before status-code inspection — these never carry HTTP codes.
        if (t instanceof SocketTimeoutException) {
            return FailoverReason.TIMEOUT;
        }
        if (t instanceof ConnectException || t instanceof UnknownHostException) {
            return FailoverReason.UNAVAILABLE;
        }

        String className = t.getClass().getName();
        String message = t.getMessage();
        String lowerMessage = message == null ? "" : message.toLowerCase(Locale.ROOT);

        // Spring WebClient surfaces the status code in the exception class name
        // (WebClientResponseException$TooManyRequests / InternalServerError / etc.)
        // and repeats it in the message ("429 Too Many Requests").
        if (className.contains("TooManyRequests") || lowerMessage.contains("429")
                || lowerMessage.contains("rate limit") || lowerMessage.contains("rate_limit")) {
            return FailoverReason.RATE_LIMIT;
        }
        if (lowerMessage.contains("timeout") || lowerMessage.contains("timed out")) {
            return FailoverReason.TIMEOUT;
        }
        if (lowerMessage.contains("connection refused") || lowerMessage.contains("unknown host")
                || lowerMessage.contains("unavailable") || lowerMessage.contains("503")) {
            // 503 is a server error too, but "service unavailable" aligns closer with retry semantics.
            return FailoverReason.UNAVAILABLE;
        }
        // Generic 5xx sniff — check for "5xx" status codes in the message.
        if (lowerMessage.contains("500 ") || lowerMessage.contains("502 ")
                || lowerMessage.contains("504 ") || lowerMessage.contains("internal server error")
                || lowerMessage.contains("bad gateway") || lowerMessage.contains("gateway timeout")) {
            return FailoverReason.SERVER_ERROR;
        }
        return FailoverReason.OTHER;
    }
}
