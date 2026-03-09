package com.herald.telegram;

import com.herald.config.HeraldConfig;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.request.GetUpdates;
import com.pengrad.telegrambot.response.GetUpdatesResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;

@Component
@ConditionalOnProperty("herald.telegram.bot-token")
class TelegramPoller {

    private static final Logger log = LoggerFactory.getLogger(TelegramPoller.class);

    private final TelegramBot bot;
    private final String allowedChatId;
    private final TelegramSender sender;
    private final TelegramQuestionHandler questionHandler;
    private final CommandHandler commandHandler;
    private int offset = 0;

    TelegramPoller(TelegramBot bot, HeraldConfig config, TelegramSender sender,
                   TelegramQuestionHandler questionHandler, CommandHandler commandHandler) {
        this.bot = bot;
        this.allowedChatId = config.telegram().allowedChatId();
        this.sender = sender;
        this.questionHandler = questionHandler;
        this.commandHandler = commandHandler;
    }

    @PostConstruct
    void validateConfig() {
        if (allowedChatId == null || allowedChatId.isBlank()) {
            throw new IllegalStateException(
                    "herald.telegram.allowed-chat-id must be configured when Telegram bot is enabled");
        }
        log.info("Telegram poller initialized for chat ID: {}", allowedChatId);
    }

    @Scheduled(fixedDelay = 1000)
    void poll() {
        try {
            GetUpdatesResponse response = bot.execute(
                    new GetUpdates().offset(offset).limit(100).timeout(0));

            if (!response.isOk()) {
                log.warn("GetUpdates failed: {}", response.description());
                return;
            }

            List<Update> updates = response.updates();
            if (updates == null || updates.isEmpty()) {
                return;
            }

            for (Update update : updates) {
                offset = update.updateId() + 1;
                processUpdate(update);
            }
        } catch (Exception e) {
            log.error("Error polling Telegram updates: {}", e.getMessage(), e);
        }
    }

    private void processUpdate(Update update) {
        Message message = update.message();
        if (message == null || message.text() == null) {
            return;
        }

        String chatId = String.valueOf(message.chat().id());
        if (!chatId.equals(allowedChatId)) {
            log.debug("Dropping message from unauthorized chat: {}", chatId);
            return;
        }

        String text = message.text();
        log.info("Received message from authorized chat: {}", text.substring(0, Math.min(text.length(), 50)));

        // Handle slash commands before anything else
        if (commandHandler.handle(text)) {
            return;
        }

        sender.sendTypingAction();

        // If there's a pending question, route this reply as the answer
        if (questionHandler.hasPendingQuestion()) {
            if (questionHandler.resolveAnswer(text)) {
                log.info("User reply resolved pending question");
                return;
            }
        }

        // TODO: pass message to agent loop (wired in a later issue)
        log.debug("Agent loop not yet wired — message received: {}", text);
    }
}
