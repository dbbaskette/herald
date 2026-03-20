package com.herald.cron;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(CronService.class)
public class CronTools {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final CronService cronService;

    public CronTools(CronService cronService) {
        this.cronService = cronService;
    }

    @Tool(description = "Create a new scheduled cron job that runs a prompt on a schedule. The schedule uses standard cron expression format (e.g. '0 0 9 * * MON-FRI' for weekdays at 9am).")
    public String cron_create(
            @ToolParam(description = "Unique name for the cron job") String name,
            @ToolParam(description = "Cron expression (e.g. '0 0 9 * * MON-FRI')") String schedule,
            @ToolParam(description = "The prompt to execute on each run") String prompt) {
        CronJob job = cronService.createJob(name, schedule, prompt);
        return "Created cron job '%s' with schedule: %s".formatted(job.name(), job.schedule());
    }

    @Tool(description = "Update an existing cron job's schedule and/or prompt.")
    public String cron_update(
            @ToolParam(description = "Name of the cron job to update") String name,
            @ToolParam(description = "New cron expression") String schedule,
            @ToolParam(description = "New prompt to execute on each run") String prompt) {
        CronJob job = cronService.updateJob(name, schedule, prompt);
        return "Updated cron job '%s' with schedule: %s".formatted(job.name(), job.schedule());
    }

    @Tool(description = "Delete a scheduled cron job by name. Built-in jobs cannot be deleted but can be disabled.")
    public String cron_delete(
            @ToolParam(description = "Name of the cron job to delete") String name) {
        try {
            boolean deleted = cronService.deleteJob(name);
            if (deleted) {
                return "Deleted cron job '%s'.".formatted(name);
            }
            return "No cron job found with name '%s'.".formatted(name);
        } catch (IllegalStateException e) {
            return "Cannot delete built-in cron job '%s'. You can disable it instead.".formatted(name);
        }
    }

    @Tool(description = "List all scheduled cron jobs with their name, schedule, enabled status, and last run time.")
    public String cron_list() {
        List<CronJob> jobs = cronService.listJobs();
        if (jobs.isEmpty()) {
            return "No cron jobs configured.";
        }
        return jobs.stream()
                .map(j -> "%s | %s | %s | %s | %s".formatted(
                        j.name(),
                        j.schedule(),
                        j.enabled() ? "enabled" : "disabled",
                        j.lastRun() != null ? j.lastRun().format(FMT) : "never",
                        j.builtIn() ? "built-in" : "custom"))
                .collect(Collectors.joining("\n"));
    }
}
