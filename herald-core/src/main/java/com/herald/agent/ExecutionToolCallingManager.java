package com.herald.agent;

import java.util.List;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/** Keeps upstream tool execution semantics while preventing work after a turn stops. */
public final class ExecutionToolCallingManager implements ToolCallingManager {
    private final ToolCallingManager delegate = ToolCallingManager.builder().build();
    @Override public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
        return delegate.resolveToolDefinitions(options);
    }
    @Override public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
        var options = (ToolCallingChatOptions) prompt.getOptions();
        var value = options.getToolContext().get(ExecutionState.KEY);
        if (!(value instanceof ExecutionState state))
            throw new IllegalStateException("Execution boundary is required for tool calls");
        state.check();
        var guarded = options.getToolCallbacks().stream().map(tool -> guard(tool, state)).toList();
        var result = delegate.executeToolCalls(new Prompt(prompt.getInstructions(),
                options.mutate().toolCallbacks(guarded).build()), response);
        state.check();
        return result;
    }
    private ToolCallback guard(ToolCallback delegate, ExecutionState state) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
            @Override public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
            @Override public String call(String input) { return call(input, new ToolContext(java.util.Map.of())); }
            @Override public String call(String input, ToolContext context) {
                state.check();
                try (var ignored = ExecutionState.attach(state)) {
                    String result = delegate.call(input, context);
                    state.check();
                    return result;
                }
            }
        };
    }
}
