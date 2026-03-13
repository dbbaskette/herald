package com.herald.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.core.Ordered;

/**
 * Diagnostic advisor that dumps the full prompt (system + messages + tool defs)
 * to timestamped files under ~/.herald/prompt-dump/ for token analysis.
 * Only active when HERALD_PROMPT_DUMP=true.
 */
class PromptDumpAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(PromptDumpAdvisor.class);
    private static final DateTimeFormatter FILE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS").withZone(ZoneId.systemDefault());

    private static final ThreadLocal<Boolean> DUMPED = ThreadLocal.withInitial(() -> false);

    private final Path dumpDir;
    private final boolean enabled;

    PromptDumpAdvisor(boolean enabled) {
        this.enabled = enabled;
        this.dumpDir = Path.of(System.getProperty("user.home"), ".herald", "prompt-dump");
        if (enabled) {
            try {
                Files.createDirectories(dumpDir);
                log.info("Prompt dump enabled — writing to {}", dumpDir);
            } catch (IOException e) {
                log.warn("Failed to create prompt dump directory: {}", e.getMessage());
            }
        }
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (enabled && !DUMPED.get()) {
            DUMPED.set(true);
            try {
                dumpRequest(request);
            } finally {
                // Reset after the full chain completes (including tool iterations)
            }
        }

        ChatClientResponse response = chain.nextCall(request);

        if (enabled && DUMPED.get()) {
            DUMPED.set(false);
            dumpResponse(response);
        }

        return response;
    }

    private void dumpRequest(ChatClientRequest request) {
        try {
            String timestamp = FILE_FMT.format(Instant.now());
            StringBuilder sb = new StringBuilder();

            sb.append("=== PROMPT DUMP ").append(timestamp).append(" ===\n\n");

            // System prompt
            var prompt = request.prompt();
            String systemText = null;
            if (prompt != null) {
                for (Message msg : prompt.getInstructions()) {
                    if (msg.getMessageType() == org.springframework.ai.chat.messages.MessageType.SYSTEM) {
                        systemText = msg.getText();
                        break;
                    }
                }
            }
            if (systemText != null && !systemText.isBlank()) {
                sb.append("--- SYSTEM PROMPT (").append(systemText.length()).append(" chars) ---\n");
                sb.append(systemText).append("\n\n");
            }

            // Messages (history + user)
            if (prompt != null && prompt.getInstructions() != null) {
                var messages = prompt.getInstructions().stream()
                        .filter(m -> m.getMessageType() != org.springframework.ai.chat.messages.MessageType.SYSTEM)
                        .toList();
                sb.append("--- MESSAGES (").append(messages.size()).append(") ---\n");
                for (Message msg : messages) {
                    String content = msg.getText();
                    sb.append("[").append(msg.getMessageType()).append("] ");
                    sb.append("(").append(content != null ? content.length() : 0).append(" chars)\n");
                    if (content != null) {
                        // Truncate very long messages for readability
                        if (content.length() > 2000) {
                            sb.append(content, 0, 2000).append("\n... [truncated, ")
                                    .append(content.length()).append(" total chars]\n\n");
                        } else {
                            sb.append(content).append("\n\n");
                        }
                    }
                }
            }

            // Tool definitions (available via context map if present)
            try {
                @SuppressWarnings("unchecked")
                var toolCallbacks = (java.util.Collection<?>) request.context().get("toolCallbacks");
                if (toolCallbacks != null && !toolCallbacks.isEmpty()) {
                    sb.append("--- TOOLS (").append(toolCallbacks.size()).append(") ---\n");
                    for (var tool : toolCallbacks) {
                        if (tool instanceof org.springframework.ai.tool.ToolCallback tc) {
                            var def = tc.getToolDefinition();
                            sb.append("  - ").append(def.name());
                            String desc = def.description();
                            if (desc != null) {
                                sb.append(" (").append(desc.length()).append(" chars desc)");
                            }
                            sb.append("\n");
                        } else {
                            sb.append("  - ").append(tool.getClass().getSimpleName()).append("\n");
                        }
                    }
                    sb.append("\n");
                }
            } catch (Exception ignored) {
                // Tool info not available in this request context
            }

            // Rough token estimate
            int totalChars = sb.length();
            sb.append("--- ESTIMATE: ~").append(totalChars / 4).append(" tokens (chars/4) ---\n");

            Path file = dumpDir.resolve("request_" + timestamp + ".txt");
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            log.debug("Prompt dumped to {}", file);
        } catch (Exception e) {
            log.warn("Failed to dump prompt: {}", e.getMessage());
        }
    }

    private void dumpResponse(ChatClientResponse response) {
        try {
            String timestamp = FILE_FMT.format(Instant.now());
            StringBuilder sb = new StringBuilder();
            sb.append("=== RESPONSE DUMP ").append(timestamp).append(" ===\n\n");

            var result = response.chatResponse();
            if (result != null && result.getResult() != null) {
                var output = result.getResult().getOutput();
                if (output != null && output.getText() != null) {
                    sb.append("--- ASSISTANT (").append(output.getText().length()).append(" chars) ---\n");
                    sb.append(output.getText()).append("\n\n");
                }
                if (result.getMetadata() != null && result.getMetadata().getUsage() != null) {
                    var usage = result.getMetadata().getUsage();
                    sb.append("--- TOKEN USAGE ---\n");
                    sb.append("  Input:  ").append(usage.getPromptTokens()).append("\n");
                    sb.append("  Output: ").append(usage.getCompletionTokens()).append("\n");
                    sb.append("  Total:  ").append(usage.getTotalTokens()).append("\n");
                }
            }

            Path file = dumpDir.resolve("response_" + timestamp + ".txt");
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Failed to dump response: {}", e.getMessage());
        }
    }

    @Override
    public String getName() {
        return "PromptDumpAdvisor";
    }

    @Override
    public int getOrder() {
        // Run just before ToolCallAdvisor to see the fully assembled prompt
        return Ordered.LOWEST_PRECEDENCE - 50;
    }
}
