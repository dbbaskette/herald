package com.herald.api;

import com.herald.agent.AgentService;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
            try {
                emitter.send(SseEmitter.event().name("error").data("empty message"));
                emitter.complete();
            } catch (IOException ignored) {
                // Client disconnected
            }
            return emitter;
        }

        String resolvedConversationId = resolveConversationId(conversationId);

        agentService.streamChat(message, resolvedConversationId)
                .subscribe(
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

        return emitter;
    }

    private String resolveConversationId(String requested) {
        return requested != null && !requested.isBlank() ? requested : WEB_CONVERSATION_ID;
    }

    public record ChatRequest(String message, String conversationId) {}
    public record ChatResponse(String reply, String error) {}
}
