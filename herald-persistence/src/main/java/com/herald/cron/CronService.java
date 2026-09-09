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
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import com.herald.agent.AgentService;
import com.herald.config.HeraldConfig;
import com.herald.agent.MessageSender;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;

@Service
@DependsOn("dataSourceInitializer")
public class CronService {

    private static final Logger log = LoggerFactory.getLogger(CronService.class);

    private final CronRepository cronRepository;
    private final ObjectProvider<AgentService> agentServiceProvider;
    private final MessageSender messageSender;
    private final ChatMemory chatMemory;
    private final BriefingJob briefingJob;
    private final ZoneId timezone;
    private final TaskScheduler scheduler;
    private final Map<Long, ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();
    private final Map<Integer, Object> executionLocks = new ConcurrentHashMap<>();
    private final Optional<com.herald.agent.BudgetPolicy> budgetPolicy;

    public CronService(CronRepository cronRepository, ObjectProvider<AgentService> agentServiceProvider,
                       Optional<MessageSender> messageSender, ChatMemory chatMemory,
                       HeraldConfig config, BriefingJob briefingJob, TaskScheduler taskScheduler,
                       Optional<com.herald.agent.BudgetPolicy> budgetPolicy) {
        this.cronRepository = cronRepository;
        this.agentServiceProvider = agentServiceProvider;
        this.messageSender = messageSender.orElse(null);
        this.chatMemory = chatMemory;
        this.briefingJob = briefingJob;
        this.timezone = ZoneId.of(config.cronTimezone());
        this.scheduler = taskScheduler;
        this.budgetPolicy = budgetPolicy;
    }

    @PostConstruct
    void loadJobs() {
        cronRepository.publishTimezone(timezone.toString());
        List<CronJob> enabledJobs = cronRepository.findEnabled();
        for (CronJob job : enabledJobs) {
            try { scheduleJob(job); }
            catch (IllegalArgumentException e) {
                cronRepository.executionState(job.id(), "failed", "Invalid stored cron expression; edit this job to repair its schedule.");
                log.warn("Cannot schedule cron job '{}'", job.name(), e);
            }
        }
        log.info("Loaded {} enabled cron job(s)", enabledJobs.size());
    }

    public CronJob createJob(String name, String schedule, String prompt) {
        validateSchedule(schedule);
        CronJob existing = cronRepository.findByName(name);
        CronJob job = new CronJob(null, name, schedule, prompt, null, existing == null || existing.enabled(), existing != null && existing.builtIn());
        cronRepository.save(job);
        CronJob saved = cronRepository.findByName(name);
        rescheduleJob(saved);
        log.info("Created and scheduled cron job '{}'", name);
        return saved;
    }

    public CronJob updateJob(String name, String schedule, String prompt) {
        CronJob existing = cronRepository.findByName(name);
        String expression = schedule == null && existing != null ? existing.schedule() : schedule;
        String text = prompt == null && existing != null ? existing.prompt() : prompt;
        validateSchedule(expression);
        CronJob job = new CronJob(existing == null ? null : existing.id(), name, expression, text,
                existing == null ? null : existing.lastRun(), existing == null || existing.enabled(),
                existing != null && existing.builtIn());
        cronRepository.save(job);
        CronJob updated = cronRepository.findByName(name);
        rescheduleJob(updated);
        log.info("Updated and rescheduled cron job '{}'", name);
        return updated;
    }

    public void enableJob(String name) {
        CronJob job = cronRepository.findByName(name);
        if (job != null) validateSchedule(job.schedule());
        cronRepository.setEnabled(name, true);
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
        validateSchedule(job.schedule());
        cancelJob(job.id());
        if (job.enabled()) {
            scheduleJob(job);
        }
        log.info("Rescheduled cron job '{}' with schedule '{}'", job.name(), job.schedule());
    }

