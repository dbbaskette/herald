package com.herald.agent;

import java.time.Instant;

/**
 * Metadata for a pending human-in-the-loop approval (memory edits, etc.).
 * Exposed via {@code GET /api/approvals} and pushed to web clients over SSE
 * as {@code approval_required} events.
 */
public record PendingApproval(
        String id,
        String kind,
        String conversationId,
        String channel,
        String toolName,
        String path,
        String diffPreview,
        Instant createdAt,
        int timeoutSeconds) {}
