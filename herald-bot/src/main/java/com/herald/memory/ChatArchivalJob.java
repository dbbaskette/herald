package com.herald.memory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background job that archives chat session messages to the Obsidian vault hourly.
 *
 * <p>Reads messages from {@code SPRING_AI_CHAT_MEMORY} for each active conversation,
 * builds a condensed summary, and writes it to {@code Chat-Sessions/} in the
 * Herald-Memory vault. After archiving, messages older than the archive window
 * are trimmed to prevent unbounded growth.
 */
@Component
public class ChatArchivalJob {

    private static final Logger log = LoggerFactory.getLogger(ChatArchivalJob.class);

    private static final String OBSIDIAN_CLI = "/Applications/Obsidian.app/Contents/MacOS/Obsidian";
    private static final String OBSIDIAN_VAULT = "Herald-Memory";
    private static final int MAX_SUMMARY_CHARS = 3000;
    private static final int KEEP_RECENT_MESSAGES = 20;

    private final JdbcTemplate jdbcTemplate;

    public ChatArchivalJob(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Runs every hour (3-minute initial delay to let the app initialize).
     */
    @Scheduled(fixedRate = 3600_000, initialDelay = 180_000)
    public void archiveSessions() {
        if (!isObsidianAvailable()) {
            log.debug("Obsidian CLI not available — skipping chat archival");
            return;
        }

        // Find conversations with messages to archive
        List<String> conversationIds = jdbcTemplate.queryForList(
                "SELECT DISTINCT conversation_id FROM SPRING_AI_CHAT_MEMORY",
                String.class);

        if (conversationIds.isEmpty()) {
            return;
        }

        int archived = 0;
        for (String conversationId : conversationIds) {
            int count = countMessages(conversationId);
            if (count <= KEEP_RECENT_MESSAGES) {
                continue; // Not enough messages to warrant archiving
            }

            String summary = buildSessionSummary(conversationId);
            if (summary.isBlank()) {
                continue;
            }

            if (archiveToObsidian(conversationId, summary)) {
                trimOldMessages(conversationId);
                archived++;
            }
        }

        if (archived > 0) {
            log.info("Chat archival complete: archived {} conversation(s) to Obsidian", archived);
        }
    }

    private int countMessages(String conversationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ?",
                Integer.class, conversationId);
        return count != null ? count : 0;
    }

    /**
     * Build a summary of the conversation by extracting recent messages.
     * Messages are stored as JSON content blobs — we extract the text.
     */
    private String buildSessionSummary(String conversationId) {
        List<Map<String, Object>> messages = jdbcTemplate.queryForList(
                "SELECT type, content, timestamp FROM SPRING_AI_CHAT_MEMORY "
                + "WHERE conversation_id = ? ORDER BY timestamp ASC",
                conversationId);

        if (messages.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        // Skip the most recent KEEP_RECENT_MESSAGES — those stay in memory
        int archiveEnd = Math.max(0, messages.size() - KEEP_RECENT_MESSAGES);

        for (int i = 0; i < archiveEnd; i++) {
            Map<String, Object> msg = messages.get(i);
            String type = (String) msg.get("type");
            String content = (String) msg.get("content");
            if (content == null || content.isBlank()) continue;

            // Extract text from content (may be JSON or plain text)
            String text = extractText(content);
            if (text.isBlank()) continue;

            String role = mapType(type);
            // Truncate individual messages to keep summary manageable
            if (text.length() > 500) {
                text = text.substring(0, 500) + "...";
            }
            sb.append("**").append(role).append(":** ").append(text).append("\n\n");

            if (sb.length() > MAX_SUMMARY_CHARS) {
                sb.append("*(truncated — ").append(archiveEnd - i - 1).append(" more messages)*\n");
                break;
            }
        }

        return sb.toString().trim();
    }

    private String extractText(String content) {
        if (content == null) return "";
        // Content may be JSON with a "text" field or raw text
        // Try simple extraction — look for text between quotes after "text":
        if (content.startsWith("{") || content.startsWith("[")) {
            // Simple JSON text extraction without pulling in a JSON parser
            int idx = content.indexOf("\"text\"");
            if (idx >= 0) {
                int start = content.indexOf("\"", idx + 6);
                if (start >= 0) {
                    int end = findClosingQuote(content, start + 1);
                    if (end >= 0) {
                        return content.substring(start + 1, end)
                                .replace("\\n", "\n")
                                .replace("\\\"", "\"")
                                .replace("\\\\", "\\");
                    }
                }
            }
            // Fallback: strip JSON artifacts and use as-is
            return content.replaceAll("[{}\\[\\]\"]", " ").replaceAll("\\s+", " ").trim();
        }
        return content.trim();
    }

    private int findClosingQuote(String s, int start) {
        for (int i = start; i < s.length(); i++) {
            if (s.charAt(i) == '"' && s.charAt(i - 1) != '\\') {
                return i;
            }
        }
        return -1;
    }

    private String mapType(String type) {
        if (type == null) return "System";
        return switch (type.toUpperCase()) {
            case "USER" -> "User";
            case "ASSISTANT" -> "Herald";
            case "SYSTEM" -> "System";
            default -> type;
        };
    }

    private boolean archiveToObsidian(String conversationId, String summary) {
        String date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmm"));
        String safeConvId = conversationId.replaceAll("[^a-zA-Z0-9._-]", "_");
        String path = "Chat-Sessions/" + date + "-" + time + "-" + safeConvId + ".md";
        String content = "---\\ntags: [chat-session, auto-archived]\\nconversation: " + safeConvId
                + "\\ndate: " + date
                + "\\n---\\n\\n# Chat Session: " + conversationId
                + "\\n\\nArchived: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                + "\\n\\n" + sanitizeForCli(summary);

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
                log.warn("Obsidian CLI timed out archiving conversation '{}'", conversationId);
                return false;
            }
            if (process.exitValue() != 0) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String error = r.lines().reduce("", (a, b) -> a + " " + b).trim();
                    log.warn("Obsidian CLI error archiving '{}': {}", conversationId, error);
                }
                return false;
            }

            log.info("Archived chat session '{}' → Obsidian: {}", conversationId, path);
            return true;
        } catch (Exception e) {
            log.warn("Failed to archive chat session '{}': {}", conversationId, e.getMessage());
            return false;
        }
    }

    /**
     * Remove messages older than the most recent KEEP_RECENT_MESSAGES.
     */
    private void trimOldMessages(String conversationId) {
        // Get the timestamp of the Nth most recent message
        List<String> timestamps = jdbcTemplate.queryForList(
                "SELECT timestamp FROM SPRING_AI_CHAT_MEMORY "
                + "WHERE conversation_id = ? ORDER BY timestamp DESC LIMIT 1 OFFSET ?",
                String.class, conversationId, KEEP_RECENT_MESSAGES - 1);

        if (timestamps.isEmpty()) {
            return;
        }

        String cutoff = timestamps.get(0);
        int deleted = jdbcTemplate.update(
                "DELETE FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ? AND timestamp < ?",
                conversationId, cutoff);

        if (deleted > 0) {
            log.info("Trimmed {} old messages from conversation '{}'", deleted, conversationId);
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
