package com.herald.telegram;

import com.herald.config.HeraldConfig;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.request.ChatAction;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.request.SendChatAction;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.SendResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty("herald.telegram.bot-token")
class TelegramSender {

    private static final Logger log = LoggerFactory.getLogger(TelegramSender.class);
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BASE_DELAY_MS = 1000;

    private final TelegramBot bot;
    private final String chatId;
    private final MessageFormatter formatter;

    TelegramSender(TelegramBot bot, HeraldConfig config, MessageFormatter formatter) {
        this.bot = bot;
        this.chatId = config.telegram().allowedChatId();
        this.formatter = formatter;
    }

    void sendMessage(String text) {
        List<String> chunks = formatter.split(text);
        for (String chunk : chunks) {
            String escaped = formatter.escapeMarkdownV2(chunk);
            sendWithRetry(escaped);
        }
    }

    void sendTypingAction() {
        try {
            bot.execute(new SendChatAction(chatId, ChatAction.typing));
        } catch (Exception e) {
            log.warn("Failed to send typing action: {}", e.getMessage());
        }
    }

    private void sendWithRetry(String text) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                SendResponse response = bot.execute(
                        new SendMessage(chatId, text).parseMode(ParseMode.MarkdownV2));
                if (response.isOk()) {
                    return;
                }
                if (response.errorCode() == 429) {
                    Integer retryAfter = response.parameters() != null
                            ? response.parameters().retryAfter() : null;
                    long delay = retryAfter != null
                            ? retryAfter * 1000L
                            : RETRY_BASE_DELAY_MS * (1L << attempt);
                    log.warn("Rate limited by Telegram, retrying after {} ms", delay);
                    Thread.sleep(delay);
                    continue;
                }
                // If MarkdownV2 parse fails, fall back to plain text
                log.warn("Telegram send failed ({}): {}, retrying as plain text",
                        response.errorCode(), response.description());
                SendResponse fallback = bot.execute(new SendMessage(chatId, text));
                if (fallback.isOk()) {
                    return;
                }
                log.error("Plain text send also failed: {}", fallback.description());
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Send interrupted");
                return;
            } catch (Exception e) {
                long delay = RETRY_BASE_DELAY_MS * (1L << attempt);
                log.warn("Send attempt {} failed: {}, retrying in {} ms",
                        attempt + 1, e.getMessage(), delay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.error("Failed to send message after {} retries", MAX_RETRIES);
    }
}
