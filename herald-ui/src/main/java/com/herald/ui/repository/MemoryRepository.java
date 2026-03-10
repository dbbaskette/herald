package com.herald.ui.repository;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class MemoryRepository {

    private final JdbcTemplate jdbcTemplate;

    MemoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    List<Map<String, Object>> listAll() {
        return jdbcTemplate.queryForList(
                "SELECT id, key, value, updated_at FROM memory ORDER BY key");
    }

    Map<String, Object> getByKey(String key) {
        List<Map<String, Object>> results = jdbcTemplate.queryForList(
                "SELECT id, key, value, updated_at FROM memory WHERE key = ?", key);
        return results.isEmpty() ? null : results.get(0);
    }
}
