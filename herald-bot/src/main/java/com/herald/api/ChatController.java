package com.herald.api;

import com.herald.agent.AgentService;
import com.herald.agent.MediaAttachment;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    private final AgentService agentService;

    public ChatController(AgentService agentService) {
        this.agentService = agentService;
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
     * Mirrors the GET /stream contract for response events (chunk/done/error)
     * but allows the user to attach images, PDFs, etc. as MultipartFile parts.
     * Each file becomes a {@link MediaAttachment} on the agent turn — the same
     * pipeline used by Telegram for incoming photos/documents.
     */
    @PostMapping(value = "/stream-multipart",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMultipart(
            @RequestPart(name = "message", required = false) String message,
            @RequestPart(name = "conversationId", required = false) String conversationId,
            @RequestPart(name = "files", required = false) List<MultipartFile> files) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);

        String text = message != null ? message : "";
        List<MediaAttachment> attachments = buildAttachments(files);

        if (text.isBlank() && attachments.isEmpty()) {
            sendErrorAndComplete(emitter, "empty message and no attachments");
            return emitter;
        }

        // Bot conversation memory expects a non-empty user message — fall back to
        // a label describing the attachments so the model has something to refer to.
        if (text.isBlank()) {
            text = describeAttachments(attachments);
        }

        log.info("Web chat multipart turn ({} attachments): {}",
                attachments.size(),
                text.substring(0, Math.min(text.length(), 80)));

        String resolvedConversationId = resolveConversationId(conversationId);
        Flux<String> stream = agentService.streamChat(text, attachments, resolvedConversationId);
        wireStreamToEmitter(stream, emitter);
        return emitter;
    }

    private List<MediaAttachment> buildAttachments(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return Collections.emptyList();
        }
        List<MediaAttachment> attachments = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            if (file.getSize() > MAX_ATTACHMENT_BYTES) {
                throw new IllegalArgumentException("Attachment '" + file.getOriginalFilename()
                        + "' exceeds " + MAX_ATTACHMENT_BYTES + " bytes");
            }
            try {
                String mimeType = file.getContentType();
                if (mimeType == null || mimeType.isBlank()) {
                    mimeType = "application/octet-stream";
                }
                String label = file.getOriginalFilename() != null
                        ? file.getOriginalFilename()
                        : "attachment";
                attachments.add(new MediaAttachment(mimeType, file.getBytes(), label));
            } catch (IOException e) {
                throw new RuntimeException("Failed to read upload '"
                        + file.getOriginalFilename() + "': " + e.getMessage(), e);
            }
        }
        return attachments;
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
