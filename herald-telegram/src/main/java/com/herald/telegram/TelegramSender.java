package com.herald.telegram;

import com.herald.agent.AgentService;
import com.herald.agent.MessageSender;
import com.herald.config.HeraldConfig;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.request.ChatAction;
import com.pengrad.telegrambot.model.request.InlineKeyboardButton;
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.request.EditMessageText;
import com.pengrad.telegrambot.request.SendChatAction;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.BaseResponse;
import com.pengrad.telegrambot.response.SendResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
@ConditionalOnProperty("herald.telegram.bot-token")
public class TelegramSender implements MessageSender {

    private static final Logger log = LoggerFactory.getLogger(TelegramSender.class);
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BASE_DELAY_MS = 1000;
    /** Telegram allows ~1 edit/sec per message; keep a small safety margin. */
    static final long EDIT_THROTTLE_MS = 1100;

    private final TelegramBot bot;
    private final String chatId;
    private final MessageFormatter formatter;

    public TelegramSender(TelegramBot bot, HeraldConfig config, MessageFormatter formatter) {
        this.bot = bot;
        this.chatId = config.telegram().allowedChatId();
        this.formatter = formatter;
    }

    @Override
    public void sendMessage(String text) {
        List<String> chunks = formatter.split(text);
        for (String chunk : chunks) {
            sendWithRetry(chunk);
        }
    }

    /**
     * Stream the assistant response into a single Telegram message, editing it as chunks
     * arrive. Blocks until the stream completes. Overflows past the Telegram message
     * length limit are sent as follow-up messages. If the stream completes with no
     * content, nothing is sent.
     *
     * <p>Edits are throttled to {@value #EDIT_THROTTLE_MS} ms between calls to stay under
     * Telegram's per-message rate limit. Rate-limit responses (429) from interim edits
     * are absorbed silently — the final edit uses the full retry logic.</p>
     */
    public void sendStreamingMessage(Flux<String> stream) {
        StringBuilder accumulator = new StringBuilder();
        AtomicReference<Integer> messageIdRef = new AtomicReference<>();
        AtomicLong lastEditMs = new AtomicLong(0);
        AtomicReference<String> lastSentText = new AtomicReference<>("");
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        Disposable subscription = stream.subscribe(
                chunk -> {
                    accumulator.append(chunk);
                    String current = AgentService.stripThinkTags(accumulator.toString());
                    if (current.isEmpty()) {
                        return;
                    }
                    if (messageIdRef.get() == null) {
                        // Create the placeholder message with the first real content so we
                        // don't leave an empty "..." hanging if the stream is empty.
                        String first = truncateForDisplay(current);
                        Integer id = sendInitialStreamMessage(first);
                        if (id != null) {
                            messageIdRef.set(id);
                            lastSentText.set(first);
                            lastEditMs.set(System.currentTimeMillis());
                        }
                        return;
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastEditMs.get() >= EDIT_THROTTLE_MS
                            && !current.equals(lastSentText.get())) {
                        String display = truncateForDisplay(current);
                        if (editPlain(messageIdRef.get(), display)) {
                            lastSentText.set(display);
                            lastEditMs.set(now);
                        }
                    }
                },
                err -> {
                    errorRef.set(err);
                    done.countDown();
                },
                done::countDown);

        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            subscription.dispose();
            return;
        }

        if (errorRef.get() != null) {
            log.error("Stream error: {}", errorRef.get().getMessage());
            String errMsg = "Sorry, something went wrong generating that response.";
            if (messageIdRef.get() != null) {
                editPlain(messageIdRef.get(), errMsg);
            } else {
                sendMessage(errMsg);
            }
            return;
        }

        String full = AgentService.stripThinkTags(accumulator.toString());
        if (full.isBlank()) {
            return;
        }

