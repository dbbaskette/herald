package com.herald.api;

import com.herald.agent.AgentService;
import com.herald.agent.MediaAttachment;
import com.herald.agent.SavedUpload;
import com.herald.agent.ToolEventBus;
import com.herald.config.HeraldLimits;

import java.io.IOException;
import java.nio.file.Path;
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

    private final AgentService agentService;
    private final ChatNotificationsHub notificationsHub;
    private final ToolEventBus toolEventBus;
    /** Virtual-thread executor for background document-processing turns. */
    private final ExecutorService backgroundExecutor =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("chat-async-", 0).factory());

    public ChatController(AgentService agentService,
                          ChatNotificationsHub notificationsHub,
                          ToolEventBus toolEventBus) {
        this.agentService = agentService;
        this.notificationsHub = notificationsHub;
        this.toolEventBus = toolEventBus;
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
        SseEmitter emitter = new SseEmitter(HeraldLimits.streamTimeoutMs());

        if (message == null || message.isBlank()) {
            sendErrorAndComplete(emitter, "empty message");
            return emitter;
        }

        String resolvedConversationId = resolveConversationId(conversationId);
        Flux<String> stream = agentService.streamChat(message, resolvedConversationId);
        wireStreamToEmitter(stream, emitter, resolvedConversationId);
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
        SseEmitter emitter = new SseEmitter(HeraldLimits.streamTimeoutMs());

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
            SseSender.send(emitter, "chunk", ack);
            SseSender.sendAndComplete(emitter, "done", "");
            // If the user didn't provide explicit instructions alongside the
            // upload, auto-append the ingest pipeline so the agent converts
            // with markitdown and feeds the result into wiki-ingest without
            // being asked. The user can still override by providing their own
            // text (e.g. "just summarize this, don't save to memory").
            String finalText = text;
            if (userText.isBlank()) {
                finalText = text + "\n\nAuto-ingest instructions: "
                        + "1) Convert each file with the markitdown skill (markitdown <path> -o <out.md>). "
                        + "2) Do NOT read the full converted file into context — it may be very large. "
                        + "Instead, read only the first ~100 lines (head -100) to extract title and key metadata. "
                        + "3) Ingest the file into memory using wiki-ingest — pass the output .md path as the source. "
                        + "The wiki-ingest skill will handle reading and chunking the content. "
                        + "4) Reply with a brief summary of what was ingested (title, page count, key topics).";
            }
            final String bgText = finalText;
            backgroundExecutor.submit(() -> runBackgroundTurn(bgText, resolvedConversationId));
            return emitter;
        }

        // Pure-text and inline-attachment turns stay synchronous.
        Flux<String> stream = agentService.streamChat(text, inlineAttachments, resolvedConversationId);
        wireStreamToEmitter(stream, emitter, resolvedConversationId);
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
        return notificationsHub.register(id, HeraldLimits.notificationsTimeoutMs());
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
        // Bridge tool events to the notifications channel so the chat UI can
        // render live cards while the background turn runs (#362).
        Runnable unsubscribe = toolEventBus.subscribe(conversationId, event -> {
            if (event instanceof ToolEventBus.ToolStart start) {
                notificationsHub.publish(conversationId, "tool_start", encodeToolStart(start));
            } else if (event instanceof ToolEventBus.ToolEnd end) {
                notificationsHub.publish(conversationId, "tool_end", encodeToolEnd(end));
            }
        });
        try {
            log.info("Background turn started (conversation={})", conversationId);
            // Push an initial progress event so the web UI can show a live
            // indicator while the agent works through the ingest pipeline.
            notificationsHub.publish(conversationId, "progress",
                    "Processing — agent is working…");
            String reply = agentService.chat(message, conversationId);
            log.info("Background turn completed (conversation={}, length={})",
                    conversationId, reply == null ? 0 : reply.length());
            // Clear the progress indicator before delivering the final message.
            notificationsHub.publish(conversationId, "progress", "");
            if (reply != null && !reply.isBlank()) {
                notificationsHub.publish(conversationId, "message", reply);
            } else {
                notificationsHub.publish(conversationId, "message",
                        "_(background turn finished with no reply)_");
            }
        } catch (Exception e) {
            log.warn("Background turn failed (conversation={}): {}",
                    conversationId, e.getMessage(), e);
            notificationsHub.publish(conversationId, "progress", "");
            notificationsHub.publish(conversationId, "error",
                    "Background processing failed: " + e.getMessage());
        } finally {
            try { unsubscribe.run(); } catch (Exception ignored) {}
        }
    }

    /**
     * Split uploads into two buckets based on MIME type:
     * <ul>
     *   <li>Images and audio go into {@code inline} as {@link MediaAttachment}
     *       (vision / audio model consumes the bytes directly).</li>
     *   <li>Everything else is written to {@link HeraldLimits#UPLOADS_DIR} and a
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

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) continue;
            if (file.getSize() > HeraldLimits.MAX_UPLOAD_BYTES) {
                throw new IllegalArgumentException("Attachment '" + file.getOriginalFilename()
                        + "' exceeds " + HeraldLimits.MAX_UPLOAD_BYTES + " bytes");
            }
            String mime = file.getContentType();
            if (mime == null || mime.isBlank()) mime = "application/octet-stream";
            String label = file.getOriginalFilename() != null
                    ? file.getOriginalFilename() : "attachment";

            if (mime.startsWith("image/") || mime.startsWith("audio/")) {
                inline.add(new MediaAttachment(mime, file.getBytes(), label));
                continue;
            }

            Path target;
            try (var in = file.getInputStream()) {
                target = SavedUpload.save(in, label);
            }
            savedLabels.add(label);
            if (savedTags.length() > 0) savedTags.append('\n');
            savedTags.append(SavedUpload.fileReceivedTag(label, mime, file.getSize(), target));
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

    private void wireStreamToEmitter(Flux<String> stream, SseEmitter emitter, String conversationId) {
        // Subscribe to tool-call events so the UI can render live tool cards (#362).
        // The subscription is auto-unregistered when the emitter completes.
        Runnable unsubscribe = toolEventBus.subscribe(conversationId, event -> {
            if (event instanceof ToolEventBus.ToolStart start) {
                SseSender.send(emitter, "tool_start", encodeToolStart(start));
            } else if (event instanceof ToolEventBus.ToolEnd end) {
                SseSender.send(emitter, "tool_end", encodeToolEnd(end));
            }
        });
        Runnable cleanup = () -> { try { unsubscribe.run(); } catch (Exception ignored) {} };

        stream.subscribe(
                chunk -> SseSender.send(emitter, "chunk", chunk),
                err -> {
                    SseSender.send(emitter, "error",
                            err.getMessage() != null ? err.getMessage() : "stream error");
                    cleanup.run();
                    SseSender.completeWithError(emitter, err);
                },
                () -> {
                    cleanup.run();
                    SseSender.sendAndComplete(emitter, "done", "");
                });
    }

    private static String encodeToolStart(ToolEventBus.ToolStart e) {
        return String.format(
                "{\"id\":\"%s\",\"name\":\"%s\",\"args\":\"%s\"}",
                jsonEscape(e.callId()), jsonEscape(e.toolName()),
                jsonEscape(e.argsPreview()));
    }

    private static String encodeToolEnd(ToolEventBus.ToolEnd e) {
        return String.format(
                "{\"id\":\"%s\",\"name\":\"%s\",\"ok\":%s,\"elapsedMs\":%d,\"summary\":\"%s\"}",
                jsonEscape(e.callId()), jsonEscape(e.toolName()),
                e.ok(), e.elapsedMs(), jsonEscape(e.summary()));
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.toString();
    }

    private static void sendErrorAndComplete(SseEmitter emitter, String message) {
        SseSender.sendAndComplete(emitter, "error", message);
    }

    private String resolveConversationId(String requested) {
        return requested != null && !requested.isBlank() ? requested : HeraldLimits.WEB_CONVERSATION_ID;
    }

    public record ChatRequest(String message, String conversationId) {}
    public record ChatResponse(String reply, String error) {}
}
