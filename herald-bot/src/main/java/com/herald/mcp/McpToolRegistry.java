package com.herald.mcp;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/** Explicit capability boundary between discovered MCP schemas and model-callable tools. */
public final class McpToolRegistry {
    // These guarded local callbacks are injected later by HeraldAutoMemoryAdvisor.
    private static final Set<String> MEMORY_TOOLS = Set.of("MemoryView", "MemoryCreate", "MemoryStrReplace",
            "MemoryInsert", "MemoryDelete", "MemoryRename");
    public enum State { DISABLED, UNCONFIGURED, AVAILABLE, UNAVAILABLE }
    private final boolean enabled;
    private final Set<String> allowed;
    private final Supplier<? extends Collection<? extends ToolCallbackProvider>> providers;
    private volatile State state;

    public McpToolRegistry(boolean enabled, Collection<String> allowed,
            Supplier<? extends Collection<? extends ToolCallbackProvider>> providers) {
        this.enabled = enabled;
        this.allowed = Set.copyOf(allowed);
        this.providers = providers;
        this.state = enabled ? State.UNCONFIGURED : State.DISABLED;
    }
    public static McpToolRegistry disabled() { return new McpToolRegistry(false, List.of(), List::of); }
    public State state() { return state; }

    public ToolCallback[] appendTo(ToolCallback[] local) { return appendTo(local, Set.of()); }

    public ToolCallback[] appendTo(ToolCallback[] local, Collection<String> reserved) {
        if (!enabled) { state = State.DISABLED; return local; }
        if (allowed.isEmpty()) { state = State.UNCONFIGURED; return local; }
        Map<String, ToolCallback> merged = new LinkedHashMap<>();
        for (var callback : local) merged.put(callback.getToolDefinition().name(), callback);
        int added = 0;
        try {
            for (var provider : providers.get()) {
                try {
                    for (var callback : provider.getToolCallbacks()) {
                        String name = callback.getToolDefinition().name();
                        // MCP cannot replace a local tool, nor bypass the explicit allowlist.
                        if (allowed.contains(name) && !MEMORY_TOOLS.contains(name) && !reserved.contains(name) && !merged.containsKey(name)) {
                            merged.put(name, guarded(callback)); added++;
                        }
                    }
                } catch (RuntimeException failure) {
                    if (Thread.currentThread().isInterrupted() || failure instanceof CancellationException) throw failure;
                    LoggerFactory.getLogger(getClass()).warn("MCP provider discovery unavailable ({})", failure.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException failure) {
            if (Thread.currentThread().isInterrupted() || failure instanceof CancellationException) throw failure;
            LoggerFactory.getLogger(getClass()).warn("MCP providers unavailable ({})", failure.getClass().getSimpleName());
        }
        state = added > 0 ? State.AVAILABLE : State.UNAVAILABLE;
        return merged.values().toArray(ToolCallback[]::new);
    }

    private ToolCallback guarded(ToolCallback delegate) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
            public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
            public String call(String input) { return invoke(input, null); }
            public String call(String input, ToolContext context) { return invoke(input, context); }
            private String invoke(String input, ToolContext context) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException("MCP call cancelled");
                try {
                    // Retain the originating turn context; do not dispatch in an
                    // unrelated executor or replace ownership/elicitation metadata.
                    return context == null ? delegate.call(input) : delegate.call(input, context);
                } catch (RuntimeException failure) {
                    if (Thread.currentThread().isInterrupted() || failure instanceof CancellationException) throw failure;
                    return "MCP tool failed or became unavailable. No successful result was returned.";
                }
            }
        };
    }
}
