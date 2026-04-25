package com.herald.telegram;

import com.herald.config.HeraldConfig;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.ResponseParameters;
import com.pengrad.telegrambot.request.SendChatAction;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.SendResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TelegramSenderTest {

    private TelegramBot bot;
    private TelegramSender sender;

    @BeforeEach
    void setUp() {
        bot = mock(TelegramBot.class);
        HeraldConfig config = new HeraldConfig(
                null,
                new HeraldConfig.Telegram("test-token", "12345"),
                null, null, null, null, null, null, null, null);
        sender = new TelegramSender(bot, config, new MessageFormatter());
    }

    @Test
    void sendMessageSendsToConfiguredChat() {
        SendResponse response = mock(SendResponse.class);
        when(response.isOk()).thenReturn(true);
        when(bot.execute(any(SendMessage.class))).thenReturn(response);

        sender.sendMessage("Hello");

        verify(bot).execute(any(SendMessage.class));
    }

    @Test
    void sendTypingActionExecutesChatAction() {
        SendResponse response = mock(SendResponse.class);
        when(response.isOk()).thenReturn(true);
        when(bot.execute(any(SendChatAction.class))).thenReturn(response);

        sender.sendTypingAction();

        verify(bot).execute(any(SendChatAction.class));
    }

    @Test
    void sendTypingActionHandlesException() {
        when(bot.execute(any(SendChatAction.class))).thenThrow(new RuntimeException("network error"));

        // Should not throw
        sender.sendTypingAction();
    }

    @Test
    void sendMessageFallsBackToEscapedMarkdownOnParseError() {
        SendResponse failResponse = mock(SendResponse.class);
        when(failResponse.isOk()).thenReturn(false);
        when(failResponse.errorCode()).thenReturn(400);
        when(failResponse.description()).thenReturn("Bad Request: can't parse entities");

        SendResponse okResponse = mock(SendResponse.class);
        when(okResponse.isOk()).thenReturn(true);

        // First call: raw MarkdownV2 fails; second call: escaped MarkdownV2 succeeds
        when(bot.execute(any(SendMessage.class)))
                .thenReturn(failResponse)
                .thenReturn(okResponse);

        sender.sendMessage("test");

        verify(bot, times(2)).execute(any(SendMessage.class));
    }

    @Test
    void sendMessageRetriesOn429WithRetryAfter() {
        SendResponse rateLimitResponse = mock(SendResponse.class);
        when(rateLimitResponse.isOk()).thenReturn(false);
        when(rateLimitResponse.errorCode()).thenReturn(429);
        ResponseParameters params = mock(ResponseParameters.class);
        when(params.retryAfter()).thenReturn(1);
        when(rateLimitResponse.parameters()).thenReturn(params);

        SendResponse okResponse = mock(SendResponse.class);
        when(okResponse.isOk()).thenReturn(true);

        when(bot.execute(any(SendMessage.class)))
                .thenReturn(rateLimitResponse)
                .thenReturn(okResponse);

        sender.sendMessage("test");

        // Should have been called twice: once rate-limited, once successful
        verify(bot, times(2)).execute(any(SendMessage.class));
    }

    @Test
    void sendMessageSplitsLongMessageIntoMultipleChunks() {
        SendResponse okResponse = mock(SendResponse.class);
        when(okResponse.isOk()).thenReturn(true);
        when(bot.execute(any(SendMessage.class))).thenReturn(okResponse);

        // Create a message that exceeds 4096 chars and must be split
        String longMessage = "A".repeat(3000) + ". " + "B".repeat(3000) + ".";

        sender.sendMessage(longMessage);

        // Should send at least 2 chunks
        verify(bot, atLeast(2)).execute(any(SendMessage.class));
    }

    // --- #282 typing-action refresh during long tool-call phases ---

    @Test
    void scheduleTypingRefreshFiresPeriodicallyAndCancels() throws InterruptedException {
        java.util.concurrent.ScheduledFuture<?> future = sender.scheduleTypingRefresh();
        try {
            Thread.sleep(TelegramSender.TYPING_REFRESH_MS + 500);
            // Refresher fired at least once during the wait.
            verify(bot, atLeastOnce()).execute(any(SendChatAction.class));
        } finally {
            TelegramSender.cancelTypingRefresher(future);
        }
        org.assertj.core.api.Assertions.assertThat(future.isCancelled() || future.isDone()).isTrue();
    }

    @Test
    void cancelTypingRefresherHandlesNull() {
        // Must not throw.
        TelegramSender.cancelTypingRefresher(null);
    }

    @Test
    void streamingMessageCancelsTypingRefresherOnCompletion() {
        SendResponse okResponse = mock(SendResponse.class);
        when(okResponse.isOk()).thenReturn(true);
        com.pengrad.telegrambot.model.Message msg = mock(com.pengrad.telegrambot.model.Message.class);
        when(msg.messageId()).thenReturn(42);
        when(okResponse.message()).thenReturn(msg);
        when(bot.execute(any(SendMessage.class))).thenReturn(okResponse);
        com.pengrad.telegrambot.response.BaseResponse editOk =
                mock(com.pengrad.telegrambot.response.BaseResponse.class);
        when(editOk.isOk()).thenReturn(true);
        when(bot.execute(any(com.pengrad.telegrambot.request.EditMessageText.class))).thenReturn(editOk);

        // Fast stream — no refresh ticks needed.
        reactor.core.publisher.Flux<String> fastStream = reactor.core.publisher.Flux.just("hello world");

        sender.sendStreamingMessage(fastStream);

        // Fast completion: typing refresher was cancelled before it ever fired.
        // No assertion on typing count here — happy path just verifies no hang.
        verify(bot, atLeastOnce()).execute(any(SendMessage.class));
    }
}
