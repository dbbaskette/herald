package com.herald.cron;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

@Component
class CronRepository {

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

    CronRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    List<CronJob> findAll() {
        return jdbcTemplate.query("SELECT id, name, schedule, prompt, last_run, enabled, built_in FROM cron_jobs ORDER BY name",
                ROW_MAPPER);
    }

    CronJob findByName(String name) {
        List<CronJob> results = jdbcTemplate.query(
                "SELECT id, name, schedule, prompt, last_run, enabled, built_in FROM cron_jobs WHERE name = ?",
                ROW_MAPPER, name);
        return results.isEmpty() ? null : results.get(0);
    }

    void save(CronJob job) {
        Assert.isTrue(StringUtils.hasText(job.name()), "Job name must not be blank");
        Assert.isTrue(StringUtils.hasText(job.schedule()), "Job schedule must not be blank");
        Assert.isTrue(StringUtils.hasText(job.prompt()), "Job prompt must not be blank");
        jdbcTemplate.update(
                "INSERT INTO cron_jobs (name, schedule, prompt, enabled) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT(name) DO UPDATE SET schedule = excluded.schedule, prompt = excluded.prompt, enabled = excluded.enabled",
                job.name(), job.schedule(), job.prompt(), job.enabled() ? 1 : 0);
    }

    void updateLastRun(String name, LocalDateTime lastRun) {
        jdbcTemplate.update("UPDATE cron_jobs SET last_run = ? WHERE name = ?", lastRun.toString(), name);
    }

    void setEnabled(String name, boolean enabled) {
        jdbcTemplate.update("UPDATE cron_jobs SET enabled = ? WHERE name = ?", enabled ? 1 : 0, name);
    }

    boolean delete(String name) {
        int rows = jdbcTemplate.update("DELETE FROM cron_jobs WHERE name = ? AND built_in = 0", name);
        return rows > 0;
    }
}
