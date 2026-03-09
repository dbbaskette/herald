package com.herald.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class MemoryRepository {

    private final JdbcTemplate jdbcTemplate;

    MemoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void set(String key, String value) {
        jdbcTemplate.update(
                "INSERT INTO memory (key, value, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP) "
                        + "ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = CURRENT_TIMESTAMP",
                key, value);
    }

    String get(String key) {
        List<String> results = jdbcTemplate.queryForList(
                "SELECT value FROM memory WHERE key = ?", String.class, key);
        return results.isEmpty() ? null : results.get(0);
    }

    Map<String, String> listAll() {
        Map<String, String> entries = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT key, value FROM memory ORDER BY key", rs -> {
            entries.put(rs.getString("key"), rs.getString("value"));
        });
        return entries;
    }

    boolean delete(String key) {
        int rows = jdbcTemplate.update("DELETE FROM memory WHERE key = ?", key);
        return rows > 0;
    }
}
