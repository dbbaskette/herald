package com.herald.agent;

/**
 * Thread-local context that tracks which channel (Telegram, web chat, etc.)
 * the current agent turn originated from, plus the conversation ID.
 *
 * <p>Set at the start of an agent call (chat / streamChat) and cleared when done.
 * Tools like {@code AskUserQuestionTool} can inspect this to decide routing —
 * e.g. auto-approve questions from web chat where interactive Q&amp;A is not
 * yet supported, instead of routing to Telegram and blocking forever.</p>
 *
 * <p>The conversation ID is exposed so observation hooks (e.g. tool-call
 * visualization, #362) can publish events keyed by conversation without
 * threading the ID through every tool callback signature.</p>
 */
public final class ChatChannelContext {

    public enum Channel { TELEGRAM, WEB }

    private static final ThreadLocal<Channel> CHANNEL = new ThreadLocal<>();
    private static final ThreadLocal<String> CONVERSATION_ID = new ThreadLocal<>();

    private ChatChannelContext() {}

    /** Set the current channel for this thread. */
    public static void set(Channel channel) {
        CHANNEL.set(channel);
    }

    /** Set the current channel and conversation id for this thread. */
    public static void set(Channel channel, String conversationId) {
        CHANNEL.set(channel);
        CONVERSATION_ID.set(conversationId);
    }

    /** Get the current channel, or {@code null} if not set. */
    public static Channel get() {
        return CHANNEL.get();
    }

    /** Get the current conversation id, or {@code null} if not set. */
    public static String getConversationId() {
        return CONVERSATION_ID.get();
    }

    /** Clear the context (call in a finally block). */
    public static void clear() {
        CHANNEL.remove();
        CONVERSATION_ID.remove();
    }

    /** Convenience: returns {@code true} when running inside a web chat turn. */
    public static boolean isWeb() {
        return CHANNEL.get() == Channel.WEB;
    }
}
