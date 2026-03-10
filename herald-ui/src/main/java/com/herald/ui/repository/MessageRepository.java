package com.herald.ui.repository;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MessageRepository {

    private final JdbcTemplate jdbcTemplate;

    public MessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> listRecent(int limit) {
        return jdbcTemplate.queryForList(
                "SELECT id, role, content, tool_calls, created_at FROM messages ORDER BY id DESC LIMIT ?",
                limit);
    }

    Map<String, Object> getById(long id) {
        List<Map<String, Object>> results = jdbcTemplate.queryForList(
                "SELECT id, role, content, tool_calls, created_at FROM messages WHERE id = ?", id);
        return results.isEmpty() ? null : results.get(0);
    }

    public void deleteAll() {
        jdbcTemplate.update("DELETE FROM messages");
    }

    int count() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM messages", Integer.class);
        return count != null ? count : 0;
    }
}
