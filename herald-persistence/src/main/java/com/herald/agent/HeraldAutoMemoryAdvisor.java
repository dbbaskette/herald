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

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(HeraldAutoMemoryAdvisor.class);
    private static final Resource DEFAULT_SYSTEM_PROMPT =
            new ClassPathResource("prompt/AUTO_MEMORY_TOOLS_SYSTEM_PROMPT.md");
    private static final String CONSOLIDATION_REMINDER =
            "<system-reminder>Consolidate the long-term memory by summarizing "
                    + "and removing redundant information.</system-reminder>";

    private final Path memoriesRoot;
    private final int order;
    private final String memorySystemPrompt;
    private final List<ToolCallback> memoryToolCallbacks;
    private final BiPredicate<ChatClientRequest, Instant> consolidationTrigger;

    private HeraldAutoMemoryAdvisor(
            Path memoriesRoot, int order,
            String memorySystemPrompt,
            List<ToolCallback> memoryToolCallbacks,
            BiPredicate<ChatClientRequest, Instant> consolidationTrigger) {
        this.memoriesRoot = memoriesRoot;
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
        if (request.context().get("herald.memory.evidence") instanceof MemoryContextEvidence) {
            return chain.nextCall(request);
        }
        var evidence = evidence(request);
        try { return chain.nextCall(before(withEvidence(request, evidence), chain)); }
        finally { evidence.publish(false); }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        if (request.context().get("herald.memory.evidence") instanceof MemoryContextEvidence) {
            return chain.nextStream(request);
        }
        // Each subscription owns its evidence. Completion/cancellation may run on
        // another scheduler, so no ThreadLocal state is used for this guard.
        return Flux.defer(() -> {
            var evidence = evidence(request);
            try {
                return chain.nextStream(before(withEvidence(request, evidence), chain))
                        .doFinally(signal -> evidence.publish(false));
            } catch (RuntimeException exception) {
                evidence.publish(false);
                return Flux.error(exception);
            }
        });
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

        // Spring AI 2.0 GA: getToolCallbacks() may return null when no tools are
        // set (it returned an empty list pre-GA), so guard before copying.
        List<ToolCallback> existing = toolOptions.getToolCallbacks();
        List<ToolCallback> merged = new ArrayList<>(existing != null ? existing : List.of());
        Set<String> existingNames = new LinkedHashSet<>();
        for (ToolCallback cb : merged) {
            existingNames.add(cb.getToolDefinition().name());
        }
        for (ToolCallback cb : memoryToolCallbacks) {
            if (!existingNames.contains(cb.getToolDefinition().name())) {
                Object evidence = request.context().get("herald.memory.evidence");
                merged.add(evidence instanceof MemoryContextEvidence e ? tracked(cb, e) : cb);
            }
        }
        // Spring AI 2.0 GA: ChatOptions are immutable — rebuild via mutate()
        // instead of the removed copy() + setToolCallbacks().
        ToolCallingChatOptions newOptions = toolOptions.mutate().toolCallbacks(merged).build();

        Prompt newPrompt = request.prompt().mutate()
                .chatOptions(newOptions)
                .build()
                .augmentSystemMessage(m -> new SystemMessage(augmentedSystem));

        return request.mutate().prompt(newPrompt).build();
    }

    private MemoryContextEvidence evidence(ChatClientRequest request) {
        Object id = request.context().get("chat_memory_conversation_id");
        String conversation = id instanceof String s ? s : ChatChannelContext.getConversationId();
        var evidence = new MemoryContextEvidence(memoriesRoot, conversation);
        evidence.publish(true);
        Object hot = request.context().get("herald.memory.hot-path");
        if (hot instanceof String path) evidence.record(path);
        return evidence;
    }
    private ChatClientRequest withEvidence(ChatClientRequest request, MemoryContextEvidence evidence) {
        var context = new java.util.HashMap<String,Object>(request.context());
        context.put("herald.memory.evidence", evidence);
        return request.mutate().context(context).build();
    }
    private ToolCallback tracked(ToolCallback delegate, MemoryContextEvidence evidence) {
        return new ToolCallback() {
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
            public org.springframework.ai.tool.metadata.ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
            public String call(String input) { return observe(input, delegate.call(input)); }
            public String call(String input, org.springframework.ai.chat.model.ToolContext context) { return observe(input, delegate.call(input, context)); }
            private String observe(String input, String result) {
                String name = delegate.getToolDefinition().name().toLowerCase(java.util.Locale.ROOT);
                String path = LoggingMemoryToolCallback.extractPath(input);
                String text = result;
                // MethodToolCallback normally JSON-encodes a String return value.
                if (text != null && text.startsWith("\"")) {
                    try { text = JSON.readValue(text, String.class); }
                    catch (RuntimeException ignored) { text = null; }
                }
                // AutoMemoryTools returns a File:/Lines envelope for successful
                // reads. Words inside the note (including "error") are arbitrary.
                if ((name.equals("memoryview") || name.equals("memoryread"))
                        && text != null && text.startsWith("File: ")
                        && text.contains("\nLines ")) {
                    evidence.record(path);
                }
                if (LoggingMemoryToolCallback.isMutatingMemoryTool(name)
                        && !name.equals("memorydelete") && text != null
                        && text.startsWith("Successfully ")) {
                    evidence.attribution(path);
                }
                return result;
            }
        };
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
        private MemoryApprovalGate approvalGate;

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

        /**
         * Wire a {@link MemoryApprovalGate} so mutating tool calls go through
         * per-type approval (issue #317). When unset, all mutations apply
         * silently — preserving the pre-#317 behavior.
         */
        public Builder approvalGate(MemoryApprovalGate approvalGate) {
            this.approvalGate = approvalGate;
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
                    wrapped.add(new LoggingMemoryToolCallback(cb, logFile, approvalGate));
                } else {
                    wrapped.add(cb);
                }
            }

            String promptText = readPrompt(memorySystemPrompt);
            return new HeraldAutoMemoryAdvisor(memoriesRootDirectory, order, promptText, wrapped, memoryConsolidationTrigger);
        }

        private static String readPrompt(Resource resource) {
            // Honors ~/.herald/prompts/<filename> as a user override; falls
            // back to the bundled classpath resource.
            try {
                return PromptLoader.load(resource);
            } catch (RuntimeException e) {
                log.warn("Failed to read memory system prompt {}: {}",
                        resource.getDescription(), e.getMessage());
                throw e;
            }
        }
    }
}
