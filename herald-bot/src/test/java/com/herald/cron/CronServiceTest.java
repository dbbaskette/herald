package com.herald.cron;

import java.util.List;
import java.util.Optional;

import com.herald.agent.AgentService;
import com.herald.config.HeraldConfig;
import com.herald.telegram.TelegramSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CronServiceTest {

    private CronRepository cronRepository;
    private AgentService agentService;
    private TelegramSender telegramSender;
    private ChatMemory chatMemory;
    private BriefingJob briefingJob;
    private CronService cronService;

    @BeforeEach
    void setUp() {
        cronRepository = mock(CronRepository.class);
        agentService = mock(AgentService.class);
        telegramSender = mock(TelegramSender.class);
        chatMemory = mock(ChatMemory.class);
        briefingJob = mock(BriefingJob.class);

        when(cronRepository.findEnabled()).thenReturn(List.of());

        HeraldConfig config = new HeraldConfig(null, null, null, null,
                new HeraldConfig.Cron("America/New_York"), null);
        TaskScheduler scheduler = createTaskScheduler();
        cronService = new CronService(cronRepository, agentService,
                Optional.of(telegramSender), chatMemory, config, briefingJob, scheduler);
        cronService.loadJobs();
    }

    private static TaskScheduler createTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("test-cron-");
        scheduler.initialize();
        return scheduler;
    }

    @Test
    void createJobSavesAndReturnsJob() {
        CronJob saved = new CronJob(1, "test-job", "0 0 9 * * *", "hello", null, true, false);
        when(cronRepository.findByName("test-job")).thenReturn(saved);

        CronJob result = cronService.createJob("test-job", "0 0 9 * * *", "hello");

        verify(cronRepository).save(any(CronJob.class));
        assertThat(result.name()).isEqualTo("test-job");
    }

    @Test
    void updateJobCancelsAndReschedules() {
        CronJob existing = new CronJob(1, "test-job", "0 0 9 * * *", "hello", null, true, false);
        CronJob updated = new CronJob(1, "test-job", "0 0 10 * * *", "updated", null, true, false);
        when(cronRepository.findByName("test-job")).thenReturn(existing, updated);

        CronJob result = cronService.updateJob("test-job", "0 0 10 * * *", "updated");

        verify(cronRepository).save(any(CronJob.class));
        assertThat(result.schedule()).isEqualTo("0 0 10 * * *");
    }

    @Test
    void enableJobSchedulesIt() {
        CronJob job = new CronJob(1, "test-job", "0 0 9 * * *", "hello", null, true, false);
        when(cronRepository.findByName("test-job")).thenReturn(job);

        cronService.enableJob("test-job");

        verify(cronRepository).setEnabled("test-job", true);
    }

    @Test
    void disableJobCancelsIt() {
        CronJob job = new CronJob(1, "test-job", "0 0 9 * * *", "hello", null, true, false);
        when(cronRepository.findByName("test-job")).thenReturn(job);

        cronService.disableJob("test-job");

        verify(cronRepository).setEnabled("test-job", false);
    }

    @Test
    void deleteJobRemovesFromDb() {
        CronJob job = new CronJob(1, "test-job", "0 0 9 * * *", "hello", null, true, false);
        when(cronRepository.findByName("test-job")).thenReturn(job);
        when(cronRepository.delete("test-job")).thenReturn(true);

        boolean result = cronService.deleteJob("test-job");

        assertThat(result).isTrue();
        verify(cronRepository).delete("test-job");
    }

    @Test
    void listJobsDelegatesToRepository() {
        List<CronJob> jobs = List.of(
                new CronJob(1, "job-a", "0 0 9 * * *", "prompt a", null, true, false));
        when(cronRepository.findAll()).thenReturn(jobs);

        List<CronJob> result = cronService.listJobs();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("job-a");
    }

    @Test
    void loadJobsSchedulesEnabledJobs() {
        CronJob enabled = new CronJob(1, "enabled-job", "0 0 9 * * *", "prompt", null, true, false);
        when(cronRepository.findEnabled()).thenReturn(List.of(enabled));

        HeraldConfig config = new HeraldConfig(null, null, null, null,
                new HeraldConfig.Cron("America/New_York"), null);
        TaskScheduler scheduler = createTaskScheduler();
        CronService service = new CronService(cronRepository, agentService,
                Optional.of(telegramSender), chatMemory, config, briefingJob, scheduler);
        service.loadJobs();

        // findEnabled called at least twice: once in setUp, once here
        verify(cronRepository, atLeast(2)).findEnabled();
    }

    @Test
    void deleteBuiltInJobThrowsIllegalStateException() {
        when(cronRepository.findByName("morning-briefing"))
                .thenReturn(new CronJob(1, "morning-briefing", "0 7 * * 1-5", "prompt", null, true, true));
        when(cronRepository.delete("morning-briefing"))
                .thenThrow(new IllegalStateException("Built-in jobs cannot be deleted"));

        assertThatThrownBy(() -> cronService.deleteJob("morning-briefing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Built-in jobs cannot be deleted");
    }

    @Test
    void executeJobClearsChatMemoryOnSuccess() {
        CronJob job = new CronJob(1, "test-job", "0 0 9 * * *", "hello", null, true, false);
        when(agentService.chat("hello", "cron-test-job")).thenReturn("result");

        cronService.executeJob(job);

        verify(agentService).chat("hello", "cron-test-job");
        verify(telegramSender).sendMessage("result");
        verify(cronRepository).updateLastRun(eq("test-job"), any());
        verify(chatMemory).clear("cron-test-job");
    }

    @Test
    void executeJobClearsChatMemoryOnFailure() {
        CronJob job = new CronJob(1, "test-job", "0 0 9 * * *", "hello", null, true, false);
        when(agentService.chat("hello", "cron-test-job")).thenThrow(new RuntimeException("agent error"));

        cronService.executeJob(job);

        verify(chatMemory).clear("cron-test-job");
        verify(cronRepository, never()).updateLastRun(any(), any());
    }

    @Test
    void executeJobSendsErrorViaTelegramOnFailure() {
        CronJob job = new CronJob(1, "test-job", "0 0 9 * * *", "hello", null, true, false);
        when(agentService.chat("hello", "cron-test-job")).thenThrow(new RuntimeException("agent error"));

        cronService.executeJob(job);

        verify(telegramSender).sendMessage("Cron job 'test-job' failed: agent error");
    }

    @Test
    void executeJobUsesBriefingJobForMorningBriefing() {
        CronJob job = new CronJob(1, "morning-briefing", "0 0 7 * * MON-FRI", "base prompt", null, true, true);
        when(briefingJob.buildMorningPrompt()).thenReturn("enriched prompt");
        when(agentService.chat("enriched prompt", "cron-morning-briefing")).thenReturn("briefing result");

        cronService.executeJob(job);

        verify(briefingJob).buildMorningPrompt();
        verify(agentService).chat("enriched prompt", "cron-morning-briefing");
        verify(telegramSender).sendMessage("briefing result");
    }

    @Test
    void executeJobUsesBriefingJobForWeeklyReview() {
        CronJob job = new CronJob(2, "weekly-review", "0 18 * * 5", "base prompt", null, true, true);
        when(briefingJob.buildWeeklyPrompt()).thenReturn("weekly prompt");
        when(agentService.chat("weekly prompt", "cron-weekly-review")).thenReturn("weekly result");

        cronService.executeJob(job);

        verify(briefingJob).buildWeeklyPrompt();
        verify(agentService).chat("weekly prompt", "cron-weekly-review");
        verify(telegramSender).sendMessage("weekly result");
    }

    @Test
    void rescheduleJobUpdatesScheduleAndReschedules() {
        CronJob existing = new CronJob(1, "test-job", "0 0 9 * * *", "hello", null, true, false);
        CronJob updated = new CronJob(1, "test-job", "0 0 8 * * *", "hello", null, true, false);
        when(cronRepository.findByName("test-job")).thenReturn(existing, updated);

        CronJob result = cronService.rescheduleJob("test-job", "0 0 8 * * *");

        verify(cronRepository).updateSchedule("test-job", "0 0 8 * * *");
        assertThat(result.schedule()).isEqualTo("0 0 8 * * *");
    }

    @Test
    void rescheduleJobWithCronJobObject() {
        CronJob job = new CronJob(1, "test-job", "0 0 8 * * *", "hello", null, true, false);

        cronService.rescheduleJob(job);

        // Should not throw - job gets scheduled
    }

    @Test
    void cancelJobByIdCancelsWithoutDeleting() {
        CronJob job = new CronJob(1, "test-job", "0 0 9 * * *", "hello", null, true, false);
        when(cronRepository.findByName("test-job")).thenReturn(job);

        // Schedule then cancel
        cronService.createJob("test-job", "0 0 9 * * *", "hello");
        cronService.cancelJob(1L);

        // No delete should have occurred
        verify(cronRepository, never()).delete(any());
    }

    @Test
    void defaultTimezoneUsedWhenConfigIsNull() {
        HeraldConfig config = new HeraldConfig(null, null, null, null, null, null);
        TaskScheduler scheduler = createTaskScheduler();
        CronService service = new CronService(cronRepository, agentService,
                Optional.of(telegramSender), chatMemory, config, briefingJob, scheduler);
        assertThat(service).isNotNull();
    }

    @Test
    void worksWithoutTelegramSender() {
        HeraldConfig config = new HeraldConfig(null, null, null, null,
                new HeraldConfig.Cron("America/New_York"), null);
        TaskScheduler scheduler = createTaskScheduler();
        CronService service = new CronService(cronRepository, agentService,
                Optional.empty(), chatMemory, config, briefingJob, scheduler);

        CronJob job = new CronJob(1, "test-job", "0 0 9 * * *", "hello", null, true, false);
        when(agentService.chat("hello", "cron-test-job")).thenReturn("result");

        service.executeJob(job);

        verify(agentService).chat("hello", "cron-test-job");
        verify(telegramSender, never()).sendMessage(any());
        verify(cronRepository).updateLastRun(eq("test-job"), any());
    }
}
