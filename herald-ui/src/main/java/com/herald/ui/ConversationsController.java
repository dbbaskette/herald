package com.herald.ui;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    /** Full message history for one conversation. */
    @GetMapping("/{id}/messages")
    List<Map<String, Object>> messages(@PathVariable String id) {
        return jdbcTemplate.queryForList("""
                SELECT type AS role, content, timestamp
                  FROM SPRING_AI_CHAT_MEMORY
                 WHERE conversation_id = ?
              ORDER BY timestamp ASC
                """, id);
    }

    /** Hard-delete a conversation's history. */
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable String id) {
        jdbcTemplate.update("DELETE FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ?", id);
        return ResponseEntity.noContent().build();
    }

    /** Best-effort 40-char title from the first user message. */
    private static String summarize(String content) {
        if (content == null) return "(empty)";
        // Spring AI's JsonChatMemoryRepository stores serialized JSON; pull text.
        String s = content;
        int idx = s.indexOf("\"text\":\"");
        if (idx >= 0) {
            int start = idx + 8;
            int end = s.indexOf('"', start);
            if (end > start) s = s.substring(start, end);
        }
        s = s.replace("\\n", " ").replace("\\\"", "\"").trim();
        if (s.length() > 40) s = s.substring(0, 40).trim() + "…";
        return s.isBlank() ? "(empty)" : s;
    }
}
