package com.herald.ui.repository;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CronJobRepository {

    private final JdbcTemplate jdbcTemplate;

    public CronJobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> listAll() {
        return jdbcTemplate.queryForList(
                "SELECT id, name, schedule, prompt, last_run, enabled, built_in FROM cron_jobs ORDER BY name");
    }

    public Map<String, Object> getById(long id) {
        List<Map<String, Object>> results = jdbcTemplate.queryForList(
                "SELECT id, name, schedule, prompt, last_run, enabled, built_in FROM cron_jobs WHERE id = ?", id);
        return results.isEmpty() ? null : results.get(0);
    }

    public int update(long id, String schedule, String prompt, Boolean enabled) {
        return jdbcTemplate.update(
                "UPDATE cron_jobs SET schedule = ?, prompt = ?, enabled = ? WHERE id = ?",
                schedule, prompt, enabled != null && enabled ? 1 : 0, id);
    }
}
