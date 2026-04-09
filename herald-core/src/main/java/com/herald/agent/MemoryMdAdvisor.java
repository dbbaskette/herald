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
 * Advisor that reads {@code MEMORY.md} from the long-term memories directory on each
 * turn and injects its content into the system prompt. This gives the model awareness
 * of what long-term memories exist so it can selectively load relevant files via
 * {@code MemoryView}.
 *
 * <p>Replaces the former {@code MemoryBlockAdvisor} which injected SQLite key-value
 * pairs. This advisor injects the file-based memory index instead.</p>
 */
class MemoryMdAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(MemoryMdAdvisor.class);

    private final Path memoriesDir;

    MemoryMdAdvisor(Path memoriesDir) {
        this.memoriesDir = memoriesDir;
    }

    private static final ThreadLocal<Boolean> INJECTED = ThreadLocal.withInitial(() -> false);

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (INJECTED.get()) {
            return chain.nextCall(request);
        }
        INJECTED.set(true);
        try {
            String content = readMemoryMd();
            if (!content.isEmpty()) {
                String delimited = "\n\n<long-term-memory>\n" + content + "\n</long-term-memory>";
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
        return "MemoryMdAdvisor";
    }

    @Override
    public int getOrder() {
        // Same slot as the old MemoryBlockAdvisor — after ContextMdAdvisor (+75),
        // before ContextCompactionAdvisor (+150)
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    String readMemoryMd() {
        Path memoryMdPath = memoriesDir.resolve("MEMORY.md");
        if (!Files.exists(memoryMdPath)) {
            log.debug("MEMORY.md not found at {}", memoryMdPath);
            return "";
        }
        try {
            return Files.readString(memoryMdPath, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            log.warn("Failed to read MEMORY.md at {}: {}", memoryMdPath, e.getMessage());
            return "";
        }
    }
}
