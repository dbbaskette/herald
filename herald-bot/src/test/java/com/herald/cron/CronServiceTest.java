package com.herald.cron;

import java.util.List;

import com.herald.agent.AgentService;
import com.herald.config.HeraldConfig;
import com.herald.telegram.TelegramSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;

import static org.assertj.core.api.Assertions.assertThat;
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

        when(cronRepository.findAll()).thenReturn(List.of());

        HeraldConfig config = new HeraldConfig(null, null, null, null,
                new HeraldConfig.Cron("America/New_York"), null);
        cronService = new CronService(cronRepository, agentService, telegramSender, chatMemory, config, briefingJob);
        cronService.loadJobs();
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
        CronJob updated = new CronJob(1, "test-job", "0 0 10 * * *", "updated", null, true, false);
        when(cronRepository.findByName("test-job")).thenReturn(updated);

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
        cronService.disableJob("test-job");

        verify(cronRepository).setEnabled("test-job", false);
    }

    @Test
    void deleteJobRemovesFromDb() {
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
        CronJob disabled = new CronJob(2, "disabled-job", "0 0 10 * * *", "prompt", null, false, false);
        when(cronRepository.findAll()).thenReturn(List.of(enabled, disabled));

        HeraldConfig config = new HeraldConfig(null, null, null, null,
                new HeraldConfig.Cron("America/New_York"), null);
        CronService service = new CronService(cronRepository, agentService, telegramSender, chatMemory, config, briefingJob);
        service.loadJobs();

        // findAll called at least twice: once in setUp, once here
        verify(cronRepository, atLeast(2)).findAll();
    }

    @Test
    void deleteBuiltInJobReturnsFalseAndKeepsSchedule() {
        CronJob builtIn = new CronJob(1, "morning-briefing", "0 0 7 * * MON-FRI", "prompt", null, true, true);
        when(cronRepository.findAll()).thenReturn(List.of(builtIn));
        when(cronRepository.findByName("morning-briefing")).thenReturn(builtIn);

        HeraldConfig config = new HeraldConfig(null, null, null, null,
                new HeraldConfig.Cron("America/New_York"), null);
        CronService service = new CronService(cronRepository, agentService, telegramSender, chatMemory, config, briefingJob);
        service.loadJobs();

        when(cronRepository.delete("morning-briefing")).thenReturn(false);

        boolean result = service.deleteJob("morning-briefing");

        assertThat(result).isFalse();
        // Built-in job still exists in the repository
        assertThat(service.findJob("morning-briefing")).isNotNull();
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
        verify(telegramSender, never()).sendMessage(any());
        verify(cronRepository, never()).updateLastRun(any(), any());
    }

    @Test
    void executeJobUsesBriefingJobForMorningBriefing() {
        CronJob job = new CronJob(1, "morning-briefing", "0 0 7 * * MON-FRI", "base prompt", null, true, true);
        when(briefingJob.buildPrompt("base prompt")).thenReturn("enriched prompt");
        when(agentService.chat("enriched prompt", "cron-morning-briefing")).thenReturn("briefing result");

        cronService.executeJob(job);

        verify(briefingJob).buildPrompt("base prompt");
        verify(agentService).chat("enriched prompt", "cron-morning-briefing");
        verify(telegramSender).sendMessage("briefing result");
    }

    @Test
    void rescheduleJobUpdatesScheduleAndReschedules() {
        CronJob updated = new CronJob(1, "test-job", "0 0 8 * * *", "hello", null, true, false);
        when(cronRepository.findByName("test-job")).thenReturn(updated);

        CronJob result = cronService.rescheduleJob("test-job", "0 0 8 * * *");

        verify(cronRepository).updateSchedule("test-job", "0 0 8 * * *");
        assertThat(result.schedule()).isEqualTo("0 0 8 * * *");
    }

    @Test
    void defaultTimezoneUsedWhenConfigIsNull() {
        HeraldConfig config = new HeraldConfig(null, null, null, null, null, null);
        CronService service = new CronService(cronRepository, agentService, telegramSender, chatMemory, config, briefingJob);
        assertThat(service).isNotNull();
    }
}
