package com.herald.telegram;

import com.herald.config.HeraldConfig;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.Chat;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.request.GetUpdates;
import com.pengrad.telegrambot.response.GetUpdatesResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.lang.reflect.Field;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TelegramPollerTest {

    private TelegramBot bot;
    private TelegramSender sender;
    private TelegramPoller poller;

    @BeforeEach
    void setUp() {
        bot = mock(TelegramBot.class);
        sender = mock(TelegramSender.class);
        HeraldConfig config = new HeraldConfig(
                null,
                new HeraldConfig.Telegram("test-token", "12345"));
        poller = new TelegramPoller(bot, config, sender);
    }

    @Test
    void pollHandlesEmptyUpdates() {
        GetUpdatesResponse response = mock(GetUpdatesResponse.class);
        when(response.isOk()).thenReturn(true);
        when(response.updates()).thenReturn(List.of());
        when(bot.execute(any(GetUpdates.class))).thenReturn(response);

        poller.poll();

        verify(sender, never()).sendTypingAction();
    }

    @Test
    void pollHandlesFailedResponse() {
        GetUpdatesResponse response = mock(GetUpdatesResponse.class);
        when(response.isOk()).thenReturn(false);
        when(response.description()).thenReturn("Unauthorized");
        when(bot.execute(any(GetUpdates.class))).thenReturn(response);

        poller.poll();

        verify(sender, never()).sendTypingAction();
    }

    @Test
    void pollDropsMessageFromUnauthorizedChat() throws Exception {
        Update update = createUpdate(99999L, "hello");
        GetUpdatesResponse response = mock(GetUpdatesResponse.class);
        when(response.isOk()).thenReturn(true);
        when(response.updates()).thenReturn(List.of(update));
        when(bot.execute(any(GetUpdates.class))).thenReturn(response);

        poller.poll();

        verify(sender, never()).sendTypingAction();
    }

    @Test
    void pollProcessesMessageFromAuthorizedChat() throws Exception {
        Update update = createUpdate(12345L, "hello");
        GetUpdatesResponse response = mock(GetUpdatesResponse.class);
        when(response.isOk()).thenReturn(true);
        when(response.updates()).thenReturn(List.of(update));
        when(bot.execute(any(GetUpdates.class))).thenReturn(response);

        poller.poll();

        verify(sender).sendTypingAction();
    }

    @Test
    void pollHandlesExceptionGracefully() {
        when(bot.execute(any(GetUpdates.class))).thenThrow(new RuntimeException("network error"));

        poller.poll();

        // Should not throw — exception is caught and logged
        verify(sender, never()).sendTypingAction();
    }

    private Update createUpdate(long chatId, String text) throws Exception {
        Chat chat = mock(Chat.class);
        when(chat.id()).thenReturn(chatId);

        Message message = mock(Message.class);
        when(message.chat()).thenReturn(chat);
        when(message.text()).thenReturn(text);

        Update update = mock(Update.class);
        when(update.updateId()).thenReturn(1);
        when(update.message()).thenReturn(message);

        return update;
    }
}
