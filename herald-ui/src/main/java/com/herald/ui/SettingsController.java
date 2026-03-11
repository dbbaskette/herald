package com.herald.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
class SettingsController {

    private final JdbcTemplate jdbcTemplate;

    SettingsController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    Map<String, String> getAll() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT key, value FROM settings ORDER BY key");
        Map<String, String> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            result.put((String) row.get("key"), (String) row.get("value"));
        }
        return result;
    }

    @PutMapping
    Map<String, String> update(@RequestBody Map<String, String> settings) {
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            jdbcTemplate.update(
                    "INSERT INTO settings (key, value, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP) "
                            + "ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = CURRENT_TIMESTAMP",
                    entry.getKey(), entry.getValue());
        }
        return getAll();
    }
}
