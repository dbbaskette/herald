package com.herald.memory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(MemoryRepository.class)
public class MemoryTools {

    private static final Logger log = LoggerFactory.getLogger(MemoryTools.class);
    private static final String OBSIDIAN_CLI = "/Applications/Obsidian.app/Contents/MacOS/Obsidian";
    private static final String OBSIDIAN_VAULT = "Herald-Memory";
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

    @Tool(description = "List all notes in cold memory (Obsidian vault), grouped by folder. Shows chat sessions, migrated memory entries, research, and any other vault content.")
    public String memory_list_cold(
            @ToolParam(description = "Optional folder to filter by (e.g. 'Chat-Sessions', 'Migrated-Memory'). Leave empty to list all.", required = false) String folder) {
        try {
            // Use search with wildcard to get all notes
            List<String> cmd = new ArrayList<>();
            cmd.add(OBSIDIAN_CLI);
            cmd.add("search");
            cmd.add("vault=" + OBSIDIAN_VAULT);
            cmd.add("query=*");
            if (folder != null && !folder.isBlank()) {
                cmd.add("path=" + folder);
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            List<String> notes = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        notes.add(trimmed);
                    }
                }
            }

            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "Error: Obsidian CLI timed out";
            }
            if (process.exitValue() != 0) {
                return "Error: Obsidian CLI returned exit code " + process.exitValue();
            }

            if (notes.isEmpty()) {
                return "Cold memory (Obsidian vault) is empty" + (folder != null ? " in folder: " + folder : "") + ".";
            }

            // Group by folder
            Map<String, List<String>> grouped = new LinkedHashMap<>();
            for (String note : notes) {
                String noteFolder = note.contains("/") ? note.substring(0, note.indexOf('/')) : "(root)";
                String name = note.contains("/") ? note.substring(note.lastIndexOf('/') + 1) : note;
                grouped.computeIfAbsent(noteFolder, k -> new ArrayList<>()).add(name);
            }

            StringBuilder sb = new StringBuilder("## Cold Memory (Obsidian Vault)\n\n");
            for (var entry : grouped.entrySet()) {
                sb.append("### ").append(entry.getKey()).append(" (").append(entry.getValue().size()).append(" notes)\n");
                for (String name : entry.getValue()) {
                    sb.append("- ").append(name).append("\n");
                }
                sb.append("\n");
            }
            sb.append("**Total:** ").append(notes.size()).append(" notes");
            return sb.toString();
        } catch (Exception e) {
            log.error("Failed to list cold memory: {}", e.getMessage(), e);
            return "Error listing cold memory: " + e.getMessage();
        }
    }

    @Tool(description = "Show complete memory status across both hot memory (SQLite) and cold memory (Obsidian vault). Use this for a full picture of what Herald remembers.")
    public String memory_status() {
        // Hot memory stats
        Map<String, String> entries = repository.listAll();
        int hotCount = entries.size();
        int hotSize = entries.entrySet().stream()
                .mapToInt(e -> e.getKey().length() + e.getValue().length())
                .sum();
        String hotStatus = hotCount > SOFT_CAP ? "OVER CAP" : "OK";

        // Cold memory stats via Obsidian CLI
        int coldCount = 0;
        Map<String, Integer> folderCounts = new LinkedHashMap<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(OBSIDIAN_CLI, "search", "vault=" + OBSIDIAN_VAULT, "query=*");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        coldCount++;
                        String folder = trimmed.contains("/") ? trimmed.substring(0, trimmed.indexOf('/')) : "(root)";
                        folderCounts.merge(folder, 1, Integer::sum);
                    }
                }
            }

            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
        } catch (Exception e) {
            log.warn("Could not query cold memory: {}", e.getMessage());
        }

        StringBuilder sb = new StringBuilder("## Memory Status\n\n");
        sb.append("### Hot Memory (SQLite)\n");
        sb.append(String.format("- Entries: %d / ~%d soft cap | Size: %d chars | Status: %s\n", hotCount, SOFT_CAP, hotSize, hotStatus));
        if (!entries.isEmpty()) {
            sb.append("- Keys: ");
            sb.append(entries.keySet().stream().collect(Collectors.joining(", ")));
            sb.append("\n");
        }
        sb.append("\n### Cold Memory (Obsidian Vault)\n");
        sb.append(String.format("- Total notes: %d\n", coldCount));
        for (var fc : folderCounts.entrySet()) {
            sb.append(String.format("  - %s: %d\n", fc.getKey(), fc.getValue()));
        }
        if (coldCount == 0 && folderCounts.isEmpty()) {
            sb.append("- (empty or Obsidian CLI unavailable)\n");
        }

        return sb.toString();
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
