package com.herald.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Advisor that sanitizes conversation history to remove orphaned tool message pairs.
 *
 * <p>When {@code MessageWindowChatMemory} trims old messages, it can split a
 * tool_use/tool_result pair — leaving a {@link ToolResponseMessage} without its
 * matching {@link AssistantMessage} tool call. The Anthropic API strictly requires
 * these to be paired, returning HTTP 400 if they are not.</p>
 *
 * <p>This advisor runs after {@code MessageChatMemoryAdvisor} loads history into the
 * prompt and strips any orphaned tool messages before the request reaches the model.</p>
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

    /**
     * Remove orphaned tool messages from the history. A ToolResponseMessage is orphaned
     * if its tool call IDs don't have matching AssistantMessage tool calls earlier in
     * the history. An AssistantMessage with only tool calls (no text) is orphaned if
     * none of its tool call IDs appear in any subsequent ToolResponseMessage.
     */
    static List<Message> sanitizeToolPairs(List<Message> messages) {
        // First pass: collect all tool_use IDs from AssistantMessages
        Set<String> toolUseIds = new HashSet<>();
        for (Message msg : messages) {
            if (msg instanceof AssistantMessage assistant && assistant.getToolCalls() != null) {
                for (AssistantMessage.ToolCall tc : assistant.getToolCalls()) {
                    toolUseIds.add(tc.id());
                }
            }
        }

        // Second pass: collect all tool_result IDs from ToolResponseMessages
        Set<String> toolResultIds = new HashSet<>();
        for (Message msg : messages) {
            if (msg instanceof ToolResponseMessage toolResponse) {
                for (ToolResponseMessage.ToolResponse resp : toolResponse.getResponses()) {
                    toolResultIds.add(resp.id());
                }
            }
        }

        // Third pass: filter out orphaned messages
        List<Message> result = new ArrayList<>(messages.size());
        for (Message msg : messages) {
            if (msg instanceof ToolResponseMessage toolResponse) {
                // Keep only if ALL its tool responses have matching tool_use IDs
                boolean allMatched = toolResponse.getResponses().stream()
                        .allMatch(resp -> toolUseIds.contains(resp.id()));
                if (allMatched) {
                    result.add(msg);
                }
            } else if (msg instanceof AssistantMessage assistant
                    && assistant.getToolCalls() != null
                    && !assistant.getToolCalls().isEmpty()
                    && (assistant.getText() == null || assistant.getText().isBlank())) {
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
