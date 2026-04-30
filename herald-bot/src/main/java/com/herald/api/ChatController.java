package com.herald.api;

import com.herald.agent.AgentService;
import com.herald.agent.MediaAttachment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import reactor.core.publisher.Flux;

/**
 * REST endpoint for the web-based chat interface.
 * Provides the same agent loop as Telegram but via HTTP.
 */
@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = {"http://localhost:8080", "http://127.0.0.1:8080"})
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final String WEB_CONVERSATION_ID = "web-console";
    private static final long STREAM_TIMEOUT_MS = 5 * 60 * 1000L;
    /** Per-file size cap. Mirrors Telegram's photo/document limit. */
    private static final long MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024;
    /** Where uploaded documents land — same directory Telegram uses, so skills work uniformly. */
    private static final Path UPLOADS_DIR = Path.of(System.getProperty("user.home"), ".herald", "uploads");

    private final AgentService agentService;
    private final ChatNotificationsHub notificationsHub;
    /** Virtual-thread executor for background document-processing turns. */
    private final ExecutorService backgroundExecutor =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("chat-async-", 0).factory());

    public ChatController(AgentService agentService, ChatNotificationsHub notificationsHub) {
        this.agentService = agentService;
        this.notificationsHub = notificationsHub;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String message = request.message();
        if (message == null || message.isBlank()) {
            return new ChatResponse("", "error: empty message");
        }

        String conversationId = resolveConversationId(request.conversationId());

        try {
            String reply = agentService.chat(message, conversationId);
            return new ChatResponse(reply, null);
        } catch (Exception e) {
            return new ChatResponse(null, "Agent error: " + e.getMessage());
        }
    }

    /**
     * Stream the agent response as Server-Sent Events. Emits {@code chunk} events with
     * incremental text as the model generates, a final {@code done} event when the turn
     * completes, and {@code error} events on failure.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String message,
                             @RequestParam(name = "conversationId", required = false) String conversationId) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);

        if (message == null || message.isBlank()) {
            sendErrorAndComplete(emitter, "empty message");
            return emitter;
        }

        String resolvedConversationId = resolveConversationId(conversationId);
        Flux<String> stream = agentService.streamChat(message, resolvedConversationId);
        wireStreamToEmitter(stream, emitter);
        return emitter;
    }

    /**
     * Multipart streaming endpoint accepting text + file attachments.
     * Mirrors the GET /stream contract for response events (chunk/done/error).
     *
     * <p>Routing rules per upload (matches the Telegram pattern):</p>
     * <ul>
     *   <li>{@code image/*} and {@code audio/*} → kept as inline
     *       {@link MediaAttachment} so vision / audio-capable models see the
     *       bytes natively in one round-trip.</li>
     *   <li>Everything else (PDF, DOCX, XLSX, HTML, …) → saved to
     *       {@code ~/.herald/uploads/} and referenced in the user message text
     *       as {@code [File received: NAME (mime, N bytes) — saved to PATH]}.
     *       The agent then runs the {@code markitdown} skill against the path
     *       to extract structured Markdown — far better than blasting raw
     *       PDF/Office bytes into the model context (which also blew past
     *       Anthropic's 100-page PDF limit on large documents).</li>
     * </ul>
     */
    @PostMapping(value = "/stream-multipart",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMultipart(
            @RequestPart(name = "message", required = false) String message,
            @RequestPart(name = "conversationId", required = false) String conversationId,
            @RequestPart(name = "files", required = false) List<MultipartFile> files) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);

        String userText = message != null ? message : "";
        List<MediaAttachment> inlineAttachments = new ArrayList<>();
        List<String> savedFileLabels = new ArrayList<>();
        StringBuilder savedFileTags = new StringBuilder();

        try {
            partitionUploads(files, inlineAttachments, savedFileLabels, savedFileTags);
        } catch (IllegalArgumentException | IOException e) {
            log.warn("Failed to handle multipart upload: {}", e.getMessage());
            sendErrorAndComplete(emitter, e.getMessage());
            return emitter;
        }

        boolean hasInline = !inlineAttachments.isEmpty();
        boolean hasSaved = !savedFileLabels.isEmpty();
        if (userText.isBlank() && !hasInline && !hasSaved) {
            sendErrorAndComplete(emitter, "empty message and no attachments");
            return emitter;
        }

        // Compose the final user message: their text plus any saved-file references
        // appended as a separate paragraph so the agent reasons about them explicitly.
        StringBuilder composed = new StringBuilder(userText);
        if (hasSaved) {
            if (!composed.isEmpty()) composed.append("\n\n");
            composed.append(savedFileTags);
        }
        if (composed.toString().isBlank() && hasInline) {
            // Inline-only turn: give the model a label so the conversation memory
            // has something readable.
            composed.append(describeAttachments(inlineAttachments));
        }
        String text = composed.toString();
        String resolvedConversationId = resolveConversationId(conversationId);

        log.info("Web chat multipart turn (inline={}, saved={}): {}",
                inlineAttachments.size(), savedFileLabels.size(),
                text.substring(0, Math.min(text.length(), 120)));

        // Saved-file uploads (PDF/DOCX/etc) trigger long-running tool chains
        // (markitdown → wiki-ingest → summarize). Don't block the SSE stream
        // for minutes — ack now, run async, push the result via the
        // notifications channel when ready.
        if (hasSaved) {
            String ack = buildBackgroundAck(savedFileLabels);
            try {
                emitter.send(SseEmitter.event().name("chunk").data(ack));
                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();
            } catch (IOException ignored) {
                // Client disconnected before ack — work still kicks off.
            }
            final String finalText = text;
            backgroundExecutor.submit(() -> runBackgroundTurn(finalText, resolvedConversationId));
            return emitter;
        }

        // Pure-text and inline-attachment turns stay synchronous.
        Flux<String> stream = agentService.streamChat(text, inlineAttachments, resolvedConversationId);
        wireStreamToEmitter(stream, emitter);
        return emitter;
    }

    /**
     * Subscribe to async chat notifications for a conversation. The hub
     * forwards new assistant messages — typically the result of a background
     * document-processing turn — as SSE {@code message} events.
     */
    @GetMapping(value = "/notifications", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter notifications(
            @RequestParam(name = "conversationId", required = false) String conversationId) {
        String id = resolveConversationId(conversationId);
        // Long-lived; tomcat will recycle when client disconnects. 30 min keeps
        // typical chat sessions alive without leaking idle channels forever.
        return notificationsHub.register(id, 30L * 60 * 1000);
    }

    private static String buildBackgroundAck(List<String> filenames) {
        if (filenames.size() == 1) {
            return "📄 Got **" + filenames.get(0) + "**. Processing in the background — I'll send the summary as soon as it's done.";
        }
        StringBuilder sb = new StringBuilder("📄 Got ");
        for (int i = 0; i < filenames.size(); i++) {
            if (i > 0) sb.append(i == filenames.size() - 1 ? " and " : ", ");
            sb.append("**").append(filenames.get(i)).append("**");
        }
        sb.append(". Processing in the background — I'll send the summary as soon as it's done.");
        return sb.toString();
    }

    private void runBackgroundTurn(String message, String conversationId) {
        try {
            log.info("Background turn started (conversation={})", conversationId);
            String reply = agentService.chat(message, conversationId);
            log.info("Background turn completed (conversation={}, length={})",
                    conversationId, reply == null ? 0 : reply.length());
            if (reply != null && !reply.isBlank()) {
                notificationsHub.publish(conversationId, "message", reply);
            } else {
                notificationsHub.publish(conversationId, "message",
                        "_(background turn finished with no reply)_");
            }
        } catch (Exception e) {
            log.warn("Background turn failed (conversation={}): {}",
                    conversationId, e.getMessage(), e);
            notificationsHub.publish(conversationId, "error",
                    "Background processing failed: " + e.getMessage());
        }
    }

    /**
     * Split uploads into two buckets based on MIME type:
     * <ul>
     *   <li>Images and audio go into {@code inline} as {@link MediaAttachment}
     *       (vision / audio model consumes the bytes directly).</li>
     *   <li>Everything else is written to {@link #UPLOADS_DIR} and a
     *       {@code [File received: …]} tag is appended to {@code savedTags}
     *       (and the filename added to {@code savedLabels}).</li>
     * </ul>
     */
    private static void partitionUploads(
            List<MultipartFile> files,
            List<MediaAttachment> inline,
            List<String> savedLabels,
            StringBuilder savedTags) throws IOException {
        if (files == null || files.isEmpty()) return;
        Files.createDirectories(UPLOADS_DIR);

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) continue;
            if (file.getSize() > MAX_ATTACHMENT_BYTES) {
                throw new IllegalArgumentException("Attachment '" + file.getOriginalFilename()
                        + "' exceeds " + MAX_ATTACHMENT_BYTES + " bytes");
            }
            String mime = file.getContentType();
            if (mime == null || mime.isBlank()) mime = "application/octet-stream";
            String label = file.getOriginalFilename() != null
                    ? file.getOriginalFilename() : "attachment";

            if (mime.startsWith("image/") || mime.startsWith("audio/")) {
                inline.add(new MediaAttachment(mime, file.getBytes(), label));
                continue;
            }

            // Save to ~/.herald/uploads/<timestamp>_<safeName>
            String safeName = label.replaceAll("[^A-Za-z0-9._-]+", "_");
            Path target = UPLOADS_DIR.resolve(System.currentTimeMillis() + "_" + safeName);
            try (var in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            savedLabels.add(label);
            if (savedTags.length() > 0) savedTags.append('\n');
            savedTags.append(String.format("[File received: %s (%s, %d bytes) — saved to %s]",
                    label, mime, file.getSize(), target));
            log.info("Saved web upload: {} → {}", label, target);
        }
    }

    private static String describeAttachments(List<MediaAttachment> attachments) {
        if (attachments.size() == 1) {
            return "[Attachment: " + attachments.get(0).label() + "]";
        }
        StringBuilder sb = new StringBuilder("[Attachments: ");
        for (int i = 0; i < attachments.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(attachments.get(i).label());
        }
        sb.append("]");
        return sb.toString();
    }

    private void wireStreamToEmitter(Flux<String> stream, SseEmitter emitter) {
        stream.subscribe(
                chunk -> {
                    try {
                        emitter.send(SseEmitter.event().name("chunk").data(chunk));
                    } catch (IOException e) {
                        log.debug("SSE client disconnected: {}", e.getMessage());
                        emitter.completeWithError(e);
                    }
                },
                err -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name("error")
                                .data(err.getMessage() != null ? err.getMessage() : "stream error"));
                    } catch (IOException ignored) {
                        // Client disconnected
                    }
                    emitter.completeWithError(err);
                },
                () -> {
                    try {
                        emitter.send(SseEmitter.event().name("done").data(""));
                    } catch (IOException ignored) {
                        // Client disconnected
                    }
                    emitter.complete();
                });
    }

    private static void sendErrorAndComplete(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error").data(message));
            emitter.complete();
        } catch (IOException ignored) {
            // Client disconnected
        }
    }

    private String resolveConversationId(String requested) {
        return requested != null && !requested.isBlank() ? requested : WEB_CONVERSATION_ID;
    }

    public record ChatRequest(String message, String conversationId) {}
    public record ChatResponse(String reply, String error) {}
}
