package com.herald.ui.repository;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class CronJobRepository {

    private final JdbcTemplate jdbcTemplate;

    CronJobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    List<Map<String, Object>> listAll() {
        return jdbcTemplate.queryForList(
                "SELECT id, name, schedule, prompt, last_run, enabled, built_in FROM cron_jobs ORDER BY name");
    }

    Map<String, Object> getById(long id) {
        List<Map<String, Object>> results = jdbcTemplate.queryForList(
                "SELECT id, name, schedule, prompt, last_run, enabled, built_in FROM cron_jobs WHERE id = ?", id);
        return results.isEmpty() ? null : results.get(0);
    }
}
