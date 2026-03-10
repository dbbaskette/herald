package com.herald.ui.repository;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class CommandRepository {

    private final JdbcTemplate jdbcTemplate;

    CommandRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    List<Map<String, Object>> listRecent(int limit) {
        return jdbcTemplate.queryForList(
                "SELECT id, type, payload, status, created_at, completed_at FROM commands ORDER BY id DESC LIMIT ?",
                limit);
    }

    Map<String, Object> insert(String type, String payload) {
        jdbcTemplate.update(
                "INSERT INTO commands (type, payload) VALUES (?, ?)", type, payload);
        return jdbcTemplate.queryForMap(
                "SELECT id, type, payload, status, created_at, completed_at FROM commands WHERE rowid = last_insert_rowid()");
    }
}
