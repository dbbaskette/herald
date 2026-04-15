package com.herald.cron;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.task.repository.BackgroundTask;
import org.springaicommunity.agent.tools.task.repository.DefaultTaskRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test proving parallel subagent dispatch works: multiple tasks
 * run concurrently via DefaultTaskRepository, and TaskOutputTool-style
 * retrieval collects all results.
 *
 * Satisfies the issue #265 acceptance criterion: "Integration test covering
 * parallel dispatch with 2+ concurrent subagents."
 */
class ParallelDispatchIntegrationTest {

    @Test
    void multipleTasksRunConcurrentlyAndResultsAreCollected() throws Exception {
        var taskRepo = new DefaultTaskRepository();
        var concurrencyWitness = new AtomicInteger(0);
        var maxConcurrency = new AtomicInteger(0);
        var allStarted = new CountDownLatch(3);
        var gate = new CountDownLatch(1);

        List<String> taskIds = new ArrayList<>();

        // Dispatch 3 background tasks that all wait on a gate, proving concurrency
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            String taskId = "parallel-test-" + i;
            taskRepo.putTask(taskId, () -> {
                int current = concurrencyWitness.incrementAndGet();
                maxConcurrency.updateAndGet(max -> Math.max(max, current));
                allStarted.countDown();
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                concurrencyWitness.decrementAndGet();
                return "result-" + idx;
            });
            taskIds.add(taskId);
        }

        // Wait for all 3 tasks to be running concurrently
        assertThat(allStarted.await(5, TimeUnit.SECONDS))
                .as("All tasks should start concurrently")
                .isTrue();

        // Verify at least 2 tasks were running at the same time
        assertThat(maxConcurrency.get())
                .as("At least 2 tasks should run concurrently")
                .isGreaterThanOrEqualTo(2);

        // Release the gate so tasks complete
        gate.countDown();

        // Collect results
        List<String> results = new ArrayList<>();
        for (String id : taskIds) {
            BackgroundTask task = taskRepo.getTasks(id);
            assertThat(task).isNotNull();
            task.waitForCompletion(5000);
            assertThat(task.isCompleted()).isTrue();
            assertThat(task.hasError()).isFalse();
            results.add(task.getResult());
        }

        assertThat(results).containsExactly("result-0", "result-1", "result-2");

        taskRepo.shutdown();
    }

    @Test
    void wallClockImprovementOverSequentialExecution() throws Exception {
        var taskRepo = new DefaultTaskRepository();
        long sleepMs = 200;

        // Parallel: dispatch 3 tasks that each sleep 200ms
        long parallelStart = System.nanoTime();
        List<String> taskIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            String taskId = "timing-" + i;
            taskRepo.putTask(taskId, () -> {
                try { Thread.sleep(sleepMs); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "done-" + idx;
            });
            taskIds.add(taskId);
        }
        for (String id : taskIds) {
            taskRepo.getTasks(id).waitForCompletion(5000);
        }
        long parallelMs = (System.nanoTime() - parallelStart) / 1_000_000;

        // Sequential baseline would be ~600ms (3 x 200ms)
        // Parallel should be ~200ms + overhead
        assertThat(parallelMs)
                .as("Parallel execution should be faster than sequential (3x%dms = %dms)",
                        sleepMs, sleepMs * 3)
                .isLessThan(sleepMs * 3);

        taskRepo.shutdown();
    }
}
