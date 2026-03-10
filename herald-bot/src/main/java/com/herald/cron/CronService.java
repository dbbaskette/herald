package com.herald.cron;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import com.herald.agent.AgentService;
import com.herald.config.HeraldConfig;
import com.herald.telegram.TelegramSender;

import org.springframework.ai.chat.memory.ChatMemory;

import jakarta.annotation.PostConstruct;

@Service
public class CronService {

    private static final Logger log = LoggerFactory.getLogger(CronService.class);

    private final CronRepository cronRepository;
    private final AgentService agentService;
    private final TelegramSender telegramSender;
    private final ChatMemory chatMemory;
    private final BriefingJob briefingJob;
    private final ZoneId timezone;
    private final TaskScheduler scheduler;
    private final Map<Long, ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();

    public CronService(CronRepository cronRepository, AgentService agentService,
                       Optional<TelegramSender> telegramSender, ChatMemory chatMemory,
                       HeraldConfig config, BriefingJob briefingJob, TaskScheduler taskScheduler) {
        this.cronRepository = cronRepository;
        this.agentService = agentService;
        this.telegramSender = telegramSender.orElse(null);
        this.chatMemory = chatMemory;
        this.briefingJob = briefingJob;
        this.timezone = ZoneId.of(config.cronTimezone());
        this.scheduler = taskScheduler;
    }

    @PostConstruct
    void loadJobs() {
        List<CronJob> enabledJobs = cronRepository.findEnabled();
        for (CronJob job : enabledJobs) {
            scheduleJob(job);
        }
        log.info("Loaded {} enabled cron job(s)", enabledJobs.size());
    }

    public CronJob createJob(String name, String schedule, String prompt) {
        CronJob job = new CronJob(null, name, schedule, prompt, null, true, false);
        cronRepository.save(job);
        CronJob saved = cronRepository.findByName(name);
        scheduleJob(saved);
        log.info("Created and scheduled cron job '{}'", name);
        return saved;
    }

    public CronJob updateJob(String name, String schedule, String prompt) {
        CronJob existing = cronRepository.findByName(name);
        if (existing != null) {
            cancelJob(existing.id());
        }
        CronJob job = new CronJob(null, name, schedule, prompt, null, true, false);
        cronRepository.save(job);
        CronJob updated = cronRepository.findByName(name);
        scheduleJob(updated);
        log.info("Updated and rescheduled cron job '{}'", name);
        return updated;
    }

    public void enableJob(String name) {
        cronRepository.setEnabled(name, true);
        CronJob job = cronRepository.findByName(name);
        if (job != null) {
            scheduleJob(job);
            log.info("Enabled cron job '{}'", name);
        }
    }

    public void disableJob(String name) {
        CronJob job = cronRepository.findByName(name);
        if (job != null) {
            cancelJob(job.id());
        }
        cronRepository.setEnabled(name, false);
        log.info("Disabled cron job '{}'", name);
    }

    public boolean deleteJob(String name) {
        CronJob job = cronRepository.findByName(name);
        boolean deleted = cronRepository.delete(name);
        if (deleted && job != null) {
            cancelJob(job.id());
            log.info("Deleted cron job '{}'", name);
        }
        return deleted;
    }

    public CronJob findJobById(int id) {
        return cronRepository.findById(id);
    }

    /**
     * Cancels the old schedule and creates a new one for the given job.
     * Used when a job's cron expression is edited.
     */
    public void rescheduleJob(CronJob job) {
        cancelJob(job.id());
        if (job.enabled()) {
            scheduleJob(job);
        }
        log.info("Rescheduled cron job '{}' with schedule '{}'", job.name(), job.schedule());
    }

    public CronJob rescheduleJob(String name, String schedule) {
        CronJob existing = cronRepository.findByName(name);
        if (existing != null) {
            cancelJob(existing.id());
        }
        cronRepository.updateSchedule(name, schedule);
        CronJob updated = cronRepository.findByName(name);
        if (updated != null && updated.enabled()) {
            scheduleJob(updated);
        }
        log.info("Rescheduled cron job '{}' with schedule '{}'", name, schedule);
        return updated;
    }

    /**
     * Cancels a scheduled job without deleting it (for disable).
     */
    public void cancelJob(long id) {
        ScheduledFuture<?> future = scheduledFutures.remove(id);
        if (future != null) {
            future.cancel(false);
        }
    }

    public CronJob findJob(String name) {
        return cronRepository.findByName(name);
    }

    public List<CronJob> listJobs() {
        return cronRepository.findAll();
    }

    private void scheduleJob(CronJob job) {
        cancelJob(job.id());
        Runnable task = () -> executeJob(job);
        CronTrigger trigger = new CronTrigger(job.schedule(), timezone);
        ScheduledFuture<?> future = scheduler.schedule(task, trigger);
        scheduledFutures.put(job.id().longValue(), future);
    }

    void executeJob(CronJob job) {
        String conversationId = "cron-" + job.name();
        try {
            log.info("Executing cron job '{}'", job.name());
            String prompt;
            if (BriefingJob.MORNING_BRIEFING_NAME.equals(job.name())) {
                prompt = briefingJob.buildMorningPrompt();
            } else if (BriefingJob.WEEKLY_REVIEW_NAME.equals(job.name())) {
                prompt = briefingJob.buildWeeklyPrompt();
            } else {
                prompt = job.prompt();
            }
            String response = agentService.chat(prompt, conversationId);
            if (telegramSender != null) {
                telegramSender.sendMessage(response);
            } else {
                log.info("Cron job '{}' result (no Telegram configured): {}", job.name(), response);
            }
            cronRepository.updateLastRun(job.name(), LocalDateTime.now());
            log.info("Cron job '{}' completed successfully", job.name());
        } catch (Exception e) {
            log.error("Cron job '{}' failed", job.name(), e);
            if (telegramSender != null) {
                try {
                    telegramSender.sendMessage("Cron job '" + job.name() + "' failed: " + e.getMessage());
                } catch (Exception sendError) {
                    log.error("Failed to send error notification for cron job '{}'", job.name(), sendError);
                }
            }
        } finally {
            chatMemory.clear(conversationId);
        }
    }
}
