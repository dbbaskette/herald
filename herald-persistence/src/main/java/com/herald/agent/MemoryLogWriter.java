package com.herald.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Append-only writer for {@code log.md} and overwrite helper for {@code hot.md}.
 *
 * <p>Log format — one event per line, UTC timestamp, space-separated key=value pairs:
 * <pre>
 * 2026-04-22T14:02:03Z CREATE path=user_role.md
 * 2026-04-22T14:05:10Z COMPACT removed=12 kept=4 tokens~3200
 * </pre>
 *
 * <p>Values containing spaces are wrapped in double quotes with internal quotes
 * escaped. Newlines in values are replaced with {@code \\n} so every event stays
 * on one line.
 */
public final class MemoryLogWriter {

    private static final Logger log = LoggerFactory.getLogger(MemoryLogWriter.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_INSTANT;
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();

    private MemoryLogWriter() {}

    public static void appendEvent(Path logFile, String event, Map<String, String> fields) {
        if (logFile == null || event == null) {
            return;
        }
        String line = formatLine(Instant.now(), event, fields);
        Object lock = LOCKS.computeIfAbsent(logFile.toAbsolutePath().normalize(), p -> new Object());
        synchronized (lock) {
            try {
                Files.createDirectories(logFile.getParent());
                Files.writeString(
                        logFile,
                        line + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.warn("Failed to append to {}: {}", logFile, e.getMessage());
            }
        }
    }

    public static void writeHot(Path hotFile, String summary) {
        if (hotFile == null) {
            return;
        }
        String body = summary == null ? "" : summary.strip();
        String content = "# Hot Context\n\n"
                + "_Last updated: " + TS.format(Instant.now()) + "_\n\n"
                + body + "\n";
        try {
            Files.createDirectories(hotFile.getParent());
            Files.writeString(hotFile, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to write {}: {}", hotFile, e.getMessage());
        }
    }

    static String formatLine(Instant when, String event, Map<String, String> fields) {
        var sb = new StringBuilder();
        sb.append(TS.format(when.atZone(ZoneOffset.UTC).toInstant()))
                .append(' ')
                .append(event);
        if (fields != null) {
            for (Map.Entry<String, String> e : fields.entrySet()) {
                if (e.getValue() == null) {
                    continue;
                }
                sb.append(' ').append(e.getKey()).append('=').append(encode(e.getValue()));
            }
        }
        return sb.toString();
    }

    private static String encode(String value) {
        String sanitized = value.replace("\r", "").replace("\n", "\\n");
        if (sanitized.indexOf(' ') < 0 && sanitized.indexOf('"') < 0) {
            return sanitized;
        }
        return "\"" + sanitized.replace("\"", "\\\"") + "\"";
    }
}
