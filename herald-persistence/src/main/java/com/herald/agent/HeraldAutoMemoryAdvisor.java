package com.herald.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.tools.AutoMemoryTools;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Herald-owned replacement for {@code AutoMemoryToolsAdvisor} from
 * {@code spring-ai-agent-utils}. Behaves identically to upstream in terms of
 * system-prompt injection and tool-callback attachment, but wraps each memory
 * {@link ToolCallback} in a {@link LoggingMemoryToolCallback} so successful
 * mutations are appended to {@code log.md}.
 *
 * <p>We own this advisor instead of subclassing upstream because
 * {@code AutoMemoryToolsAdvisor.Builder} does not expose a hook to inject custom
 * tools or a tool listener. See the upstream follow-up request linked in
 * {@code MEMORY.md} / the memory-phase tracking issue.
 */
public final class HeraldAutoMemoryAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(HeraldAutoMemoryAdvisor.class);
    private static final Resource DEFAULT_SYSTEM_PROMPT =
            new ClassPathResource("prompt/AUTO_MEMORY_TOOLS_SYSTEM_PROMPT.md");
    private static final String CONSOLIDATION_REMINDER =
            "<system-reminder>Consolidate the long-term memory by summarizing "
                    + "and removing redundant information.</system-reminder>";

    private final int order;
    private final String memorySystemPrompt;
    private final List<ToolCallback> memoryToolCallbacks;
    private final BiPredicate<ChatClientRequest, Instant> consolidationTrigger;

    private HeraldAutoMemoryAdvisor(
            int order,
            String memorySystemPrompt,
            List<ToolCallback> memoryToolCallbacks,
            BiPredicate<ChatClientRequest, Instant> consolidationTrigger) {
        this.order = order;
        this.memorySystemPrompt = memorySystemPrompt;
        this.memoryToolCallbacks = List.copyOf(memoryToolCallbacks);
        this.consolidationTrigger = consolidationTrigger;
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public String getName() {
        return "HeraldAutoMemoryAdvisor";
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(before(request, chain));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(before(request, chain));
    }

    ChatClientRequest before(ChatClientRequest request, AdvisorChain ignored) {
        ChatOptions options = request.prompt().getOptions();
        if (!(options instanceof ToolCallingChatOptions toolOptions)) {
            return request;
        }

        String consolidationReminder = consolidationTrigger.test(request, Instant.now())
                ? CONSOLIDATION_REMINDER
                : "";

        String existingSystem = request.prompt().getSystemMessage().getText();
        String sep = System.lineSeparator();
        String augmentedSystem = existingSystem + sep + sep
                + memorySystemPrompt + sep + sep + consolidationReminder;

        ToolCallingChatOptions newOptions = (ToolCallingChatOptions) toolOptions.copy();
        List<ToolCallback> merged = new ArrayList<>(newOptions.getToolCallbacks());
        Set<String> existingNames = new LinkedHashSet<>();
        for (ToolCallback cb : merged) {
            existingNames.add(cb.getToolDefinition().name());
        }
        for (ToolCallback cb : memoryToolCallbacks) {
            if (!existingNames.contains(cb.getToolDefinition().name())) {
                merged.add(cb);
            }
        }
        newOptions.setToolCallbacks(new ArrayList<>(merged));

        Prompt newPrompt = request.prompt().mutate()
                .chatOptions(newOptions)
                .build()
                .augmentSystemMessage(m -> new SystemMessage(augmentedSystem));

        return request.mutate().prompt(newPrompt).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Test visibility. */
    List<ToolCallback> memoryToolCallbacks() {
        return memoryToolCallbacks;
    }

    public static final class Builder {
        private int order = Ordered.HIGHEST_PRECEDENCE + 100;
        private Path memoriesRootDirectory;
        private Path logFile;
        private Resource memorySystemPrompt = DEFAULT_SYSTEM_PROMPT;
        private BiPredicate<ChatClientRequest, Instant> memoryConsolidationTrigger =
                (req, instant) -> false;

        private Builder() {}

        public Builder order(int order) {
            this.order = order;
            return this;
        }

        public Builder memoriesRootDirectory(Path memoriesRootDirectory) {
            this.memoriesRootDirectory = memoriesRootDirectory;
            return this;
        }

        public Builder memoriesRootDirectory(String memoriesRootDirectory) {
            this.memoriesRootDirectory = Path.of(memoriesRootDirectory);
            return this;
        }

        public Builder logFile(Path logFile) {
            this.logFile = logFile;
            return this;
        }

        public Builder memorySystemPrompt(Resource memorySystemPrompt) {
            this.memorySystemPrompt = memorySystemPrompt;
            return this;
        }

        public Builder memoryConsolidationTrigger(
                BiPredicate<ChatClientRequest, Instant> memoryConsolidationTrigger) {
            this.memoryConsolidationTrigger = memoryConsolidationTrigger;
            return this;
        }

        public HeraldAutoMemoryAdvisor build() {
            Objects.requireNonNull(memoriesRootDirectory, "memoriesRootDirectory is required");

            AutoMemoryTools tools = AutoMemoryTools.builder()
                    .memoriesDir(memoriesRootDirectory)
                    .build();

            ToolCallback[] raw = MethodToolCallbackProvider.builder()
                    .toolObjects(tools)
                    .build()
                    .getToolCallbacks();

            List<ToolCallback> wrapped = new ArrayList<>(raw.length);
            for (ToolCallback cb : raw) {
                String name = cb.getToolDefinition().name();
                if (logFile != null && LoggingMemoryToolCallback.isMutatingMemoryTool(name)) {
                    wrapped.add(new LoggingMemoryToolCallback(cb, logFile));
                } else {
                    wrapped.add(cb);
                }
            }

            String promptText = readPrompt(memorySystemPrompt);
            return new HeraldAutoMemoryAdvisor(order, promptText, wrapped, memoryConsolidationTrigger);
        }

        private static String readPrompt(Resource resource) {
            try (var in = resource.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("Failed to read memory system prompt {}: {}",
                        resource.getDescription(), e.getMessage());
                throw new UncheckedIOException(e);
            }
        }
    }
}
