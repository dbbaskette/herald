package com.herald.agent;

import java.util.UUID;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Decorator that publishes {@code tool_start} / {@code tool_end} events to the
 * {@link ToolEventBus} around every {@link ToolCallback#call(String, ToolContext)}
 * invocation (#362).
 *
 * <p>Reads the active conversation id from {@link ChatChannelContext}. If no
 * conversation context is set (e.g. tests, scheduled jobs), events are
 * silently dropped — they would have nowhere to go anyway.</p>
 */
public class ObservingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolEventBus bus;

    public ObservingToolCallback(ToolCallback delegate, ToolEventBus bus) {
        this.delegate = delegate;
        this.bus = bus;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String input) {
        return call(input, null);
    }

    @Override
    public String call(String input, ToolContext toolContext) {
        String convo = ChatChannelContext.getConversationId();
        if (convo == null) {
            return delegate.call(input, toolContext);
        }
        String callId = UUID.randomUUID().toString();
        String name = getToolDefinition().name();
        String preview = previewArgs(input);
        long started = System.currentTimeMillis();
        bus.publish(new ToolEventBus.ToolStart(convo, callId, name, preview));
        boolean ok = true;
        String result = null;
        try {
            result = delegate.call(input, toolContext);
            return result;
        } catch (RuntimeException e) {
            ok = false;
            throw e;
        } finally {
            long elapsed = System.currentTimeMillis() - started;
            bus.publish(new ToolEventBus.ToolEnd(convo, callId, name, elapsed, ok,
                    summarize(result)));
        }
    }

    /** Trim the tool input to a short headline for the UI card. */
    private static String previewArgs(String input) {
        if (input == null) return "";
        String s = input.replace('\n', ' ').replace('\r', ' ').trim();
        if (s.length() > 120) s = s.substring(0, 120) + "…";
        return s;
    }

    /** Trim tool output for the UI card summary. */
    private static String summarize(String output) {
        if (output == null) return "";
        String trimmed = output.strip();
        if (trimmed.isEmpty()) return "";
        // Take the first non-empty line, cap at 200 chars.
        int newline = trimmed.indexOf('\n');
        String firstLine = newline >= 0 ? trimmed.substring(0, newline) : trimmed;
        if (firstLine.length() > 200) firstLine = firstLine.substring(0, 200) + "…";
        return firstLine;
    }
}
