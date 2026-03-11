package com.herald.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Advisor that sanitizes conversation history before it reaches the Anthropic API.
 *
 * <p>Handles two classes of problems:</p>
 * <ol>
 *   <li><b>Empty content messages</b> — JDBC chat memory stores TOOL and tool-only
 *       ASSISTANT messages with empty content strings. The Anthropic API rejects these
 *       with "user messages must have non-empty content".</li>
 *   <li><b>Orphaned tool pairs</b> — When {@code MessageWindowChatMemory} trims old
 *       messages, it can split a tool_use/tool_result pair, leaving orphaned references
 *       that the API rejects with "unexpected tool_use_id".</li>
 * </ol>
 *
 * <p>Runs after {@code MessageChatMemoryAdvisor} loads history into the prompt.</p>
 */
class ToolPairSanitizingAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ToolPairSanitizingAdvisor.class);

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        List<Message> messages = request.prompt().getInstructions();
        List<Message> sanitized = sanitizeToolPairs(messages);

        if (sanitized.size() != messages.size()) {
            log.info("Removed {} orphaned tool messages from conversation history",
                    messages.size() - sanitized.size());
            request = request.mutate()
                    .prompt(new Prompt(sanitized, request.prompt().getOptions()))
                    .build();
        }

        return chain.nextCall(request);
    }

    static List<Message> sanitizeToolPairs(List<Message> messages) {
        // Step 1: Drop messages with empty/null content (JDBC serialization artifacts)
        // TOOL and tool-only ASSISTANT messages are stored with empty content strings
        // by Spring AI's JDBC repository, and the Anthropic API rejects them.
        List<Message> nonEmpty = new ArrayList<>(messages.size());
        for (Message msg : messages) {
            if (msg instanceof ToolResponseMessage) {
                // ToolResponseMessages stored via JDBC lose their response data —
                // they come back with empty content. Drop them entirely.
                if (hasContent(msg)) {
                    nonEmpty.add(msg);
                }
            } else if (hasContent(msg) || hasToolCalls(msg)) {
                nonEmpty.add(msg);
            }
        }

        // Step 2: Collect tool_use IDs from AssistantMessages
        Set<String> toolUseIds = new HashSet<>();
        for (Message msg : nonEmpty) {
            if (msg instanceof AssistantMessage assistant && assistant.getToolCalls() != null) {
                for (AssistantMessage.ToolCall tc : assistant.getToolCalls()) {
                    toolUseIds.add(tc.id());
                }
            }
        }

        // Step 3: Collect tool_result IDs from ToolResponseMessages
        Set<String> toolResultIds = new HashSet<>();
        for (Message msg : nonEmpty) {
            if (msg instanceof ToolResponseMessage toolResponse) {
                for (ToolResponseMessage.ToolResponse resp : toolResponse.getResponses()) {
                    toolResultIds.add(resp.id());
                }
            }
        }

        // Step 4: Filter orphaned tool pairs
        List<Message> result = new ArrayList<>(nonEmpty.size());
        for (Message msg : nonEmpty) {
            if (msg instanceof ToolResponseMessage toolResponse) {
                boolean allMatched = toolResponse.getResponses().stream()
                        .allMatch(resp -> toolUseIds.contains(resp.id()));
                if (allMatched) {
                    result.add(msg);
                }
            } else if (msg instanceof AssistantMessage assistant
                    && assistant.getToolCalls() != null
                    && !assistant.getToolCalls().isEmpty()
                    && !hasContent(msg)) {
                // Tool-only assistant message: keep only if its tool calls have matching results
                boolean anyMatched = assistant.getToolCalls().stream()
                        .anyMatch(tc -> toolResultIds.contains(tc.id()));
                if (anyMatched) {
                    result.add(msg);
                }
            } else {
                result.add(msg);
            }
        }

        return result;
    }

    private static boolean hasContent(Message msg) {
        String text = msg.getText();
        return text != null && !text.isBlank();
    }

    private static boolean hasToolCalls(Message msg) {
        return msg instanceof AssistantMessage assistant
                && assistant.getToolCalls() != null
                && !assistant.getToolCalls().isEmpty();
    }

    @Override
    public String getName() {
        return "ToolPairSanitizingAdvisor";
    }

    @Override
    public int getOrder() {
        // Run after MessageChatMemoryAdvisor (which defaults to Ordered.LOWEST_PRECEDENCE - 100)
        // but before ToolCallAdvisor processes the request.
        return Ordered.LOWEST_PRECEDENCE - 50;
    }
}