    public CronJob rescheduleJob(String name, String schedule) {
        validateSchedule(schedule);
        cronRepository.updateSchedule(name, schedule);
        CronJob updated = cronRepository.findByName(name);
        if (updated != null) rescheduleJob(updated);
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

    static String validateSchedule(String expression) {
        if (expression == null || expression.length() > 256) throw new IllegalArgumentException("Provide a cron expression with five or six fields");
        String normalized = expression.trim();
        if (normalized.split("\\s+").length == 5) normalized = "0 " + normalized;
        CronExpression.parse(normalized);
        return normalized;
    }

    private void scheduleJob(CronJob job) {
        CronTrigger trigger = new CronTrigger(validateSchedule(job.schedule()), timezone);
        cancelJob(job.id());
        Runnable task = () -> {
            synchronized (executionLocks.computeIfAbsent(job.id(), id -> new Object())) {
                // Recheck after acquiring the lock: a preceding run may have delayed this callback.
                CronJob current = cronRepository.findById(job.id());
                if (current != null && current.enabled() && current.schedule().equals(job.schedule())) executeLocked(current);
            }
        };
        ScheduledFuture<?> future = scheduler.schedule(task, trigger);
        if (future != null) scheduledFutures.put(job.id().longValue(), future);
    }

    String executeJob(CronJob job) {
        synchronized (executionLocks.computeIfAbsent(job.id(), id -> new Object())) {
            return executeLocked(job);
        }
    }

    private String executeLocked(CronJob job) {
        String outcome = "completed";
        String conversationId = "cron-" + job.name();
        try {
            cronRepository.executionState(job.id(), "running", null);
            log.info("Executing cron job '{}'", job.name());
            // Budget gate (#319): skip the job silently when blocked.
            if (budgetPolicy.isPresent()) {
                var decision = budgetPolicy.get().evaluate();
                if (decision.isBlocked()) {
                    log.info("Cron job '{}' skipped — budget policy: {}",
                            job.name(), decision.message());
                    cronRepository.executionState(job.id(), "skipped", decision.message());
                    return "skipped";
                }
            }
            String prompt;
            if (BriefingJob.MORNING_BRIEFING_NAME.equals(job.name())) {
                prompt = briefingJob.buildMorningPrompt();
            } else if (BriefingJob.PARALLEL_MORNING_BRIEFING_NAME.equals(job.name())) {
                prompt = briefingJob.buildParallelMorningPrompt();
            } else if (BriefingJob.WEEKLY_REVIEW_NAME.equals(job.name())) {
                prompt = briefingJob.buildWeeklyPrompt();
            } else {
                prompt = job.prompt();
            }
            // Built-in jobs keep their capability-aware context and honor saved prompt edits.
            if (BriefingJob.MORNING_BRIEFING_NAME.equals(job.name())
                    || BriefingJob.PARALLEL_MORNING_BRIEFING_NAME.equals(job.name())
                    || BriefingJob.WEEKLY_REVIEW_NAME.equals(job.name())) {
                prompt += "\n\n## Saved job instructions\n" + job.prompt();
            }
            String response = agentServiceProvider.getObject().chat(prompt, conversationId);
            if (messageSender != null) {
                messageSender.sendMessage(response);
            } else {
                log.info("Cron job '{}' result (no messaging configured): {}", job.name(), response);
            }
            cronRepository.updateLastRun(job.name(), LocalDateTime.now());
            cronRepository.executionState(job.id(), "completed", null);
            log.info("Cron job '{}' completed successfully", job.name());
        } catch (Exception e) {
            outcome = "failed";
            cronRepository.executionState(job.id(), "failed", "Execution failed; check the bot logs for details.");
            log.error("Cron job '{}' failed", job.name(), e);
            if (messageSender != null) {
                try {
                    messageSender.sendMessage("Cron job '" + job.name() + "' failed: " + e.getMessage());
                } catch (Exception sendError) {
                    log.error("Failed to send error notification for cron job '{}'", job.name(), sendError);
                }
            }
        } finally {
            try { chatMemory.clear(conversationId); }
            catch (Exception cleanupError) { log.warn("Could not clear cron conversation", cleanupError); }
        }
        return outcome;
    }
}
