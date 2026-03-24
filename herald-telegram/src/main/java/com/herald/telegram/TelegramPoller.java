package com.herald.telegram;

import com.herald.agent.AgentService;
import com.herald.config.HeraldConfig;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.CallbackQuery;
import com.pengrad.telegrambot.model.Document;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.PhotoSize;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.Voice;
import com.pengrad.telegrambot.request.AnswerCallbackQuery;
import com.pengrad.telegrambot.request.GetFile;
import com.pengrad.telegrambot.request.GetUpdates;
import com.pengrad.telegrambot.response.GetFileResponse;
import com.pengrad.telegrambot.response.GetUpdatesResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

@Component
@ConditionalOnProperty("herald.telegram.bot-token")
public class TelegramPoller {

    private static final Logger log = LoggerFactory.getLogger(TelegramPoller.class);

    private final TelegramBot bot;
    private final String allowedChatId;
    private final TelegramSender sender;
    private final TelegramQuestionHandler questionHandler;
    private final CommandHandler commandHandler;
    private final AgentService agentService;
    private int offset = 0;

    public TelegramPoller(TelegramBot bot, HeraldConfig config, TelegramSender sender,
                          TelegramQuestionHandler questionHandler, CommandHandler commandHandler,
                          AgentService agentService) {
        this.bot = bot;
        this.allowedChatId = config.telegram().allowedChatId();
        this.sender = sender;
        this.questionHandler = questionHandler;
        this.commandHandler = commandHandler;
        this.agentService = agentService;
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

    private static final Path UPLOADS_DIR = Path.of(System.getProperty("user.home"), ".herald", "uploads");

    private void processUpdate(Update update) {
        // Handle inline keyboard button presses
        CallbackQuery callbackQuery = update.callbackQuery();
        if (callbackQuery != null) {
            handleCallbackQuery(callbackQuery);
            return;
        }

        Message message = update.message();
        if (message == null) {
            return;
        }

        String chatId = String.valueOf(message.chat().id());
        if (!chatId.equals(allowedChatId)) {
            log.debug("Dropping message from unauthorized chat: {}", chatId);
            return;
        }

        // Build the text to send to the agent — may include file context
        String text = buildMessageText(message);
        if (text == null || text.isBlank()) {
            return;
        }

        log.info("Received message from authorized chat: {}", text.substring(0, Math.min(text.length(), 80)));

        // Handle slash commands before anything else (only for pure text messages)
        if (message.text() != null && commandHandler.handle(message.text())) {
            return;
        }

        sender.sendTypingAction();

        // If there's a pending question, route this reply as the answer
        if (questionHandler.hasPendingQuestion()) {
            String answerText = message.text() != null ? message.text() : text;
            if (questionHandler.resolveAnswer(answerText)) {
                log.info("User reply resolved pending question");
                return;
            }
        }

        // Pass message to agent loop
        try {
            String response = agentService.chat(text);
            if (response != null && !response.isBlank()) {
                sender.sendMessage(response);
            }
        } catch (Exception e) {
            log.error("Agent loop error: {}", e.getMessage(), e);
            sender.sendMessage("Sorry, something went wrong processing your message. Please try again.");
        }
    }

    /**
     * Builds the text message to send to the agent. For plain text messages, returns
     * the text directly. For file/photo/voice messages, downloads the file and returns
     * a description with the local file path so the agent can access it.
     */
    private String buildMessageText(Message message) {
        String caption = message.caption();

        // Plain text message
        if (message.text() != null) {
            return message.text();
        }

        // Document (PDF, spreadsheet, text file, etc.)
        Document document = message.document();
        if (document != null) {
            Path localPath = downloadTelegramFile(document.fileId(), document.fileName());
            if (localPath != null) {
                String desc = String.format("[File received: %s (%s, %d bytes) — saved to %s]",
                        document.fileName(), document.mimeType(), document.fileSize(), localPath);
                return caption != null ? caption + "\n\n" + desc : desc;
            }
            return caption != null ? caption + "\n\n[File upload failed]" : null;
        }

        // Photo (sent as image, not as file)
        PhotoSize[] photos = message.photo();
        if (photos != null && photos.length > 0) {
            // Use the largest photo (last in array)
            PhotoSize largest = photos[photos.length - 1];
            Path localPath = downloadTelegramFile(largest.fileId(), "photo_" + largest.fileId() + ".jpg");
            if (localPath != null) {
                String desc = String.format("[Photo received (%dx%d) — saved to %s]",
                        largest.width(), largest.height(), localPath);
                return caption != null ? caption + "\n\n" + desc : desc;
            }
            return caption != null ? caption + "\n\n[Photo upload failed]" : null;
        }

        // Voice message
        Voice voice = message.voice();
        if (voice != null) {
            Path localPath = downloadTelegramFile(voice.fileId(), "voice_" + voice.fileId() + ".ogg");
            if (localPath != null) {
                String desc = String.format("[Voice message received (%d seconds) — saved to %s]",
                        voice.duration(), localPath);
                return caption != null ? caption + "\n\n" + desc : desc;
            }
            return caption != null ? caption + "\n\n[Voice upload failed]" : null;
        }

        // Unsupported message type with caption
        if (caption != null) {
            return caption;
        }

        return null;
    }

    /**
     * Downloads a file from Telegram servers to ~/.herald/uploads/.
     * Returns the local path, or null on failure.
     */
    private Path downloadTelegramFile(String fileId, String fileName) {
        try {
            GetFileResponse fileResponse = bot.execute(new GetFile(fileId));
            if (!fileResponse.isOk() || fileResponse.file() == null) {
                log.warn("Failed to get file info from Telegram: {}", fileResponse.description());
                return null;
            }

            String downloadUrl = bot.getFullFilePath(fileResponse.file());

            Files.createDirectories(UPLOADS_DIR);

            // Sanitize filename
            String safeName = fileName != null ? fileName.replaceAll("[^a-zA-Z0-9._-]", "_") : fileId;
            // Prepend timestamp to avoid collisions
            String timestamped = System.currentTimeMillis() + "_" + safeName;
            Path target = UPLOADS_DIR.resolve(timestamped);

            try (InputStream in = URI.create(downloadUrl).toURL().openStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }

            log.info("Downloaded Telegram file: {} → {}", fileName, target);
            return target;
        } catch (Exception e) {
            log.error("Failed to download Telegram file {}: {}", fileName, e.getMessage());
            return null;
        }
    }

    private void handleCallbackQuery(CallbackQuery callbackQuery) {
        // Verify the callback came from the allowed chat before acknowledging
        if (callbackQuery.message() != null) {
            String chatId = String.valueOf(callbackQuery.message().chat().id());
            if (!chatId.equals(allowedChatId)) {
                log.debug("Dropping callback from unauthorized chat: {}", chatId);
                return;
            }
        }

        // Acknowledge the callback to dismiss the loading spinner
        try {
            bot.execute(new AnswerCallbackQuery(callbackQuery.id()));
        } catch (Exception e) {
            log.warn("Failed to answer callback query: {}", e.getMessage());
        }

        String data = callbackQuery.data();
        if (data != null && questionHandler.hasPendingQuestion()) {
            if (questionHandler.resolveAnswer(data)) {
                log.info("Callback query resolved pending question with: {}", data);
            }
        }
    }
}
