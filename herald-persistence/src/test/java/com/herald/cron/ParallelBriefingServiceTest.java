package com.herald.cron;

import com.herald.config.HeraldConfig;
import com.herald.tools.GwsAvailabilityChecker;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.task.repository.BackgroundTask;
import org.springaicommunity.agent.tools.task.repository.DefaultTaskRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ParallelBriefingServiceTest {

    @Test
    void dispatchesMultipleBackgroundTasksAndCollectsResults() {
        var taskRepo = new DefaultTaskRepository();
        var gwsChecker = mock(GwsAvailabilityChecker.class);
        when(gwsChecker.isAvailable()).thenReturn(true);

        HeraldConfig config = new HeraldConfig(null, null, null, null, null,
                new HeraldConfig.Weather("London"), null, null, null, null);

        var service = new ParallelBriefingService(taskRepo, gwsChecker, config, true);

        List<String> taskIds = service.dispatchResearchThreads();

        // With GWS + web search + city: weather, calendar, email, priorities = 4 threads
        assertThat(taskIds).hasSize(4);

        // All tasks should be registered in the repo
        for (String id : taskIds) {
            BackgroundTask task = taskRepo.getTasks(id);
            assertThat(task).isNotNull();
        }
    }

    @Test
    void dispatchesMinimalThreadsWhenNoGwsOrWeather() {
        var taskRepo = new DefaultTaskRepository();
        var gwsChecker = mock(GwsAvailabilityChecker.class);
        when(gwsChecker.isAvailable()).thenReturn(false);

        HeraldConfig config = new HeraldConfig(null, null, null, null, null, null, null, null, null, null);

        var service = new ParallelBriefingService(taskRepo, gwsChecker, config, false);

        List<String> taskIds = service.dispatchResearchThreads();

        // Only priorities thread
        assertThat(taskIds).hasSize(1);
    }

    @Test
    void collectResultsWaitsForCompletion() throws Exception {
        var taskRepo = new DefaultTaskRepository();
        var gwsChecker = mock(GwsAvailabilityChecker.class);
        when(gwsChecker.isAvailable()).thenReturn(false);

        HeraldConfig config = new HeraldConfig(null, null, null, null, null, null, null, null, null, null);

        var service = new ParallelBriefingService(taskRepo, gwsChecker, config, false);

        List<String> taskIds = service.dispatchResearchThreads();

        // Results should complete (the supplier runs immediately on the executor)
        List<String> results = service.collectResults(taskIds, 5000);

        assertThat(results).hasSize(1);
        assertThat(results.get(0)).isNotBlank();
    }
}
