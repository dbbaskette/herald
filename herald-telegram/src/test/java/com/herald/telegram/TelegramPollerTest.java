package com.herald.telegram;

import com.herald.agent.AgentService;
import com.herald.config.HeraldConfig;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.Chat;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.request.GetUpdates;
import com.pengrad.telegrambot.response.GetUpdatesResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TelegramPollerTest {

    private TelegramBot bot;
    private TelegramSender sender;
    private TelegramQuestionHandler questionHandler;
    private CommandHandler commandHandler;
    private AgentService agentService;
    private TelegramPoller poller;

    @BeforeEach
    void setUp() {
        bot = mock(TelegramBot.class);
        sender = mock(TelegramSender.class);
        questionHandler = mock(TelegramQuestionHandler.class);
        commandHandler = mock(CommandHandler.class);
        agentService = mock(AgentService.class);
        HeraldConfig config = new HeraldConfig(
                null,
                new HeraldConfig.Telegram("test-token", "12345"),
                null, null, null, null, null, null, null);
        poller = new TelegramPoller(bot, config, sender, questionHandler, commandHandler, agentService);
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
    void pollRoutesReplyToPendingQuestion() throws Exception {
        when(questionHandler.hasPendingQuestion()).thenReturn(true);
        when(questionHandler.resolveAnswer("B")).thenReturn(true);

        Update update = createUpdate(12345L, "B");
        GetUpdatesResponse response = mock(GetUpdatesResponse.class);
        when(response.isOk()).thenReturn(true);
        when(response.updates()).thenReturn(List.of(update));
        when(bot.execute(any(GetUpdates.class))).thenReturn(response);

        poller.poll();

        verify(questionHandler).resolveAnswer("B");
        verify(agentService, never()).chat(any());
    }

    @Test
    void pollFallsThroughToAgentWhenResolveAnswerFails() throws Exception {
        when(questionHandler.hasPendingQuestion()).thenReturn(true);
        when(questionHandler.resolveAnswer("B")).thenReturn(false);
        when(commandHandler.handle("B")).thenReturn(false);
        when(agentService.chat("B")).thenReturn("agent reply");

        Update update = createUpdate(12345L, "B");
        GetUpdatesResponse response = mock(GetUpdatesResponse.class);
        when(response.isOk()).thenReturn(true);
        when(response.updates()).thenReturn(List.of(update));
        when(bot.execute(any(GetUpdates.class))).thenReturn(response);

        poller.poll();

        verify(questionHandler).resolveAnswer("B");
        verify(agentService).chat("B");
        verify(sender).sendMessage("agent reply");
    }

    @Test
    void pollDoesNotRouteToQuestionHandlerWhenNoPending() throws Exception {
        when(questionHandler.hasPendingQuestion()).thenReturn(false);

        Update update = createUpdate(12345L, "hello");
        GetUpdatesResponse response = mock(GetUpdatesResponse.class);
        when(response.isOk()).thenReturn(true);
        when(response.updates()).thenReturn(List.of(update));
        when(bot.execute(any(GetUpdates.class))).thenReturn(response);

        poller.poll();

        verify(questionHandler, never()).resolveAnswer(any());
    }

    @Test
    void constructorThrowsWhenAllowedChatIdIsBlank() {
        TelegramBot botMock = mock(TelegramBot.class);
        TelegramSender senderMock = mock(TelegramSender.class);
        TelegramQuestionHandler handlerMock = mock(TelegramQuestionHandler.class);
        CommandHandler cmdMock = mock(CommandHandler.class);
        AgentService agentMock = mock(AgentService.class);
        HeraldConfig blankConfig = new HeraldConfig(
                null,
                new HeraldConfig.Telegram("test-token", ""),
                null, null, null, null, null, null, null);
        TelegramPoller blankPoller = new TelegramPoller(botMock, blankConfig, senderMock, handlerMock, cmdMock, agentMock);

        assertThatThrownBy(blankPoller::validateConfig)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed-chat-id");
    }

    @Test
    void pollRoutesSlashCommandToCommandHandler() throws Exception {
        when(commandHandler.handle("/help")).thenReturn(true);

        Update update = createUpdate(12345L, "/help");
        GetUpdatesResponse response = mock(GetUpdatesResponse.class);
        when(response.isOk()).thenReturn(true);
        when(response.updates()).thenReturn(List.of(update));
        when(bot.execute(any(GetUpdates.class))).thenReturn(response);

        poller.poll();

        verify(commandHandler).handle("/help");
        // Command should NOT trigger typing action or reach agent loop
        verify(sender, never()).sendTypingAction();
    }

    @Test
    void pollPassesNonCommandToAgentLoop() throws Exception {
        when(commandHandler.handle("hello")).thenReturn(false);
        when(agentService.chat("hello")).thenReturn("Hi there!");

        Update update = createUpdate(12345L, "hello");
        GetUpdatesResponse response = mock(GetUpdatesResponse.class);
        when(response.isOk()).thenReturn(true);
        when(response.updates()).thenReturn(List.of(update));
        when(bot.execute(any(GetUpdates.class))).thenReturn(response);

        poller.poll();

        verify(commandHandler).handle("hello");
        verify(sender).sendTypingAction();
        verify(agentService).chat("hello");
        verify(sender).sendMessage("Hi there!");
    }

    @Test
    void pollSendsErrorMessageWhenAgentThrows() throws Exception {
        when(commandHandler.handle("hello")).thenReturn(false);
        when(agentService.chat("hello")).thenThrow(new RuntimeException("model error"));

        Update update = createUpdate(12345L, "hello");
        GetUpdatesResponse response = mock(GetUpdatesResponse.class);
        when(response.isOk()).thenReturn(true);
        when(response.updates()).thenReturn(List.of(update));
        when(bot.execute(any(GetUpdates.class))).thenReturn(response);

        poller.poll();

        verify(sender).sendMessage("Sorry, something went wrong processing your message. Please try again.");
    }

    @Test
    void pollDoesNotSendBlankAgentResponse() throws Exception {
        when(commandHandler.handle("hello")).thenReturn(false);
        when(agentService.chat("hello")).thenReturn("");

        Update update = createUpdate(12345L, "hello");
        GetUpdatesResponse response = mock(GetUpdatesResponse.class);
        when(response.isOk()).thenReturn(true);
        when(response.updates()).thenReturn(List.of(update));
        when(bot.execute(any(GetUpdates.class))).thenReturn(response);

        poller.poll();

        verify(agentService).chat("hello");
        verify(sender, never()).sendMessage(any());
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
