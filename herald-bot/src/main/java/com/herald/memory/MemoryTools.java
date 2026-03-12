package com.herald.memory;

import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class MemoryTools {

    static final int SOFT_CAP = 15;

    private final MemoryRepository repository;

    public MemoryTools(MemoryRepository repository) {
        this.repository = repository;
    }

    @Tool(description = "Store or update a persistent fact in memory. Use this to remember information about the user, preferences, or important details across conversations.")
    public String memory_set(
            @ToolParam(description = "The key to store the fact under (e.g. 'name', 'project', 'preference')") String key,
            @ToolParam(description = "The value to store") String value) {
        repository.set(key, value);
        return "Stored memory: " + key + " = " + value;
    }

    @Tool(description = "Retrieve a specific fact from persistent memory by its key.")
    public String memory_get(
            @ToolParam(description = "The key to look up") String key) {
        String value = repository.get(key);
        if (value == null) {
            return "No memory found for key: " + key;
        }
        return key + " = " + value;
    }

    @Tool(description = "List all stored facts in hot memory (SQLite). NOTE: This only shows hot memory. For a complete picture, also search the Obsidian vault using the obsidian skill — cold memory contains research, session logs, and migrated entries.")
    public String memory_list() {
        Map<String, String> entries = repository.listAll();
        if (entries.isEmpty()) {
            return "No memories stored.";
        }
        return entries.entrySet().stream()
                .map(e -> "- **" + e.getKey() + "**: " + e.getValue())
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "Return hot memory (SQLite) usage statistics: entry count, total size, and whether the soft cap (~15 entries) is exceeded. Remember: this only covers hot memory — Obsidian vault holds additional cold memory (migrated entries, session logs, research).")
    public String memory_stats() {
        Map<String, String> entries = repository.listAll();
        int count = entries.size();
        int totalSize = entries.entrySet().stream()
                .mapToInt(e -> e.getKey().length() + e.getValue().length())
                .sum();
        String status = count > SOFT_CAP ? "OVER CAP — migrate verbose or stale entries to Obsidian" : "OK";
        return String.format("Entries: %d / ~%d soft cap | Total size: %d chars | Status: %s", count, SOFT_CAP, totalSize, status);
    }

    @Tool(description = "Delete a specific fact from persistent memory.")
    public String memory_delete(
            @ToolParam(description = "The key to delete") String key) {
        boolean deleted = repository.delete(key);
        if (deleted) {
            return "Deleted memory: " + key;
        }
        return "No memory found for key: " + key;
    }

    public void clearAll() {
        repository.deleteAll();
    }

    public int count() {
        return repository.count();
    }

    /**
     * Formats all memory entries as a markdown block suitable for system prompt inclusion.
     * Values are sanitized to mitigate prompt injection via stored memory content.
     */
    public String formatForSystemPrompt() {
        Map<String, String> entries = repository.listAll();
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("## Known Facts\n");
        entries.forEach((k, v) -> sb.append("- **").append(sanitize(k)).append("**: ").append(sanitize(v)).append("\n"));
        if (entries.size() > SOFT_CAP) {
            sb.append("\n⚠️ WARNING: Hot memory has ").append(entries.size()).append(" entries (soft cap: ~").append(SOFT_CAP)
              .append("). Migrate verbose or stale entries to Obsidian to keep this block lean.\n");
        }
        return sb.toString();
    }

    private static final int MAX_SANITIZED_LENGTH = 500;

    /**
     * Sanitizes a value for safe inclusion in system prompts by stripping control characters,
     * collapsing newlines, and truncating to a maximum length.
     */
    static String sanitize(String input) {
        if (input == null) {
            return "";
        }
        // Replace newlines and carriage returns with spaces to prevent prompt structure manipulation
        String sanitized = input.replaceAll("[\\r\\n]+", " ");
        // Strip other control characters (except normal space)
        sanitized = sanitized.replaceAll("[\\x00-\\x1F\\x7F]", "");
        if (sanitized.length() > MAX_SANITIZED_LENGTH) {
            sanitized = sanitized.substring(0, MAX_SANITIZED_LENGTH) + "…";
        }
        return sanitized;
    }
}
