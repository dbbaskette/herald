package com.herald.ui;

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
                "SELECT key, value, updated_at FROM memory ORDER BY key");
    }

    Map<String, Object> get(String key) {
        List<Map<String, Object>> results = jdbcTemplate.queryForList(
                "SELECT key, value, updated_at FROM memory WHERE key = ?", key);
        return results.isEmpty() ? null : results.get(0);
    }

    Map<String, Object> upsert(String key, String value) {
        jdbcTemplate.update(
                "INSERT INTO memory (key, value, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP) "
                        + "ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = CURRENT_TIMESTAMP",
                key, value);
        return get(key);
    }

    boolean delete(String key) {
        int rows = jdbcTemplate.update("DELETE FROM memory WHERE key = ?", key);
        return rows > 0;
    }
}
