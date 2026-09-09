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

    public long create(String name, String schedule, String prompt, boolean enabled) {
        jdbcTemplate.update("INSERT INTO cron_jobs(name,schedule,prompt,enabled) VALUES(?,?,?,?)", name,schedule,prompt,enabled ? 1 : 0);
        return jdbcTemplate.queryForObject("SELECT id FROM cron_jobs WHERE name = ?", Long.class, name);
    }
    public int update(long id, String name, String schedule, String prompt, boolean enabled) {
        return jdbcTemplate.update("UPDATE cron_jobs SET name=?, schedule=?, prompt=?, enabled=? WHERE id=?", name,schedule,prompt,enabled ? 1 : 0,id);
    }
    public boolean delete(long id) {
        return jdbcTemplate.update("DELETE FROM cron_jobs WHERE id=? AND built_in=0", id) > 0;
    }
    public Map<String,Object> execution(long id) {
        var rows = jdbcTemplate.queryForList("SELECT status,message FROM cron_execution WHERE job_id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public String runtimeTimezone(String fallback) {
        var values = jdbcTemplate.queryForList("SELECT value FROM settings WHERE key='cron.runtime-timezone'", String.class);
        return values.isEmpty() ? fallback : values.get(0);
    }
    public String activeRunStatus(long id) {
        var rows = jdbcTemplate.queryForList("SELECT status FROM commands WHERE type='RUN_CRON' AND payload=? AND status IN ('pending','running') ORDER BY CASE status WHEN 'running' THEN 0 ELSE 1 END LIMIT 1", String.class, String.valueOf(id));
        return rows.isEmpty() ? null : rows.get(0).equals("pending") ? "queued" : "running";
    }
    public String syncStatus(long id) {
        var rows = jdbcTemplate.queryForList("SELECT status FROM commands WHERE type='SYNC_CRON' AND payload=? ORDER BY id DESC LIMIT 1", String.valueOf(id));
        if (rows.isEmpty()) return "applied";
        return switch ((String) rows.get(0).get("status")) { case "pending", "running" -> "queued"; case "completed" -> "applied"; default -> "failed"; };
    }
}
