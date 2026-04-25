package com.herald.doctor.checks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.herald.doctor.HealthCheck;

/**
 * Validates {@code ~/.herald/memories/} — the one directory Herald writes to
 * most. Checks existence, writability, MEMORY.md presence + required sections,
 * log.md size (warn when it's big enough to bloat context injection), hot.md
 * existence.
 */
public class MemoryDirCheck implements HealthCheck {

    private static final long LOG_WARN_BYTES = 5L * 1024 * 1024; // 5 MB
    private static final List<String> REQUIRED_SECTIONS = List.of(
            "## User", "## Feedback", "## Projects", "## References");

    private final Path memoriesDir;

    public MemoryDirCheck(Path memoriesDir) {
        this.memoriesDir = memoriesDir;
    }

    public MemoryDirCheck() {
        this(resolveDefaultPath());
    }

    private static Path resolveDefaultPath() {
        String raw = System.getenv().getOrDefault("HERALD_MEMORIES_DIR", "~/.herald/memories");
        if (raw.startsWith("~/")) {
            raw = System.getProperty("user.home") + raw.substring(1);
        }
        return Path.of(raw);
    }

    @Override
    public String name() {
        return "Memory directory";
    }

    @Override
    public Result run() {
        if (!Files.exists(memoriesDir)) {
            return Result.warn("missing at " + memoriesDir,
                    "Will be created on first launch; warning only if you expected one");
        }
        if (!Files.isWritable(memoriesDir)) {
            return Result.fail("not writable: " + memoriesDir,
                    "chmod u+w " + memoriesDir);
        }

        Path memoryMd = memoriesDir.resolve("MEMORY.md");
        Path logMd = memoriesDir.resolve("log.md");
        Path hotMd = memoriesDir.resolve("hot.md");
        StringBuilder msg = new StringBuilder(memoriesDir.toString()).append(" — ");
        StringBuilder warnings = new StringBuilder();
        boolean anyFail = false;

        if (Files.exists(memoryMd)) {
            try {
                String content = Files.readString(memoryMd);
                List<String> missing = REQUIRED_SECTIONS.stream()
                        .filter(section -> !content.contains(section))
                        .toList();
                if (!missing.isEmpty()) {
                    warnings.append("MEMORY.md missing sections: ").append(missing).append("; ");
                } else {
                    msg.append("MEMORY.md ok, ");
                }
            } catch (IOException e) {
                anyFail = true;
                warnings.append("MEMORY.md unreadable: ").append(e.getMessage()).append("; ");
            }
        } else {
            warnings.append("MEMORY.md missing; ");
        }

        if (Files.exists(logMd)) {
            try {
                long size = Files.size(logMd);
                msg.append("log.md ").append(DatabaseCheck.humanBytes(size));
                if (size > LOG_WARN_BYTES) {
                    warnings.append("log.md >").append(DatabaseCheck.humanBytes(LOG_WARN_BYTES))
                            .append(" — consider archiving; ");
                }
            } catch (IOException e) {
                warnings.append("log.md unreadable: ").append(e.getMessage()).append("; ");
            }
        }

        if (!Files.exists(hotMd)) {
            warnings.append("hot.md missing (auto-created on first write); ");
        }

        if (anyFail) {
            return Result.fail(msg.toString().trim() + " | " + warnings.toString().trim(), null);
        }
        if (warnings.length() > 0) {
            return Result.warn(msg.toString().trim() + " | " + warnings.toString().trim(), null);
        }
        return Result.ok(msg.toString().trim());
    }
}
