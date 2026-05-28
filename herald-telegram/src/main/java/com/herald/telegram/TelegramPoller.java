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
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
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
    private final SlashCommandDispatcher commandHandler;
    private final AgentService agentService;
    private final java.util.Optional<com.herald.agent.BudgetPolicy> budgetPolicy;
    private int offset = 0;

    /**
     * Runs agent turns off the poller thread so the @Scheduled poll() can keep
     * fetching updates while a turn is in flight. Single-threaded so concurrent
     * messages serialize (the chat memory is one-conversation), and so an
     * AskUserQuestion that blocks on user input never blocks polling — fixes
     * the "bot looks offline mid-question" symptom.
     */
    private final ExecutorService agentExecutor;

    /**
     * Shared OkHttp client backing the {@link TelegramBot}. When we see a
     * sustained burst of poll failures (TLS handshake errors, NoRouteToHost,
     * Connection reset, …) it usually means a half-open socket got cached
     * during a network blip. Evicting the connection pool forces the next
     * poll to open a brand-new socket. May be {@code null} in tests.
     */
    private final OkHttpClient telegramOkHttpClient;

    private static final int EVICT_AFTER_CONSECUTIVE_FAILURES = 3;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    @Autowired
    public TelegramPoller(TelegramBot bot, HeraldConfig config, TelegramSender sender,
                          TelegramQuestionHandler questionHandler,
                          SlashCommandDispatcher commandHandler,
                          AgentService agentService,
                          java.util.Optional<com.herald.agent.BudgetPolicy> budgetPolicy,
                          OkHttpClient telegramOkHttpClient) {
        this(bot, config, sender, questionHandler, commandHandler, agentService, budgetPolicy,
                Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "herald-telegram-agent");
                    t.setDaemon(true);
                    return t;
                }),
                telegramOkHttpClient);
    }

    /** Test-friendly constructor — lets tests pass a same-thread executor and skip eviction. */
    TelegramPoller(TelegramBot bot, HeraldConfig config, TelegramSender sender,
                   TelegramQuestionHandler questionHandler,
                   SlashCommandDispatcher commandHandler,
                   AgentService agentService,
                   java.util.Optional<com.herald.agent.BudgetPolicy> budgetPolicy,
                   ExecutorService agentExecutor) {
        this(bot, config, sender, questionHandler, commandHandler, agentService, budgetPolicy,
                agentExecutor, null);
    }

    /** Test-only overload kept for {@code validateConfig} smoke tests. */
    TelegramPoller(TelegramBot bot, HeraldConfig config, TelegramSender sender,
                   TelegramQuestionHandler questionHandler,
                   SlashCommandDispatcher commandHandler,
                   AgentService agentService,
                   java.util.Optional<com.herald.agent.BudgetPolicy> budgetPolicy) {
        this(bot, config, sender, questionHandler, commandHandler, agentService, budgetPolicy,
                Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "herald-telegram-agent-test");
                    t.setDaemon(true);
                    return t;
                }), null);
    }

    TelegramPoller(TelegramBot bot, HeraldConfig config, TelegramSender sender,
                   TelegramQuestionHandler questionHandler,
                   SlashCommandDispatcher commandHandler,
                   AgentService agentService,
                   java.util.Optional<com.herald.agent.BudgetPolicy> budgetPolicy,
                   ExecutorService agentExecutor,
                   OkHttpClient telegramOkHttpClient) {
        this.bot = bot;
        this.allowedChatId = config.telegram().allowedChatId();
        this.sender = sender;
        this.questionHandler = questionHandler;
        this.commandHandler = commandHandler;
        this.agentService = agentService;
        this.budgetPolicy = budgetPolicy;
        this.agentExecutor = agentExecutor;
        this.telegramOkHttpClient = telegramOkHttpClient;
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
            // Any successful round-trip clears the consecutive-failure counter,
            // even if Telegram returned no updates.
            consecutiveFailures.set(0);

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
            int n = consecutiveFailures.incrementAndGet();
            log.error("Error polling Telegram updates ({} consecutive): {}", n, e.getMessage(), e);
            if (n >= EVICT_AFTER_CONSECUTIVE_FAILURES && telegramOkHttpClient != null) {
                log.warn("Evicting Telegram connection pool after {} consecutive failures "
                        + "(forces fresh socket on next poll)", n);
                telegramOkHttpClient.connectionPool().evictAll();
                consecutiveFailures.set(0);
            }
        }
    }

    private static final Path UPLOADS_DIR = com.herald.config.HeraldLimits.UPLOADS_DIR;

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

        // Build the text + any media attachments to send to the agent.
        UserMessagePayload payload = buildMessagePayload(message);
        String text = payload == null ? null : payload.text();
        if (text == null || text.isBlank()) {
            return;
        }

        log.info("Received message from authorized chat ({} attachments): {}",
                payload.attachments().size(), text.substring(0, Math.min(text.length(), 80)));

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

        // Budget gate (#319): block or warn before invoking the agent.
        if (budgetPolicy.isPresent()) {
            var decision = budgetPolicy.get().evaluate();
            if (decision.isBlocked()) {
                sender.sendMessage("✋ " + decision.message());
                return;
            }
            if (decision.verdict() == com.herald.agent.BudgetPolicy.Verdict.WARN) {
                sender.sendMessage("⚠ " + decision.message());
                // warning is fire-once-per-day and non-blocking; fall through
            }
        }

        // Hand the agent turn off to the dedicated executor and return so the
        // next poll() tick can fetch new updates. The executor is single-thread
        // so concurrent messages serialize behind whatever turn is in flight —
        // critical when a turn is parked inside an AskUserQuestion waiting on
        // the user's reply: polling keeps running and the reply still flows in.
        agentExecutor.submit(() -> {
            try {
                sender.sendStreamingMessage(
                        agentService.streamChat(text, payload.attachments(),
                                com.herald.agent.AgentService.DEFAULT_CONVERSATION_ID));
            } catch (Exception e) {
                log.error("Agent loop error: {}", e.getMessage(), e);
                sender.sendMessage("Sorry, something went wrong processing your message. Please try again.");
            }
        });
    }

    @PreDestroy
    void shutdown() {
        agentExecutor.shutdown();
        try {
            if (!agentExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                agentExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            agentExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Payload record returned to the main dispatcher. */
    record UserMessagePayload(String text, List<com.herald.agent.MediaAttachment> attachments) {}

    /** Max file size accepted from Telegram. Unified with web chat in
     *  {@link com.herald.config.HeraldLimits#MAX_UPLOAD_BYTES}. */
    private static final long MAX_FILE_BYTES = com.herald.config.HeraldLimits.MAX_UPLOAD_BYTES;

    /**
     * Builds the agent payload from a Telegram message. Photos become native
     * multimodal attachments (Anthropic / OpenAI / Gemini vision), while
     * voice + PDF + text documents are best-effort inlined or fall back to
     * the legacy "file saved to ... " descriptor the agent can reach via
     * shell tools. See issue #320.
     */
    UserMessagePayload buildMessagePayload(Message message) {
        String caption = message.caption();

        // Plain text message
        if (message.text() != null) {
            return new UserMessagePayload(message.text(), List.of());
        }

        List<com.herald.agent.MediaAttachment> attachments = new java.util.ArrayList<>();
        StringBuilder text = new StringBuilder();
        if (caption != null && !caption.isBlank()) {
            text.append(caption);
        }

        // Photo — native multimodal attachment for vision-capable providers.
        PhotoSize[] photos = message.photo();
        if (photos != null && photos.length > 0) {
            PhotoSize largest = photos[photos.length - 1];
            FetchedFile fetched = fetchBytes(largest.fileId(),
                    "photo_" + largest.fileId() + ".jpg");
            if (fetched != null) {
                attachments.add(new com.herald.agent.MediaAttachment(
                        "image/jpeg", fetched.bytes(),
                        String.format("photo %dx%d", largest.width(), largest.height())));
                if (text.length() == 0) {
                    text.append("[Photo attached]");
                }
                return new UserMessagePayload(text.toString(), attachments);
            }
            return new UserMessagePayload(
                    text.length() == 0 ? "[Photo upload failed]"
                            : text.toString() + "\n\n[Photo upload failed]",
                    List.of());
        }

        // Document — inline text for small text/* and PDF (if extractable),
        // otherwise fall back to the legacy "saved to ... " descriptor.
        Document document = message.document();
        if (document != null) {
            return buildDocumentPayload(document, caption);
        }

        // Voice — transcribe locally if the helper is available; otherwise
        // fall back to saving the file and letting the agent reach for it
        // via shell tools (matches pre-#320 behavior).
        Voice voice = message.voice();
        if (voice != null) {
            Path localPath = downloadTelegramFile(voice.fileId(),
                    "voice_" + voice.fileId() + ".ogg");
            String transcription = tryTranscribeVoice(localPath);
            if (transcription != null && !transcription.isBlank()) {
                if (text.length() > 0) text.append("\n\n");
                text.append("[Voice transcription (").append(voice.duration())
                        .append("s)]: ").append(transcription);
            } else if (localPath != null) {
                if (text.length() > 0) text.append("\n\n");
                text.append(String.format(
                        "[Voice message received (%d seconds) — file saved to %s. "
                                + "Run the `voice-handling` skill to transcribe it "
                                + "locally; it will install whisper if needed.]",
                        voice.duration(), localPath));
            } else {
                if (text.length() > 0) text.append("\n\n");
                text.append("[Voice upload failed]");
            }
            return new UserMessagePayload(text.toString(), List.of());
        }

        if (caption != null && !caption.isBlank()) {
            return new UserMessagePayload(caption, List.of());
        }
        return null;
    }

    private UserMessagePayload buildDocumentPayload(Document document, String caption) {
        StringBuilder text = new StringBuilder();
        if (caption != null && !caption.isBlank()) {
            text.append(caption);
        }
        Path localPath = downloadTelegramFile(document.fileId(), document.fileName());
        if (localPath == null) {
            if (text.length() > 0) text.append("\n\n");
            text.append("[File upload failed]");
            return new UserMessagePayload(text.toString(), List.of());
        }
        String mime = document.mimeType() == null ? "" : document.mimeType();
        String inlined = tryExtractDocumentText(localPath, mime);
        if (inlined != null) {
            // Surface the on-disk path alongside the extracted text so the agent
            // knows it can re-process the file with a richer tool (markitdown,
            // OCR, page-range extraction, etc.) on subsequent turns. Without
            // this the model thinks the chat-only handoff is all it has.
            if (text.length() > 0) text.append("\n\n");
            text.append(String.format("[Document: %s — saved to %s]%n%s",
                    document.fileName(), localPath, inlined));
        } else {
            if (text.length() > 0) text.append("\n\n");
            String hint = "application/pdf".equals(mime)
                    ? " — run the `markitdown` skill to get structure-preserving Markdown "
                            + "(headings, tables, reading order); it'll install "
                            + "the markitdown CLI if needed"
                    : "";
            text.append(com.herald.agent.SavedUpload.fileReceivedTag(
                    document.fileName(), mime, document.fileSize(), localPath, hint));
        }
        return new UserMessagePayload(text.toString(), List.of());
    }

    /**
     * Inline-extract text from common document types. Returns null when the
     * format isn't supported or the extractor isn't available — caller falls
     * back to the saved-path descriptor.
     */
    static String tryExtractDocumentText(Path localPath, String mimeType) {
        if (localPath == null) return null;
        try {
            long size = Files.size(localPath);
            if (size > MAX_FILE_BYTES) {
                return null; // too big to inline safely
            }
            // Plain-text family → read directly.
            if (mimeType.startsWith("text/")
                    || mimeType.equals("application/json")
                    || mimeType.equals("application/xml")) {
                return Files.readString(localPath, java.nio.charset.StandardCharsets.UTF_8);
            }
            // PDF → shell to `pdftotext` if available.
            if (mimeType.equals("application/pdf")
                    && isCommandAvailable("pdftotext")) {
                return runCommandCaptureStdout(
                        new String[]{"pdftotext", localPath.toString(), "-"});
            }
        } catch (Exception e) {
            log.debug("Inline document extraction failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Best-effort voice transcription. Tries a `whisper` command (openai-
     * whisper or whisper.cpp) on PATH; returns null when unavailable so the
     * caller falls back to the legacy file-path descriptor. Users who want
     * voice-mode should install whisper or wait for issue #308.
     */
    static String tryTranscribeVoice(Path audioPath) {
        if (audioPath == null) return null;
        if (!isCommandAvailable("whisper")) return null;
        try {
            // `whisper <file> --model tiny --output_format txt --output_dir <tmp>`
            // writes `<basename>.txt` next to the file. Simplest: capture stdout
            // via `--output_format txt --fp16 False` and filter the output lines.
            String[] cmd = {"whisper", audioPath.toString(),
                    "--model", "tiny", "--output_format", "txt",
                    "--output_dir", audioPath.getParent().toString(),
                    "--language", "en", "--fp16", "False"};
            int exit = runCommandWithTimeout(cmd, 60);
            if (exit != 0) return null;
            String base = audioPath.getFileName().toString();
            int dot = base.lastIndexOf('.');
            Path txt = audioPath.getParent().resolve(
                    (dot >= 0 ? base.substring(0, dot) : base) + ".txt");
            if (Files.exists(txt)) {
                return Files.readString(txt, java.nio.charset.StandardCharsets.UTF_8).trim();
            }
        } catch (Exception e) {
            log.debug("Voice transcription failed: {}", e.getMessage());
        }
        return null;
    }

    private static boolean isCommandAvailable(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "command -v " + cmd);
            Process p = pb.start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String runCommandCaptureStdout(String[] cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process p = pb.start();
        String out;
        try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(
                p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            out = br.lines().collect(java.util.stream.Collectors.joining("\n"));
        }
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return null;
        }
        return p.exitValue() == 0 ? out : null;
    }

    private static int runCommandWithTimeout(String[] cmd, int timeoutSec) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return -1;
        }
        return p.exitValue();
    }

    /** Downloads Telegram file bytes directly (no local path). Used for Media attachments. */
    private FetchedFile fetchBytes(String fileId, String fileName) {
        try {
            GetFileResponse fileResponse = bot.execute(new GetFile(fileId));
            if (!fileResponse.isOk() || fileResponse.file() == null) {
                log.warn("Failed to get file info from Telegram: {}", fileResponse.description());
                return null;
            }
            if (fileResponse.file().fileSize() != null
                    && fileResponse.file().fileSize() > MAX_FILE_BYTES) {
                log.warn("Telegram file exceeds {} bytes cap — rejecting",
                        MAX_FILE_BYTES);
                return null;
            }
            String downloadUrl = bot.getFullFilePath(fileResponse.file());
            try (InputStream in = URI.create(downloadUrl).toURL().openStream()) {
                byte[] bytes = in.readAllBytes();
                return new FetchedFile(bytes, fileName);
            }
        } catch (Exception e) {
            log.error("Failed to fetch Telegram file {}: {}", fileName, e.getMessage());
            return null;
        }
    }

    private record FetchedFile(byte[] bytes, String fileName) {}

    /**
     * Downloads a file from Telegram servers to {@link com.herald.config.HeraldLimits#UPLOADS_DIR}.
     * Returns the local path, or null on failure. Filename sanitization and the
     * timestamped-prefix scheme are shared with the web chat path via
     * {@link com.herald.agent.SavedUpload}.
     */
    private Path downloadTelegramFile(String fileId, String fileName) {
        try {
            GetFileResponse fileResponse = bot.execute(new GetFile(fileId));
            if (!fileResponse.isOk() || fileResponse.file() == null) {
                log.warn("Failed to get file info from Telegram: {}", fileResponse.description());
                return null;
            }

            String downloadUrl = bot.getFullFilePath(fileResponse.file());
            String labeled = fileName != null ? fileName : fileId;

            Path target;
            try (InputStream in = URI.create(downloadUrl).toURL().openStream()) {
                target = com.herald.agent.SavedUpload.save(in, labeled);
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
