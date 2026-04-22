package com.herald.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decorates a memory {@link ToolCallback} (one of {@code memoryCreate / memoryStrReplace /
 * memoryInsert / memoryDelete / memoryRename}) and appends an event to {@code log.md}
 * after the delegate call succeeds. Read-only operations are not wrapped.
 */
final class LoggingMemoryToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(LoggingMemoryToolCallback.class);

    private final ToolCallback delegate;
    private final Path logFile;
    private final String eventName;

    LoggingMemoryToolCallback(ToolCallback delegate, Path logFile) {
        this.delegate = delegate;
        this.logFile = logFile;
        this.eventName = eventNameFor(delegate.getToolDefinition().name());
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
    public String call(String toolInput) {
        String result = delegate.call(toolInput);
        logSuccess(toolInput);
        return result;
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String result = delegate.call(toolInput, toolContext);
        logSuccess(toolInput);
        return result;
    }

    private void logSuccess(String toolInput) {
        try {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("tool", delegate.getToolDefinition().name());
            String path = extractPath(toolInput);
            if (path != null) {
                fields.put("path", path);
            }
            MemoryLogWriter.appendEvent(logFile, eventName, fields);
        } catch (Exception e) {
            log.debug("Failed to record memory log entry for {}: {}",
                    delegate.getToolDefinition().name(), e.getMessage());
        }
    }

    /**
     * Extracts a best-effort {@code path} (or {@code old_path}) value from the JSON tool
     * input so the log captures which file changed without taking a JSON dependency.
     */
    static String extractPath(String toolInput) {
        if (toolInput == null) {
            return null;
        }
        String path = extractJsonString(toolInput, "path");
        if (path != null) {
            return path;
        }
        return extractJsonString(toolInput, "old_path");
    }

    private static String extractJsonString(String json, String key) {
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) {
            return null;
        }
        int colon = json.indexOf(':', k + needle.length());
        if (colon < 0) {
            return null;
        }
        int quote = json.indexOf('"', colon + 1);
        if (quote < 0) {
            return null;
        }
        var sb = new StringBuilder();
        for (int i = quote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                sb.append(json.charAt(++i));
                continue;
            }
            if (c == '"') {
                return sb.toString();
            }
            sb.append(c);
        }
        return null;
    }

    static String eventNameFor(String toolName) {
        return switch (toolName.toLowerCase()) {
            case "memorycreate" -> "CREATE";
            case "memorystrreplace" -> "STRREPLACE";
            case "memoryinsert" -> "INSERT";
            case "memorydelete" -> "DELETE";
            case "memoryrename" -> "RENAME";
            default -> "MEMORY_" + toolName.toUpperCase();
        };
    }

    static boolean isMutatingMemoryTool(String toolName) {
        return switch (toolName.toLowerCase()) {
            case "memorycreate", "memorystrreplace", "memoryinsert",
                    "memorydelete", "memoryrename" -> true;
            default -> false;
        };
    }
}
