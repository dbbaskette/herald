package com.herald.api;

import com.herald.agent.AgentService;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for the web-based chat interface.
 * Provides the same agent loop as Telegram but via HTTP.
 */
@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = {"http://localhost:8080", "http://127.0.0.1:8080"})
public class ChatController {

    private static final String WEB_CONVERSATION_ID = "web-console";

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

        String conversationId = request.conversationId() != null && !request.conversationId().isBlank()
                ? request.conversationId()
                : WEB_CONVERSATION_ID;

        try {
            String reply = agentService.chat(message, conversationId);
            return new ChatResponse(reply, null);
        } catch (Exception e) {
            return new ChatResponse(null, "Agent error: " + e.getMessage());
        }
    }

    public record ChatRequest(String message, String conversationId) {}
    public record ChatResponse(String reply, String error) {}
}
