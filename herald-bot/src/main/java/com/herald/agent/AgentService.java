package com.herald.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
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
    private final AgentMetrics agentMetrics;

    AgentService(ChatClient mainClient, AgentMetrics agentMetrics) {
        this.mainClient = mainClient;
        this.agentMetrics = agentMetrics;
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

        long startTime = System.nanoTime();

        String model = "unknown";
        long tokensIn = 0;
        long tokensOut = 0;
        List<String> toolCalls = Collections.emptyList();
        ChatResponse chatResponse = null;

        try {
            chatResponse = mainClient.prompt()
                    .user(userMessage)
                    .advisors(a -> a.param("chat_memory_conversation_id", conversationId))
                    .call()
                    .chatResponse();

            if (chatResponse != null && chatResponse.getMetadata() != null) {
                Usage usage = chatResponse.getMetadata().getUsage();
                if (usage != null) {
                    tokensIn = usage.getPromptTokens() != null ? usage.getPromptTokens().longValue() : 0;
                    tokensOut = usage.getCompletionTokens() != null ? usage.getCompletionTokens().longValue() : 0;
                }
                if (chatResponse.getMetadata().getModel() != null) {
                    model = chatResponse.getMetadata().getModel();
                }
            }

            // Extract tool call names from all generations
            toolCalls = extractToolCalls(chatResponse);
        } finally {
            long latencyMs = (System.nanoTime() - startTime) / 1_000_000;
            agentMetrics.recordTurn(model, tokensIn, tokensOut, latencyMs, toolCalls);
        }

        String content = chatResponse != null && chatResponse.getResult() != null
                ? chatResponse.getResult().getOutput().getText()
                : null;

        log.info("Agent response generated (conversation={}), length={}",
                conversationId, content != null ? content.length() : 0);

        return content != null ? content : "";
    }

    private List<String> extractToolCalls(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResults() == null) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        for (Generation generation : chatResponse.getResults()) {
            if (generation.getOutput() instanceof AssistantMessage assistant
                    && assistant.getToolCalls() != null) {
                for (AssistantMessage.ToolCall tc : assistant.getToolCalls()) {
                    names.add(tc.name());
                }
            }
        }
        return names;
    }
}
