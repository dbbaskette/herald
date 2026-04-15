package com.herald.cron;

import com.herald.config.HeraldConfig;
import com.herald.tools.GwsAvailabilityChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.tools.task.repository.BackgroundTask;
import org.springaicommunity.agent.tools.task.repository.TaskRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Programmatic fan-out for morning briefing research threads.
 * Dispatches independent research tasks via {@link TaskRepository} for parallel
 * execution, then collects results. This demonstrates the Part 4 pattern of
 * parallel + background subagent execution from the Spring AI Agentic Patterns series.
 */
@Service
public class ParallelBriefingService {

    private static final Logger log = LoggerFactory.getLogger(ParallelBriefingService.class);

    private final TaskRepository taskRepository;
    private final GwsAvailabilityChecker gwsChecker;
    private final HeraldConfig config;
    private final boolean webSearchAvailable;

    // Spring wiring constructor — reads web search API key and derives the boolean
    public ParallelBriefingService(TaskRepository taskRepository,
                                   GwsAvailabilityChecker gwsChecker,
                                   HeraldConfig config,
                                   @Value("${herald.web.search-api-key:}") String webSearchApiKey) {
        this(taskRepository, gwsChecker, config,
             webSearchApiKey != null && !webSearchApiKey.isBlank());
    }

    // Package-private: used by tests only
    ParallelBriefingService(TaskRepository taskRepository,
                            GwsAvailabilityChecker gwsChecker,
                            HeraldConfig config,
                            boolean webSearchAvailable) {
        this.taskRepository = taskRepository;
        this.gwsChecker = gwsChecker;
        this.config = config;
        this.webSearchAvailable = webSearchAvailable;
    }

    /**
     * Dispatches independent research threads as background tasks.
     * Returns the list of task IDs that can be passed to {@link #collectResults}.
     */
    public List<String> dispatchResearchThreads() {
        List<String> taskIds = new ArrayList<>();

        String city = config.weatherLocation();
        if (webSearchAvailable && !city.isEmpty()) {
            taskIds.add(dispatchTask("weather",
                    "Find the current weather and today's forecast for " + city +
                    ". Return a concise summary."));
        }

        if (gwsChecker.isAvailable()) {
            taskIds.add(dispatchTask("calendar",
                    "Use calendar_events_list to list today's events and meetings with times. " +
                    "Return a formatted list."));
            taskIds.add(dispatchTask("email",
                    "Use gmail_search to check for flagged or important unread emails. " +
                    "Return a summary of each."));
        }

        taskIds.add(dispatchTask("priorities",
                "Use memory_list to surface the top 3 priorities or open items from memory. " +
                "Return a bullet list."));

        log.info("Dispatched {} parallel briefing threads", taskIds.size());
        return taskIds;
    }

    /**
     * Collects results from previously dispatched background tasks.
     * Waits up to {@code timeoutMs} per task for completion.
     */
    public List<String> collectResults(List<String> taskIds, long timeoutMs) {
        List<String> results = new ArrayList<>();
        for (String id : taskIds) {
            BackgroundTask task = taskRepository.getTasks(id);
            if (task == null) {
                log.warn("Task {} not found in repository", id);
                results.add("[Task " + id + " not found]");
                continue;
            }
            try {
                task.waitForCompletion(timeoutMs);
                if (task.hasError()) {
                    log.warn("Task {} failed: {}", id, task.getErrorMessage());
                    results.add("[Task " + id + " failed: " + task.getErrorMessage() + "]");
                } else {
                    results.add(task.getResult());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for task {}", id);
                results.add("[Task " + id + " interrupted]");
            }
        }
        return results;
    }

    private String dispatchTask(String label, String prompt) {
        String taskId = "briefing-" + label + "-" + UUID.randomUUID().toString().substring(0, 8);
        taskRepository.putTask(taskId, () -> prompt);
        log.debug("Dispatched background task: {} ({})", taskId, label);
        return taskId;
    }
}
