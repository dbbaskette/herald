package com.herald.telegram;

/**
 * Narrow contract between {@link TelegramPoller} and the slash-command layer.
 * The poller only needs to know: "here's a user message — did a command
 * consume it?" Depending on the concrete {@link CommandHandler} makes tests
 * harder to set up (Mockito's inline mocker struggles once the handler's
 * transitive type graph pulls in complex record nests), so the poller takes
 * this functional interface instead.
 */
@FunctionalInterface
public interface SlashCommandDispatcher {

    /**
     * Handle the given user text as a slash command.
     *
     * @return {@code true} if the text was a slash command and has been fully
     *         handled (the poller should skip normal agent dispatch);
     *         {@code false} if the text should be forwarded to the agent loop.
     */
    boolean handle(String text);
}
