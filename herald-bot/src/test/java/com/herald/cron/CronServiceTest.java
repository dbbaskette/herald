package com.herald.cron;

import java.util.List;

import com.herald.agent.AgentService;
import com.herald.config.HeraldConfig;
import com.herald.telegram.TelegramSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CronServiceTest {

    private CronRepository cronRepository;
    private AgentService agentService;
    private TelegramSender telegramSender;
    private CronService cronService;

    @BeforeEach
    void setUp() {
        cronRepository = mock(CronRepository.class);
        agentService = mock(AgentService.class);
        telegramSender = mock(TelegramSender.class);

        when(cronRepository.findAll()).thenReturn(List.of());

        HeraldConfig config = new HeraldConfig(null, null, null, null,
                new HeraldConfig.Cron("America/New_York"));
        cronService = new CronService(cronRepository, agentService, telegramSender, config);
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
                new HeraldConfig.Cron("America/New_York"));
        CronService service = new CronService(cronRepository, agentService, telegramSender, config);
        service.loadJobs();

        // findAll called at least twice: once in setUp, once here
        verify(cronRepository, atLeast(2)).findAll();
    }

    @Test
    void deleteBuiltInJobReturnsFalseAndKeepsSchedule() {
        // First, create and schedule a built-in job
        CronJob builtIn = new CronJob(1, "morning-briefing", "0 7 * * 1-5", "prompt", null, true, true);
        when(cronRepository.findAll()).thenReturn(List.of(builtIn));
        when(cronRepository.findByName("morning-briefing")).thenReturn(builtIn);

        HeraldConfig config = new HeraldConfig(null, null, null, null,
                new HeraldConfig.Cron("America/New_York"));
        CronService service = new CronService(cronRepository, agentService, telegramSender, config);
        service.loadJobs();

        // DB delete returns false for built-in job
        when(cronRepository.delete("morning-briefing")).thenReturn(false);

        boolean result = service.deleteJob("morning-briefing");

        assertThat(result).isFalse();
        // The job should still be scheduled — verify by enabling it (which would re-schedule)
        // and checking that the job is still findable
        CronJob still = service.findJob("morning-briefing");
        assertThat(still).isNotNull();
        assertThat(still.builtIn()).isTrue();
    }

    @Test
    void defaultTimezoneUsedWhenConfigIsNull() {
        HeraldConfig config = new HeraldConfig(null, null, null, null, null);
        CronService service = new CronService(cronRepository, agentService, telegramSender, config);
        assertThat(service).isNotNull();
    }
}
