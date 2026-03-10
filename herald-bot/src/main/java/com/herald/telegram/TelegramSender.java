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
public class TelegramSender {

    private static final Logger log = LoggerFactory.getLogger(TelegramSender.class);
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BASE_DELAY_MS = 1000;

    private final TelegramBot bot;
    private final String chatId;
    private final MessageFormatter formatter;

    public TelegramSender(TelegramBot bot, HeraldConfig config, MessageFormatter formatter) {
        this.bot = bot;
        this.chatId = config.telegram().allowedChatId();
        this.formatter = formatter;
    }

    public void sendMessage(String text) {
        List<String> chunks = formatter.split(text);
        for (String chunk : chunks) {
            sendWithRetry(chunk);
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
                // Try sending as MarkdownV2 first to preserve intentional formatting
                SendResponse response = bot.execute(
                        new SendMessage(chatId, text).parseMode(ParseMode.MarkdownV2));
                if (response.isOk()) {
                    return;
                }
                if (response.errorCode() == 429) {
                    handleRateLimit(response, attempt);
                    continue;
                }
                // MarkdownV2 parse failed — try with escaped text
                log.debug("MarkdownV2 parse failed, attempting with escaped text: {}",
                        response.description());
                String escaped = formatter.escapeMarkdownV2(text);
                // Re-split if escaping pushed the chunk over the limit
                List<String> escapedChunks = formatter.split(escaped);
                boolean allOk = true;
                for (String escapedChunk : escapedChunks) {
                    SendResponse escapedResponse = bot.execute(
                            new SendMessage(chatId, escapedChunk).parseMode(ParseMode.MarkdownV2));
                    if (!escapedResponse.isOk()) {
                        allOk = false;
                        break;
                    }
                }
                if (allOk) {
                    return;
                }
                // Fall back to plain text as last resort
                log.warn("Escaped MarkdownV2 also failed, sending as plain text");
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

    private void handleRateLimit(SendResponse response, int attempt) throws InterruptedException {
        Integer retryAfter = response.parameters() != null
                ? response.parameters().retryAfter() : null;
        long delay = retryAfter != null
                ? retryAfter * 1000L
                : RETRY_BASE_DELAY_MS * (1L << attempt);
        log.warn("Rate limited by Telegram, retrying after {} ms", delay);
        Thread.sleep(delay);
    }
}
