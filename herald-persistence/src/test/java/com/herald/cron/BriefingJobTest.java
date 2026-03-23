package com.herald.cron;

import com.herald.config.HeraldConfig;
import com.herald.memory.MemoryRepository;
import com.herald.tools.GwsAvailabilityChecker;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BriefingJobTest {

    private final HeraldConfig defaultConfig = new HeraldConfig(null, null, null, null, null, null, null, null, null);

    private BriefingJob createJob(HeraldConfig config, GwsAvailabilityChecker gwsChecker,
                                  MemoryRepository memRepo, boolean webSearch,
                                  BriefingJob.WeatherFetcher fetcher) {
        return new BriefingJob(config, gwsChecker, memRepo, webSearch, fetcher);
    }

    private MemoryRepository mockMemoryRepository() {
        return mock(MemoryRepository.class);
    }

    // --- buildMorningPrompt tests ---

    @Test
    void morningPromptIncludesDateAndDay() {
        GwsAvailabilityChecker gwsChecker = mock(GwsAvailabilityChecker.class);
        when(gwsChecker.isAvailable()).thenReturn(true);

        BriefingJob job = createJob(defaultConfig, gwsChecker, mockMemoryRepository(),
                false, url -> "");

        String result = job.buildMorningPrompt();

        LocalDate today = LocalDate.now();
        String expectedDay = today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        String expectedDate = today.format(DateTimeFormatter.ofPattern("MMMM d, yyyy"));

        assertThat(result).contains("Today is " + expectedDay);
        assertThat(result).contains(expectedDate);
    }

    @Test
    void morningPromptIncludesWeatherViaWebSearchWhenAvailable() {
        HeraldConfig config = new HeraldConfig(null, null, null, null, null,
                new HeraldConfig.Weather("London"), null, null, null);
        GwsAvailabilityChecker gwsChecker = mock(GwsAvailabilityChecker.class);
        when(gwsChecker.isAvailable()).thenReturn(true);
        MemoryRepository memRepo = mockMemoryRepository();

        BriefingJob job = createJob(config, gwsChecker, memRepo, true, url -> {
            throw new AssertionError("Should not pre-fetch weather when web search is available");
        });

        String result = job.buildMorningPrompt();

        assertThat(result).contains("Use web_search to find the current weather");
        assertThat(result).contains("London");
    }

    @Test
    void morningPromptFallsBackToWttrWhenNoWebSearch() {
        HeraldConfig config = new HeraldConfig(null, null, null, null, null,
                new HeraldConfig.Weather("London"), null, null, null);
        GwsAvailabilityChecker gwsChecker = mock(GwsAvailabilityChecker.class);
        when(gwsChecker.isAvailable()).thenReturn(true);
        MemoryRepository memRepo = mockMemoryRepository();

        BriefingJob job = createJob(config, gwsChecker, memRepo, false, url -> {
            assertThat(url).contains("London");
            return "London: +15°C";
        });

        String result = job.buildMorningPrompt();

        assertThat(result).contains("Current weather: London: +15°C");
        assertThat(result).doesNotContain("web_search");
    }

    @Test
    void morningPromptOmitsWeatherOnFetchFailure() {
        HeraldConfig config = new HeraldConfig(null, null, null, null, null,
                new HeraldConfig.Weather("London"), null, null, null);
        GwsAvailabilityChecker gwsChecker = mock(GwsAvailabilityChecker.class);
        when(gwsChecker.isAvailable()).thenReturn(true);

        BriefingJob job = createJob(config, gwsChecker, mockMemoryRepository(), false,
                url -> { throw new RuntimeException("network error"); });

        String result = job.buildMorningPrompt();

        assertThat(result).doesNotContain("Current weather");
    }

    @Test
    void morningPromptIncludesCalendarAndEmailWhenGwsAvailable() {
        GwsAvailabilityChecker gwsChecker = mock(GwsAvailabilityChecker.class);
        when(gwsChecker.isAvailable()).thenReturn(true);

        BriefingJob job = createJob(defaultConfig, gwsChecker, mockMemoryRepository(),
                false, url -> "");

        String result = job.buildMorningPrompt();

        assertThat(result).contains("calendar_events_list");
        assertThat(result).contains("gmail_search");
    }

    @Test
    void morningPromptOmitsCalendarAndEmailWhenGwsUnavailable() {
        GwsAvailabilityChecker gwsChecker = mock(GwsAvailabilityChecker.class);
        when(gwsChecker.isAvailable()).thenReturn(false);

        BriefingJob job = createJob(defaultConfig, gwsChecker, mockMemoryRepository(),
                false, url -> "");

        String result = job.buildMorningPrompt();

        assertThat(result).doesNotContain("calendar_events_list");
        assertThat(result).doesNotContain("gmail_search");
    }

    @Test
    void morningPromptAlwaysIncludesMemoryPriorities() {
        GwsAvailabilityChecker gwsChecker = mock(GwsAvailabilityChecker.class);
        when(gwsChecker.isAvailable()).thenReturn(false);

        BriefingJob job = createJob(defaultConfig, gwsChecker, mockMemoryRepository(),
                false, url -> "");

        String result = job.buildMorningPrompt();

        assertThat(result).contains("memory_list");
        assertThat(result).contains("Top Priorities");
    }

    @Test
    void morningPromptAlwaysIncludesAdaptiveSection() {
        GwsAvailabilityChecker gwsChecker = mock(GwsAvailabilityChecker.class);
        when(gwsChecker.isAvailable()).thenReturn(false);

        BriefingJob job = createJob(defaultConfig, gwsChecker, mockMemoryRepository(),
                false, url -> "");

        String result = job.buildMorningPrompt();

        assertThat(result).contains("Things You'd Want to Know Today");
    }

    @Test
    void morningPromptIncludesFormattingInstructions() {
        GwsAvailabilityChecker gwsChecker = mock(GwsAvailabilityChecker.class);
        when(gwsChecker.isAvailable()).thenReturn(true);

        BriefingJob job = createJob(defaultConfig, gwsChecker, mockMemoryRepository(),
                false, url -> "");

        String result = job.buildMorningPrompt();

        assertThat(result).contains("*bold headers*");
        assertThat(result).contains("bullet points");
    }

    // --- buildWeeklyPrompt tests ---

    @Test
    void weeklyPromptIncludesDateAndDay() {
        GwsAvailabilityChecker gwsChecker = mock(GwsAvailabilityChecker.class);
        when(gwsChecker.isAvailable()).thenReturn(true);

        BriefingJob job = createJob(defaultConfig, gwsChecker, mockMemoryRepository(),
                false, url -> "");

        String result = job.buildWeeklyPrompt();

        LocalDate today = LocalDate.now();
        String expectedDay = today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);

        assertThat(result).contains("Today is " + expectedDay);
        assertThat(result).contains("weekly review");
    }

    @Test
    void weeklyPromptIncludesNextWeekPreviewWhenGwsAvailable() {
        GwsAvailabilityChecker gwsChecker = mock(GwsAvailabilityChecker.class);
        when(gwsChecker.isAvailable()).thenReturn(true);

        BriefingJob job = createJob(defaultConfig, gwsChecker, mockMemoryRepository(),
                false, url -> "");

        String result = job.buildWeeklyPrompt();

        assertThat(result).contains("calendar_events_list");
        assertThat(result).contains("Next Week Preview");
    }

    @Test
    void weeklyPromptOmitsCalendarWhenGwsUnavailable() {
        GwsAvailabilityChecker gwsChecker = mock(GwsAvailabilityChecker.class);
        when(gwsChecker.isAvailable()).thenReturn(false);

        BriefingJob job = createJob(defaultConfig, gwsChecker, mockMemoryRepository(),
                false, url -> "");

        String result = job.buildWeeklyPrompt();

        assertThat(result).doesNotContain("calendar_events_list");
    }

    @Test
    void weeklyPromptAlwaysIncludesRecapAndOpenItems() {
        GwsAvailabilityChecker gwsChecker = mock(GwsAvailabilityChecker.class);
        when(gwsChecker.isAvailable()).thenReturn(false);

        BriefingJob job = createJob(defaultConfig, gwsChecker, mockMemoryRepository(),
                false, url -> "");

        String result = job.buildWeeklyPrompt();

        assertThat(result).contains("Week Recap");
        assertThat(result).contains("Open Items");
        assertThat(result).contains("Suggestions");
        assertThat(result).contains("memory_list");
    }

    // --- resolveCity tests ---

    @Test
    void resolveCityUsesMemoryFirst() {
        GwsAvailabilityChecker gwsChecker = mock(GwsAvailabilityChecker.class);
        MemoryRepository memRepo = mockMemoryRepository();
        when(memRepo.get("user.city")).thenReturn("San Francisco");

        HeraldConfig config = new HeraldConfig(null, null, null, null, null,
                new HeraldConfig.Weather("London"), null, null, null);

        BriefingJob job = createJob(config, gwsChecker, memRepo, true, url -> "");

        String result = job.buildMorningPrompt();

        assertThat(result).contains("San Francisco");
        assertThat(result).doesNotContain("London");
    }

    @Test
    void resolveCityFallsBackToConfig() {
        GwsAvailabilityChecker gwsChecker = mock(GwsAvailabilityChecker.class);
        MemoryRepository memRepo = mockMemoryRepository();
        when(memRepo.get("user.city")).thenReturn(null);

        HeraldConfig config = new HeraldConfig(null, null, null, null, null,
                new HeraldConfig.Weather("London"), null, null, null);

        BriefingJob job = createJob(config, gwsChecker, memRepo, true, url -> "");

        String result = job.buildMorningPrompt();

        assertThat(result).contains("London");
    }

    @Test
    void resolveCityNoLocationSkipsWeatherSection() {
        GwsAvailabilityChecker gwsChecker = mock(GwsAvailabilityChecker.class);
        MemoryRepository memRepo = mockMemoryRepository();
        when(memRepo.get("user.city")).thenReturn(null);

        BriefingJob job = createJob(defaultConfig, gwsChecker, memRepo, true,
                url -> { throw new AssertionError("Should not fetch weather without city"); });

        String result = job.buildMorningPrompt();

        assertThat(result).doesNotContain("Weather");
    }
}
