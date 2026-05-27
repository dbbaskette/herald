package com.herald.agent;

/**
 * Callback invoked when a web-channel approval is registered and the Console
 * should be notified (SSE {@code approval_required} event).
 */
@FunctionalInterface
public interface ApprovalPromptHandler {
    void onApprovalRequired(PendingApproval approval);
}
