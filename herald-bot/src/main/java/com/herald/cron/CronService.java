package com.herald.cron;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import com.herald.agent.AgentService;
import com.herald.config.HeraldConfig;
import com.herald.telegram.TelegramSender;

import org.springframework.ai.chat.memory.ChatMemory;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Service
public class CronService {

    private static final Logger log = LoggerFactory.getLogger(CronService.class);

    private final CronRepository cronRepository;
    private final AgentService agentService;
    private final TelegramSender telegramSender;
    private final ChatMemory chatMemory;
    private final ZoneId timezone;
    private final ThreadPoolTaskScheduler scheduler;
    private final Map<String, ScheduledFuture<?>> scheduledJobs = new ConcurrentHashMap<>();

    public CronService(CronRepository cronRepository, AgentService agentService,
                       TelegramSender telegramSender, ChatMemory chatMemory, HeraldConfig config) {
        this.cronRepository = cronRepository;
        this.agentService = agentService;
        this.telegramSender = telegramSender;
        this.chatMemory = chatMemory;
        this.timezone = ZoneId.of(config.cronTimezone());

        this.scheduler = new ThreadPoolTaskScheduler();
        this.scheduler.setPoolSize(4);
        this.scheduler.setThreadNamePrefix("cron-");
        this.scheduler.initialize();
    }

    @PostConstruct
    void loadJobs() {
        List<CronJob> enabledJobs = cronRepository.findAll().stream()
                .filter(CronJob::enabled)
                .toList();
        for (CronJob job : enabledJobs) {
            scheduleJob(job);
        }
        log.info("Loaded {} enabled cron job(s)", enabledJobs.size());
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
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
        cancelJob(name);
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
        cancelJob(name);
        cronRepository.setEnabled(name, false);
        log.info("Disabled cron job '{}'", name);
    }

    public boolean deleteJob(String name) {
        boolean deleted = cronRepository.delete(name);
        if (deleted) {
            cancelJob(name);
            log.info("Deleted cron job '{}'", name);
        }
        return deleted;
    }

    public CronJob findJob(String name) {
        return cronRepository.findByName(name);
    }

    public List<CronJob> listJobs() {
        return cronRepository.findAll();
    }

    private void scheduleJob(CronJob job) {
        cancelJob(job.name());
        Runnable task = () -> executeJob(job);
        CronTrigger trigger = new CronTrigger(job.schedule(), timezone);
        ScheduledFuture<?> future = scheduler.schedule(task, trigger);
        scheduledJobs.put(job.name(), future);
    }

    private void cancelJob(String name) {
        ScheduledFuture<?> future = scheduledJobs.remove(name);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void executeJob(CronJob job) {
        String conversationId = "cron-" + job.name();
        try {
            log.info("Executing cron job '{}'", job.name());
            String response = agentService.chat(job.prompt(), conversationId);
            telegramSender.sendMessage(response);
            cronRepository.updateLastRun(job.name(), LocalDateTime.now());
            log.info("Cron job '{}' completed successfully", job.name());
        } catch (Exception e) {
            log.error("Cron job '{}' failed", job.name(), e);
        } finally {
            chatMemory.clear(conversationId);
        }
    }
}
