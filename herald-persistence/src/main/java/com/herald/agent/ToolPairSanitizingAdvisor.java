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
 *       ASSISTANT messages with empty content strings. The Anthropic API rejects these.</li>
 *   <li><b>Orphaned tool pairs</b> — When {@code MessageWindowChatMemory} trims old
 *       messages, it can split a tool_use/tool_result pair, leaving orphaned references.</li>
 * </ol>
 *
 * <p>Uses a thread-local counter to limit re-invocations during ToolCallAdvisor loops.
 * After {@value #MAX_ITERATIONS} iterations, sanitization is skipped to prevent infinite loops.</p>
 */
class ToolPairSanitizingAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ToolPairSanitizingAdvisor.class);
    private static final int MAX_ITERATIONS = 25;
    private static final ThreadLocal<Integer> ITERATION_COUNT = ThreadLocal.withInitial(() -> 0);

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        int iteration = ITERATION_COUNT.get();

        if (iteration >= MAX_ITERATIONS) {
            log.warn("Sanitizer hit max iterations ({}), passing through unsanitized", MAX_ITERATIONS);
            return chain.nextCall(request);
        }

        ITERATION_COUNT.set(iteration + 1);

        try {
            List<Message> messages = request.prompt().getInstructions();
            List<Message> sanitized = sanitizeToolPairs(messages);

            if (sanitized.size() != messages.size()) {
                if (iteration == 0) {
                    log.info("Removed {} orphaned tool messages from conversation history",
                            messages.size() - sanitized.size());
                }
                request = request.mutate()
                        .prompt(new Prompt(sanitized, request.prompt().getOptions()))
                        .build();
            }

            return chain.nextCall(request);
        } finally {
            // Reset counter when unwinding back to the original caller
            if (iteration == 0) {
                ITERATION_COUNT.remove();
            }
        }
    }

    static List<Message> sanitizeToolPairs(List<Message> messages) {
        // Step 1: Drop messages with empty/null content (JDBC serialization artifacts)
        // but ALWAYS preserve USER messages.
        List<Message> nonEmpty = new ArrayList<>(messages.size());
        for (Message msg : messages) {
            if (msg.getMessageType() == MessageType.USER) {
                // Always keep user messages
                nonEmpty.add(msg);
            } else if (msg instanceof ToolResponseMessage) {
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

        // Step 4: Filter orphaned tool pairs — drop tool_use without tool_result and vice versa
        List<Message> result = new ArrayList<>(nonEmpty.size());
        for (Message msg : nonEmpty) {
            if (msg instanceof ToolResponseMessage toolResponse) {
                // Keep only if ALL its tool_result IDs have a matching tool_use
                boolean allMatched = toolResponse.getResponses().stream()
                        .allMatch(resp -> toolUseIds.contains(resp.id()));
                if (allMatched) {
                    result.add(msg);
                }
            } else if (msg instanceof AssistantMessage assistant
                    && assistant.getToolCalls() != null
                    && !assistant.getToolCalls().isEmpty()) {
                // Assistant with tool calls: keep if it has text content OR any tool call has a matching result
                if (hasContent(msg)) {
                    // Has text content — keep but note: any unreferenced tool_use blocks
                    // are fine for the API as long as we don't send orphaned tool_results
                    result.add(msg);
                } else {
                    // Tool-only assistant: keep only if at least one tool call has a matching result
                    boolean anyMatched = assistant.getToolCalls().stream()
                            .anyMatch(tc -> toolResultIds.contains(tc.id()));
                    if (anyMatched) {
                        result.add(msg);
                    }
                }
            } else {
                result.add(msg);
            }
        }

        // Safety: ensure at least one message remains (the user's message)
        if (result.isEmpty()) {
            log.warn("Sanitizer would remove all messages — keeping last user message");
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i).getMessageType() == MessageType.USER) {
                    return List.of(messages.get(i));
                }
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
        return Ordered.LOWEST_PRECEDENCE - 50;
    }
}
