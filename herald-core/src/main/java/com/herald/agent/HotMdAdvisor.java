package com.herald.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.core.Ordered;

import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads {@code hot.md} from disk on each turn and injects it into the system prompt
 * as a session-continuity note. The file is refreshed by
 * {@link ContextCompactionAdvisor} when the conversation is compacted, so the model
 * always has a short summary of what rolled off.
 *
 * <p>Runs after {@link ContextMdAdvisor} and before the memory-tools advisor so the
 * hot context sits next to the rest of the long-term memory block.</p>
 */
public class HotMdAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(HotMdAdvisor.class);

    private final Path hotFilePath;

    public HotMdAdvisor(Path hotFilePath) {
        this.hotFilePath = hotFilePath;
    }

    private static final ThreadLocal<Boolean> INJECTED = ThreadLocal.withInitial(() -> false);

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (INJECTED.get()) {
            return chain.nextCall(request);
        }
        INJECTED.set(true);
        try {
            return chain.nextCall(injectHot(request));
        } finally {
            INJECTED.remove();
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        if (INJECTED.get()) {
            return chain.nextStream(request);
        }
        INJECTED.set(true);
        return chain.nextStream(injectHot(request))
                .doFinally(signal -> INJECTED.remove());
    }

    private ChatClientRequest injectHot(ChatClientRequest request) {
        String content = readHotFile();
        if (content.isEmpty()) {
            return request;
        }
        String delimited = "\n\n<hot-context>\n" + content + "\n</hot-context>";
        return request.mutate()
                .prompt(request.prompt().augmentSystemMessage(
                        existing -> new SystemMessage(existing.getText() + delimited)))
                .build();
    }

    @Override
    public String getName() {
        return "HotMdAdvisor";
    }

    @Override
    public int getOrder() {
        // Between ContextMdAdvisor (+75) and the memory-tools advisor (+100).
        return Ordered.HIGHEST_PRECEDENCE + 90;
    }

    String readHotFile() {
        if (!Files.exists(hotFilePath)) {
            return "";
        }
        try {
            return Files.readString(hotFilePath, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            log.warn("Failed to read hot.md at {}: {}", hotFilePath, e.getMessage());
            return "";
        }
    }
}
