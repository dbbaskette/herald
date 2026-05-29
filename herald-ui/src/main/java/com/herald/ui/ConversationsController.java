package com.herald.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Lists conversations grouped from the {@code SPRING_AI_CHAT_MEMORY} table.
 * Supports the multi-conversation sidebar (#361): one entry per
 * {@code conversation_id} with a synthesized title (first user turn) and
 * latest-turn timestamp.
 */
@RestController
@RequestMapping("/api/conversations")
class ConversationsController {

    private final JdbcTemplate jdbcTemplate;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    ConversationsController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Aggregated list of conversations with a derived title. */
    @GetMapping
    List<Map<String, Object>> list() {
        // Per-conversation stats: turn count + last-activity time. We
        // separately fetch the first user message for a title.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT
                    conversation_id            AS id,
                    COUNT(*)                   AS turnCount,
                    MAX(timestamp)             AS lastTurnAt,
                    MIN(timestamp)             AS firstTurnAt
                FROM SPRING_AI_CHAT_MEMORY
                GROUP BY conversation_id
                ORDER BY MAX(timestamp) DESC
                """);

        // For each row, get the first user message's content for a title.
        // (Avoiding a window function so this works on SQLite without extras.)
        for (Map<String, Object> row : rows) {
            Object id = row.get("id");
            List<Map<String, Object>> first = jdbcTemplate.queryForList("""
                    SELECT content
                      FROM SPRING_AI_CHAT_MEMORY
                     WHERE conversation_id = ? AND type = 'USER'
                  ORDER BY timestamp ASC LIMIT 1
                    """, id);
            String title = summarize(first.isEmpty() ? null : (String) first.get(0).get("content"));
            row.put("title", title);
        }
        return rows;
    }

    /**
     * Full message history for one conversation, rendered human-readable.
     *
     * <p>{@code JsonChatMemoryRepository} stores each row's {@code content} as a
     * serialized message blob ({@code {"type":..,"content":..,"metadata":..}}) so
     * the agent can round-trip tool calls. The web chat only wants the visible
     * text, so we unwrap it here. Tool-response rows and tool-call-only assistant
     * rows (no visible text) are dropped — they're internal plumbing, not turns a
     * person should see when resuming a conversation.</p>
     */
    @GetMapping("/{id}/messages")
    List<Map<String, Object>> messages(@PathVariable String id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT type AS role, content, timestamp
                  FROM SPRING_AI_CHAT_MEMORY
                 WHERE conversation_id = ?
              ORDER BY timestamp ASC
                """, id);

        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String role = String.valueOf(row.get("role"));
            if ("TOOL".equals(role)) continue; // tool-response plumbing
            String text = extractText((String) row.get("content"));
            if (text == null || text.isBlank()) continue; // e.g. tool-call-only assistant turn
            Map<String, Object> clean = new LinkedHashMap<>();
            clean.put("role", role);
            clean.put("content", text);
            clean.put("timestamp", row.get("timestamp"));
            out.add(clean);
        }
        return out;
    }

    /** Hard-delete a conversation's history. */
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable String id) {
        jdbcTemplate.update("DELETE FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ?", id);
        return ResponseEntity.noContent().build();
    }

    /** Best-effort 40-char title from the first user message. */
    private static String summarize(String content) {
        String s = extractText(content);
        if (s == null) return "(empty)";
        s = s.replace("\n", " ").trim();
        if (s.length() > 40) s = s.substring(0, 40).trim() + "…";
        return s.isBlank() ? "(empty)" : s;
    }

    /**
     * Unwrap the visible text from a stored chat-memory row. The repository
     * serializes messages as {@code {"type":..,"content":..,"metadata":..}}; we
     * return the {@code content} field (falling back to the legacy {@code text}
     * field, then to the raw string for any pre-JSON rows).
     */
    static String extractText(String stored) {
        if (stored == null || stored.isBlank()) return "";
        String trimmed = stored.trim();
        if (trimmed.charAt(0) != '{') return stored; // legacy plain-text row
        try {
            JsonNode node = MAPPER.readTree(trimmed);
            if (node.hasNonNull("content")) return node.get("content").asText("");
            if (node.hasNonNull("text")) return node.get("text").asText("");
            return stored;
        } catch (Exception e) {
            return stored; // not valid JSON — surface as-is rather than hiding it
        }
    }
}
