package com.herald.cron;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Consumes only cron commands; acceptance in the UI never implies execution. */
@Component
@DependsOn("dataSourceInitializer")
public class CronCommandPoller {
    private static final Logger log = LoggerFactory.getLogger(CronCommandPoller.class);
    private final JdbcTemplate jdbc;
    private final CronService service;
    public CronCommandPoller(JdbcTemplate jdbc, CronService service) { this.jdbc = jdbc; this.service = service; }

    @jakarta.annotation.PostConstruct
    void recoverInterrupted() {
        // A bot restart must not leave accepted runs looking active forever.
        jdbc.update("UPDATE commands SET status='failed',completed_at=CURRENT_TIMESTAMP WHERE type IN ('RUN_CRON','SYNC_CRON') AND status='running'");
        jdbc.update("UPDATE cron_execution SET status='failed',message='Bot restarted during execution; outcome is unknown. Retry manually if appropriate.',updated_at=? WHERE status='running'", java.time.Instant.now().toString());
    }

    @Scheduled(fixedDelayString="${herald.cron.command-poll-ms:2000}")
    public void poll() {
        var pending = jdbc.queryForList("SELECT id,type,payload FROM commands WHERE status='pending' AND type IN ('RUN_CRON','SYNC_CRON') ORDER BY id LIMIT 20");
        for (var command : pending) {
            long commandId = ((Number) command.get("id")).longValue();
            if (jdbc.update("UPDATE commands SET status='running' WHERE id=? AND status='pending'", commandId) != 1) continue;
            String status = "completed";
            try {
                int jobId = Integer.parseInt((String) command.get("payload"));
                CronJob job = service.findJobById(jobId);
                if ("SYNC_CRON".equals(command.get("type"))) {
                    if (job == null) service.cancelJob(jobId); else service.rescheduleJob(job);
                } else {
                    if (job == null) throw new IllegalArgumentException("Cron job no longer exists");
                    status = service.executeJob(job);
                }
            } catch (Exception e) {
                status = "failed";
                log.warn("Cron command {} failed", commandId, e);
            }
            jdbc.update("UPDATE commands SET status=?,completed_at=CURRENT_TIMESTAMP WHERE id=?", status, commandId);
        }
    }
}
