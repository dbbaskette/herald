package com.herald.agent;

/**
 * Abstraction for sending messages to the user (Telegram, console, etc.).
 * Lives in herald-core so persistence and cron modules can reference it
 * without a compile dependency on any specific messaging implementation.
 */
public interface MessageSender {

    void sendMessage(String text);

    /** Send with an observable acknowledgement, or throw on any terminal failure.
     * Existing fire-and-forget implementations must opt in rather than silently
     * claiming delivery. This does not prove the recipient has read the message. */
    default void sendMessageOrThrow(String text) {
        throw new UnsupportedOperationException("This message transport does not support acknowledged delivery");
    }
}