        List<String> finalChunks = formatter.split(full);
        if (messageIdRef.get() == null) {
            // First-chunk placeholder send failed; fall back to normal send.
            sendMessage(full);
            return;
        }
        editWithRetry(messageIdRef.get(), finalChunks.get(0));
        for (int i = 1; i < finalChunks.size(); i++) {
            sendWithRetry(finalChunks.get(i));
        }
    }

    private String truncateForDisplay(String text) {
        return text.length() <= MessageFormatter.TELEGRAM_MAX_LENGTH
                ? text
                : text.substring(0, MessageFormatter.TELEGRAM_MAX_LENGTH);
    }

    private Integer sendInitialStreamMessage(String text) {
        try {
            SendResponse response = bot.execute(new SendMessage(chatId, text));
            if (response.isOk() && response.message() != null) {
                return response.message().messageId();
            }
            log.warn("Stream placeholder send failed: {}", response.description());
            return null;
        } catch (Exception e) {
            log.warn("Stream placeholder send threw: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Send a message with inline keyboard buttons. Each option becomes a button row.
     * The button's callback data is the option label text.
     */
    public void sendMessageWithKeyboard(String text, List<String> options) {
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        for (String option : options) {
            keyboard.addRow(new InlineKeyboardButton(option).callbackData(option));
        }
        SendMessage request = new SendMessage(chatId, text).replyMarkup(keyboard);
        try {
            SendResponse response = bot.execute(request);
            if (!response.isOk()) {
                log.error("Failed to send keyboard message: {}", response.description());
            }
        } catch (Exception e) {
            log.error("Error sending keyboard message: {}", e.getMessage());
        }
    }

    void sendTypingAction() {
        try {
            bot.execute(new SendChatAction(chatId, ChatAction.typing));
        } catch (Exception e) {
            log.warn("Failed to send typing action: {}", e.getMessage());
        }
    }

    /** Best-effort plain-text edit used for interim streaming updates. */
    private boolean editPlain(int messageId, String text) {
        try {
            BaseResponse response = bot.execute(new EditMessageText(chatId, messageId, text));
            if (response.isOk()) {
                return true;
            }
            // "message is not modified" is fine; rate limits will be retried on next tick
            if (response.errorCode() == 400 && response.description() != null
                    && response.description().contains("message is not modified")) {
                return true;
            }
            log.debug("Interim edit failed (code={}): {}", response.errorCode(), response.description());
            return false;
        } catch (Exception e) {
            log.debug("Interim edit threw: {}", e.getMessage());
            return false;
        }
    }

    private void editWithRetry(int messageId, String text) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                BaseResponse response = bot.execute(
                        new EditMessageText(chatId, messageId, text).parseMode(ParseMode.MarkdownV2));
                if (response.isOk()) {
                    return;
                }
                if (response.errorCode() == 429) {
                    handleEditRateLimit(response, attempt);
                    continue;
                }
                if (response.errorCode() == 400 && response.description() != null
                        && response.description().contains("message is not modified")) {
                    return;
                }
                // MarkdownV2 parse failed — try with escaped text
                String escaped = formatter.escapeMarkdownV2(text);
                BaseResponse escapedResponse = bot.execute(
                        new EditMessageText(chatId, messageId, escaped).parseMode(ParseMode.MarkdownV2));
                if (escapedResponse.isOk()) {
                    return;
                }
                // Fall back to plain text
                BaseResponse plain = bot.execute(new EditMessageText(chatId, messageId, text));
                if (plain.isOk()) {
                    return;
                }
                log.error("Final edit failed: {}", plain.description());
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                long delay = RETRY_BASE_DELAY_MS * (1L << attempt);
                log.warn("Edit attempt {} failed: {}, retrying in {} ms",
                        attempt + 1, e.getMessage(), delay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.error("Failed to edit message after {} retries", MAX_RETRIES);
    }

    private void handleEditRateLimit(BaseResponse response, int attempt) throws InterruptedException {
        Integer retryAfter = response.parameters() != null
                ? response.parameters().retryAfter() : null;
        long delay = retryAfter != null
                ? retryAfter * 1000L
                : RETRY_BASE_DELAY_MS * (1L << attempt);
        log.warn("Rate limited on edit, retrying after {} ms", delay);
        Thread.sleep(delay);
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
