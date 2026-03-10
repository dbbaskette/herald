package com.herald.cron;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CronToolsTest {

    private CronService cronService;
    private CronTools cronTools;

    @BeforeEach
    void setUp() {
        cronService = mock(CronService.class);
        cronTools = new CronTools(cronService);
    }

    @Test
    void cronCreateCallsServiceAndReturnsConfirmation() {
        when(cronService.createJob("daily", "0 0 9 * * *", "Good morning"))
                .thenReturn(new CronJob(1, "daily", "0 0 9 * * *", "Good morning", null, true, false));

        String result = cronTools.cron_create("daily", "0 0 9 * * *", "Good morning");

        verify(cronService).createJob("daily", "0 0 9 * * *", "Good morning");
        assertThat(result).contains("Created").contains("daily").contains("0 0 9 * * *");
    }

    @Test
    void cronUpdateCallsServiceAndReturnsConfirmation() {
        when(cronService.updateJob("daily", "0 0 10 * * *", "Updated prompt"))
                .thenReturn(new CronJob(1, "daily", "0 0 10 * * *", "Updated prompt", null, true, false));

        String result = cronTools.cron_update("daily", "0 0 10 * * *", "Updated prompt");

        verify(cronService).updateJob("daily", "0 0 10 * * *", "Updated prompt");
        assertThat(result).contains("Updated").contains("daily");
    }

    @Test
    void cronDeleteWhenExists() {
        when(cronService.deleteJob("daily")).thenReturn(true);

        String result = cronTools.cron_delete("daily");

        verify(cronService).deleteJob("daily");
        assertThat(result).contains("Deleted").contains("daily");
    }

    @Test
    void cronDeleteWhenNotFound() {
        when(cronService.deleteJob("missing")).thenReturn(false);
        when(cronService.findJob("missing")).thenReturn(null);

        String result = cronTools.cron_delete("missing");

        assertThat(result).contains("No cron job found").contains("missing");
    }

    @Test
    void cronDeleteBuiltInJobReturnsProtectionMessage() {
        when(cronService.deleteJob("morning-briefing")).thenReturn(false);
        when(cronService.findJob("morning-briefing")).thenReturn(
                new CronJob(1, "morning-briefing", "0 7 * * 1-5", "prompt", null, true, true));

        String result = cronTools.cron_delete("morning-briefing");

        assertThat(result).contains("Cannot delete built-in").contains("morning-briefing");
    }

    @Test
    void cronListShowsFormattedJobs() {
        when(cronService.listJobs()).thenReturn(List.of(
                new CronJob(1, "morning", "0 0 9 * * *", "Brief me",
                        LocalDateTime.of(2026, 3, 9, 9, 0), true, true),
                new CronJob(2, "weekly", "0 0 17 * * FRI", "Weekly review",
                        null, false, false)));

        String result = cronTools.cron_list();

        assertThat(result).contains("morning").contains("enabled").contains("2026-03-09 09:00").contains("built-in");
        assertThat(result).contains("weekly").contains("disabled").contains("never").contains("custom");
    }

    @Test
    void cronListWhenEmpty() {
        when(cronService.listJobs()).thenReturn(List.of());

        String result = cronTools.cron_list();

        assertThat(result).contains("No cron jobs configured");
    }
}
