package com.herald.agent;

/**
 * Abstraction for sending messages to the user (Telegram, console, etc.).
 * Lives in herald-core so persistence and cron modules can reference it
 * without a compile dependency on any specific messaging implementation.
 */
public interface MessageSender {

    void sendMessage(String text);
}
