package com.herald.cron;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

@Component
public class CronRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<CronJob> ROW_MAPPER = (rs, rowNum) -> {
        String lastRunStr = rs.getString("last_run");
        LocalDateTime lastRun = lastRunStr != null ? LocalDateTime.parse(lastRunStr) : null;
        return new CronJob(
                rs.getInt("id"),
                rs.getString("name"),
                rs.getString("schedule"),
                rs.getString("prompt"),
                lastRun,
                rs.getInt("enabled") == 1,
                rs.getInt("built_in") == 1);
    };

    public CronRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    List<CronJob> findAll() {
        return jdbcTemplate.query("SELECT id, name, schedule, prompt, last_run, enabled, built_in FROM cron_jobs ORDER BY name",
                ROW_MAPPER);
    }

    List<CronJob> findEnabled() {
        return jdbcTemplate.query(
                "SELECT id, name, schedule, prompt, last_run, enabled, built_in FROM cron_jobs WHERE enabled = 1 ORDER BY name",
                ROW_MAPPER);
    }

    CronJob findById(int id) {
        List<CronJob> results = jdbcTemplate.query(
                "SELECT id, name, schedule, prompt, last_run, enabled, built_in FROM cron_jobs WHERE id = ?",
                ROW_MAPPER, id);
        return results.isEmpty() ? null : results.get(0);
    }

    CronJob findByName(String name) {
        List<CronJob> results = jdbcTemplate.query(
                "SELECT id, name, schedule, prompt, last_run, enabled, built_in FROM cron_jobs WHERE name = ?",
                ROW_MAPPER, name);
        return results.isEmpty() ? null : results.get(0);
    }

    void save(CronJob job) {
        Assert.isTrue(StringUtils.hasText(job.name()), "Job name must not be blank");
        CronService.validateSchedule(job.schedule());
        Assert.isTrue(StringUtils.hasText(job.prompt()), "Job prompt must not be blank");
        jdbcTemplate.update(
                "INSERT INTO cron_jobs (name, schedule, prompt, enabled) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT(name) DO UPDATE SET schedule = excluded.schedule, prompt = excluded.prompt, enabled = excluded.enabled",
                job.name(), job.schedule(), job.prompt(), job.enabled() ? 1 : 0);
    }

    void updateLastRun(String name, LocalDateTime lastRun) {
        jdbcTemplate.update("UPDATE cron_jobs SET last_run = ? WHERE name = ?", lastRun.toString(), name);
    }

    void updateSchedule(String name, String schedule) {
        CronService.validateSchedule(schedule);
        jdbcTemplate.update("UPDATE cron_jobs SET schedule = ? WHERE name = ?", schedule, name);
    }

    void setEnabled(String name, boolean enabled) {
        jdbcTemplate.update("UPDATE cron_jobs SET enabled = ? WHERE name = ?", enabled ? 1 : 0, name);
    }

    void update(CronJob job) {
        Assert.notNull(job.id(), "Job id must not be null for update");
        CronService.validateSchedule(job.schedule());
        jdbcTemplate.update(
                "UPDATE cron_jobs SET name = ?, schedule = ?, prompt = ?, enabled = ? WHERE id = ?",
                job.name(), job.schedule(), job.prompt(), job.enabled() ? 1 : 0, job.id());
    }

    void publishTimezone(String timezone) {
        jdbcTemplate.update("INSERT INTO settings(key,value) VALUES('cron.runtime-timezone',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at=CURRENT_TIMESTAMP", timezone);
    }

    void executionState(int id, String status, String message) {
        jdbcTemplate.update("INSERT INTO cron_execution(job_id,status,message,updated_at) VALUES(?,?,?,?) ON CONFLICT(job_id) DO UPDATE SET status=excluded.status,message=excluded.message,updated_at=excluded.updated_at",
                id, status, message, java.time.Instant.now().toString());
    }

    boolean delete(String name) {
        CronJob job = findByName(name);
        if (job != null && job.builtIn()) {
            throw new IllegalStateException("Built-in jobs cannot be deleted");
        }
        int rows = jdbcTemplate.update("DELETE FROM cron_jobs WHERE name = ? AND built_in = 0", name);
        return rows > 0;
    }
}
