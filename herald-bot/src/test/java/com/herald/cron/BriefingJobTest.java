package com.herald.cron;

import com.herald.config.HeraldConfig;
import com.herald.tools.GwsAvailabilityChecker;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BriefingJobTest {

    @Test
    void buildPromptIncludesDateAndDay() {
        HeraldConfig config = new HeraldConfig(null, null, null, null, null, null);
        GwsAvailabilityChecker gwsChecker = mock(GwsAvailabilityChecker.class);
        when(gwsChecker.isAvailable()).thenReturn(true);

        BriefingJob briefingJob = new BriefingJob(config, gwsChecker, url -> "London: +15°C");

        String result = briefingJob.buildPrompt("base prompt");

        LocalDate today = LocalDate.now();
        String expectedDay = today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        String expectedDate = today.format(DateTimeFormatter.ofPattern("MMMM d, yyyy"));

        assertThat(result).contains("Today is " + expectedDay);
        assertThat(result).contains(expectedDate);
        assertThat(result).contains("base prompt");
    }

    @Test
    void buildPromptIncludesWeather() {
        HeraldConfig config = new HeraldConfig(null, null, null, null, null,
                new HeraldConfig.Weather("London"));
        GwsAvailabilityChecker gwsChecker = mock(GwsAvailabilityChecker.class);
        when(gwsChecker.isAvailable()).thenReturn(true);

        BriefingJob briefingJob = new BriefingJob(config, gwsChecker, url -> {
            assertThat(url).contains("London");
            return "London: +15°C";
        });

        String result = briefingJob.buildPrompt("base prompt");

        assertThat(result).contains("Current weather: London: +15°C");
    }

    @Test
    void buildPromptOmitsWeatherOnFailure() {
        HeraldConfig config = new HeraldConfig(null, null, null, null, null, null);
        GwsAvailabilityChecker gwsChecker = mock(GwsAvailabilityChecker.class);
        when(gwsChecker.isAvailable()).thenReturn(true);

        BriefingJob briefingJob = new BriefingJob(config, gwsChecker, url -> {
            throw new RuntimeException("network error");
        });

        String result = briefingJob.buildPrompt("base prompt");

        assertThat(result).doesNotContain("Current weather");
        assertThat(result).contains("base prompt");
    }

    @Test
    void buildPromptIncludesGwsUnavailableNote() {
        HeraldConfig config = new HeraldConfig(null, null, null, null, null, null);
        GwsAvailabilityChecker gwsChecker = mock(GwsAvailabilityChecker.class);
        when(gwsChecker.isAvailable()).thenReturn(false);

        BriefingJob briefingJob = new BriefingJob(config, gwsChecker, url -> "");

        String result = briefingJob.buildPrompt("base prompt");

        assertThat(result).contains("Google Workspace CLI (gws) is not available");
        assertThat(result).contains("Calendar and email sections should be omitted");
    }

    @Test
    void buildPromptOmitsGwsNoteWhenAvailable() {
        HeraldConfig config = new HeraldConfig(null, null, null, null, null, null);
        GwsAvailabilityChecker gwsChecker = mock(GwsAvailabilityChecker.class);
        when(gwsChecker.isAvailable()).thenReturn(true);

        BriefingJob briefingJob = new BriefingJob(config, gwsChecker, url -> "");

        String result = briefingJob.buildPrompt("base prompt");

        assertThat(result).doesNotContain("gws");
        assertThat(result).contains("base prompt");
    }

    @Test
    void buildPromptUsesDefaultLocationWhenNotConfigured() {
        HeraldConfig config = new HeraldConfig(null, null, null, null, null, null);
        GwsAvailabilityChecker gwsChecker = mock(GwsAvailabilityChecker.class);
        when(gwsChecker.isAvailable()).thenReturn(true);

        BriefingJob briefingJob = new BriefingJob(config, gwsChecker, url -> {
            assertThat(url).isEqualTo("https://wttr.in/?format=3");
            return "Somewhere: +20°C";
        });

        briefingJob.buildPrompt("test");
    }

    @Test
    void buildPromptUsesConfiguredLocation() {
        HeraldConfig config = new HeraldConfig(null, null, null, null, null,
                new HeraldConfig.Weather("New+York"));
        GwsAvailabilityChecker gwsChecker = mock(GwsAvailabilityChecker.class);
        when(gwsChecker.isAvailable()).thenReturn(true);

        BriefingJob briefingJob = new BriefingJob(config, gwsChecker, url -> {
            assertThat(url).isEqualTo("https://wttr.in/New+York?format=3");
            return "New York: +10°C";
        });

        briefingJob.buildPrompt("test");
    }
}
