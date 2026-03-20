package com.herald.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.core.Ordered;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Advisor that reads {@code CONTEXT.md} from disk on each turn and injects its content
 * into the system prompt. This provides a human-editable standing brief that the model
 * always has access to. Changes to the file are reflected immediately on the next turn.
 *
 * <p>Runs after {@link DateTimePromptAdvisor} but before {@link MemoryBlockAdvisor},
 * so context assembly order is: system prompt → CONTEXT.md → memory block.</p>
 */
class ContextMdAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ContextMdAdvisor.class);

    private final Path contextFilePath;

    ContextMdAdvisor(Path contextFilePath) {
        this.contextFilePath = contextFilePath;
    }

    private static final ThreadLocal<Boolean> INJECTED = ThreadLocal.withInitial(() -> false);

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (INJECTED.get()) {
            return chain.nextCall(request);
        }
        INJECTED.set(true);
        try {
            String content = readContextFile();
            if (!content.isEmpty()) {
                String delimited = "\n\n<context>\n" + content + "\n</context>";
                request = request.mutate()
                        .prompt(request.prompt().augmentSystemMessage(
                                existing -> new SystemMessage(existing.getText() + delimited)))
                        .build();
            }
            return chain.nextCall(request);
        } finally {
            INJECTED.remove();
        }
    }

    @Override
    public String getName() {
        return "ContextMdAdvisor";
    }

    @Override
    public int getOrder() {
        // Between DateTimePromptAdvisor (+50) and MemoryBlockAdvisor (+100)
        return Ordered.HIGHEST_PRECEDENCE + 75;
    }

    /**
     * Reads the CONTEXT.md file from disk. If the file does not exist or cannot be read,
     * returns an empty string and logs a debug/warn message.
     */
    String readContextFile() {
        if (!Files.exists(contextFilePath)) {
            log.debug("CONTEXT.md not found at {}", contextFilePath);
            return "";
        }
        try {
            String content = Files.readString(contextFilePath, StandardCharsets.UTF_8).trim();
            return content;
        } catch (IOException e) {
            log.warn("Failed to read CONTEXT.md at {}: {}", contextFilePath, e.getMessage());
            return "";
        }
    }

    /**
     * Creates the CONTEXT.md file with a starter template if it does not already exist.
     * Called once at startup to ensure the file is available for the user to edit.
     */
    void ensureTemplateExists(String templateContent) {
        if (Files.exists(contextFilePath)) {
            return;
        }
        try {
            Files.createDirectories(contextFilePath.getParent());
            Files.writeString(contextFilePath, templateContent, StandardCharsets.UTF_8);
            log.info("Created starter CONTEXT.md at {}", contextFilePath);
        } catch (IOException e) {
            log.warn("Failed to create starter CONTEXT.md at {}: {}", contextFilePath, e.getMessage());
        }
    }
}
