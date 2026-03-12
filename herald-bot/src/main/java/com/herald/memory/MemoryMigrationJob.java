package com.herald.memory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background job that automatically migrates oversized or excess hot memory entries
 * to the Obsidian vault. Runs every hour and after startup (with a 2-minute delay
 * to let the app fully initialize).
 *
 * <p>Migration criteria (checked in order):
 * <ol>
 *   <li>Any entry with a value longer than 200 characters → always migrate</li>
 *   <li>If total count exceeds the soft cap (~15), migrate the longest entries
 *       until count is at or below the cap</li>
 * </ol>
 *
 * <p>Each migrated entry is written to the Obsidian vault under
 * {@code Migrated-Memory/<date>-<key>.md}, then deleted from SQLite.
 */
@Component
public class MemoryMigrationJob {

    private static final Logger log = LoggerFactory.getLogger(MemoryMigrationJob.class);

    private static final int VALUE_LENGTH_THRESHOLD = 200;
    private static final String OBSIDIAN_CLI = "/Applications/Obsidian.app/Contents/MacOS/obsidian";
    private static final String OBSIDIAN_VAULT = "Herald-Memory";

    private final MemoryRepository repository;

    public MemoryMigrationJob(MemoryRepository repository) {
        this.repository = repository;
    }

    /**
     * Runs every hour (and 2 minutes after startup).
     */
    @Scheduled(fixedRate = 3600_000, initialDelay = 120_000)
    public void migrate() {
        Map<String, String> entries = repository.listAll();
        if (entries.isEmpty()) {
            return;
        }

        if (!isObsidianAvailable()) {
            log.debug("Obsidian CLI not available — skipping memory migration");
            return;
        }

        int migrated = 0;

        // Phase 1: Migrate any entry with value > 200 chars
        for (var entry : entries.entrySet()) {
            if (entry.getValue().length() > VALUE_LENGTH_THRESHOLD) {
                if (archiveAndDelete(entry.getKey(), entry.getValue())) {
                    migrated++;
                }
            }
        }

        // Phase 2: If still over soft cap, migrate longest remaining entries
        if (repository.count() > MemoryTools.SOFT_CAP) {
            // Re-read after phase 1 deletions
            Map<String, String> remaining = repository.listAll();
            remaining.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue().length(), a.getValue().length()))
                    .limit(remaining.size() - MemoryTools.SOFT_CAP)
                    .forEach(entry -> archiveAndDelete(entry.getKey(), entry.getValue()));
            migrated += remaining.size() - Math.min(remaining.size(), MemoryTools.SOFT_CAP);
        }

        if (migrated > 0) {
            log.info("Memory migration complete: archived {} entries to Obsidian", migrated);
        }
    }

    /**
     * Archive a single memory entry to Obsidian, then delete from SQLite.
     * Returns true if successful.
     */
    private boolean archiveAndDelete(String key, String value) {
        String date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String safeKey = key.replaceAll("[^a-zA-Z0-9._-]", "_");
        String path = "Migrated-Memory/" + date + "-" + safeKey + ".md";
        String content = "---\\ntags: [migrated-memory]\\nkey: " + safeKey
                + "\\ndate: " + date
                + "\\n---\\n\\n# " + key + "\\n\\n" + sanitizeForCli(value);

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    OBSIDIAN_CLI, "create",
                    "vault=" + OBSIDIAN_VAULT,
                    "path=" + path,
                    "content=" + content,
                    "overwrite");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Obsidian CLI timed out migrating key '{}'", key);
                return false;
            }
            if (process.exitValue() != 0) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String error = r.lines().reduce("", (a, b) -> a + " " + b).trim();
                    log.warn("Obsidian CLI error migrating key '{}': {}", key, error);
                }
                return false;
            }

            repository.delete(key);
            log.info("Migrated memory '{}' ({} chars) → Obsidian: {}", key, value.length(), path);
            return true;
        } catch (Exception e) {
            log.warn("Failed to migrate memory '{}' to Obsidian: {}", key, e.getMessage());
            return false;
        }
    }

    private boolean isObsidianAvailable() {
        try {
            Process p = new ProcessBuilder(OBSIDIAN_CLI, "create", "--help").redirectErrorStream(true).start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String sanitizeForCli(String input) {
        return input.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
