package com.herald.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper around the main ChatClient that provides a simple call interface
 * with error handling for the Telegram transport layer.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final String DEFAULT_CONVERSATION_ID = "default";

    private final ChatClient mainClient;

    AgentService(ChatClient mainClient) {
        this.mainClient = mainClient;
    }

    /**
     * Send a user message through the agent loop and return the final response.
     * The ChatClient handles tool calls, memory, and conversation history via advisors.
     */
    public String chat(String userMessage) {
        return chat(userMessage, DEFAULT_CONVERSATION_ID);
    }

    /**
     * Send a user message with a specific conversation ID.
     */
    public String chat(String userMessage, String conversationId) {
        log.info("Agent processing message (conversation={}): {}",
                conversationId, userMessage.substring(0, Math.min(userMessage.length(), 50)));

        String content = mainClient.prompt()
                .user(userMessage)
                .advisors(a -> a.param("chat_memory_conversation_id", conversationId))
                .call()
                .content();

        log.info("Agent response generated (conversation={}), length={}",
                conversationId, content != null ? content.length() : 0);

        return content != null ? content : "";
    }
}
