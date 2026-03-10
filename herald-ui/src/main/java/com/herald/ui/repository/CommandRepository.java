package com.herald.ui.repository;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CommandRepository {

    private final JdbcTemplate jdbcTemplate;

    public CommandRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    List<Map<String, Object>> listRecent(int limit) {
        return jdbcTemplate.queryForList(
                "SELECT id, type, payload, status, created_at, completed_at FROM commands ORDER BY id DESC LIMIT ?",
                limit);
    }

    public Map<String, Object> insert(String type, String payload) {
        jdbcTemplate.update(
                "INSERT INTO commands (type, payload, status) VALUES (?, ?, 'pending')", type, payload);
        return jdbcTemplate.queryForMap(
                "SELECT id, type, payload, status, created_at, completed_at FROM commands WHERE rowid = last_insert_rowid()");
    }

    int countPending() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM commands WHERE status = 'pending'", Integer.class);
        return count != null ? count : 0;
    }
}
