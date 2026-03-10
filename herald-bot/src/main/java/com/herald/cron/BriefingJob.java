package com.herald.cron;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.herald.config.HeraldConfig;
import com.herald.memory.MemoryRepository;
import com.herald.tools.GwsAvailabilityChecker;

@Component
public class BriefingJob {

    private static final Logger log = LoggerFactory.getLogger(BriefingJob.class);
    private static final int TIMEOUT_SECONDS = 10;
    static final String MORNING_BRIEFING_NAME = "morning-briefing";
    static final String WEEKLY_REVIEW_NAME = "weekly-review";

    @FunctionalInterface
    interface WeatherFetcher {
        String fetch(String url) throws Exception;
    }

    private final HeraldConfig config;
    private final GwsAvailabilityChecker gwsChecker;
    private final MemoryRepository memoryRepository;
    private final boolean webSearchAvailable;
    private final WeatherFetcher weatherFetcher;

    public BriefingJob(HeraldConfig config, GwsAvailabilityChecker gwsChecker,
                       MemoryRepository memoryRepository,
                       @Value("${herald.web.search-api-key:}") String webSearchApiKey) {
        this(config, gwsChecker, memoryRepository,
                webSearchApiKey != null && !webSearchApiKey.isBlank(),
                BriefingJob::fetchWeatherHttp);
    }

    BriefingJob(HeraldConfig config, GwsAvailabilityChecker gwsChecker,
                MemoryRepository memoryRepository, boolean webSearchAvailable,
                WeatherFetcher weatherFetcher) {
        this.config = config;
        this.gwsChecker = gwsChecker;
        this.memoryRepository = memoryRepository;
        this.webSearchAvailable = webSearchAvailable;
        this.weatherFetcher = weatherFetcher;
    }

    public String buildMorningPrompt() {
        var sb = new StringBuilder();

        sb.append("You are running as a scheduled morning briefing. ");
        sb.append("Compile a concise morning digest for the user.\n\n");

        appendDateHeader(sb);

        String city = resolveCity();

        // Weather section — only when data source is available
        if (webSearchAvailable && !city.isEmpty()) {
            sb.append("## Section 1 — Weather\n");
            sb.append("Use web_search to find the current weather and today's forecast for ")
                    .append(city).append(".\n\n");
        } else if (!city.isEmpty()) {
            String weather = fetchWeather(city);
            if (!weather.isEmpty()) {
                sb.append("## Section 1 — Weather\n");
                sb.append("Current weather: ").append(weather).append("\n\n");
            }
        }

        // Calendar section — only when GWS available
        if (gwsChecker.isAvailable()) {
            sb.append("## Section 2 — Calendar\n");
            sb.append("Use calendar_events_list to list today's events and meetings with times.\n\n");
        }

        // Memory priorities — always included
        sb.append("## Section 3 — Top Priorities\n");
        sb.append("Use memory_list to surface the top 3 priorities or open items from memory.\n\n");

        // Flagged emails — only when GWS available
        if (gwsChecker.isAvailable()) {
            sb.append("## Section 4 — Flagged Emails\n");
            sb.append("Use gmail_search to check for flagged or important unread emails and summarize them.\n\n");
        }

        // Adaptive section — always included
        sb.append("## Section 5 — Things You'd Want to Know Today\n");
        sb.append("Add an adaptive section with anything else relevant: upcoming deadlines, ")
                .append("reminders, or notable context from recent conversations.\n\n");

        appendFormattingInstructions(sb);
        return sb.toString();
    }

    public String buildWeeklyPrompt() {
        var sb = new StringBuilder();

        sb.append("You are running as a scheduled weekly review. ");
        sb.append("Compile a concise end-of-week summary for the user.\n\n");

        appendDateHeader(sb);

        // Week recap — always included (uses memory)
        sb.append("## Section 1 — Week Recap\n");
        sb.append("Use memory_list to review stored facts and summarize the key activity, ")
                .append("tasks, and conversations from this week.\n\n");

        // Open items — always included
        sb.append("## Section 2 — Open Items\n");
        sb.append("Surface any unresolved tasks, pending questions, or open items from memory.\n\n");

        // Next week preview — only when GWS available
        if (gwsChecker.isAvailable()) {
            sb.append("## Section 3 — Next Week Preview\n");
            sb.append("Use calendar_events_list to preview next week's scheduled events and commitments.\n\n");
        }

        // Suggestions — always included
        sb.append("## Section 4 — Suggestions\n");
        sb.append("Offer recommendations for follow-ups or preparation for the coming week.\n\n");

        appendFormattingInstructions(sb);
        return sb.toString();
    }

    private void appendDateHeader(StringBuilder sb) {
        LocalDate today = LocalDate.now();
        String dayOfWeek = today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        sb.append("Today is ").append(dayOfWeek).append(", ")
                .append(today.format(DateTimeFormatter.ofPattern("MMMM d, yyyy")))
                .append(".\n\n");
    }

    private void appendFormattingInstructions(StringBuilder sb) {
        sb.append("Format your response using *bold headers* for each section and bullet points ");
        sb.append("for readability. Keep the tone friendly and concise.");
    }

    String resolveCity() {
        String city = memoryRepository.get("user.city");
        if (city != null && !city.isBlank()) {
            return city;
        }
        return config.weatherLocation();
    }

    private String fetchWeather(String city) {
        try {
            String url = "https://wttr.in/" + (city.isEmpty() ? "" : city) + "?format=3";
            String result = weatherFetcher.fetch(url);
            if (result != null && !result.isBlank()) {
                return result.trim();
            }
        } catch (Exception e) {
            log.warn("Failed to fetch weather for briefing: {}", e.getMessage());
        }
        return "";
    }

    private static String fetchWeatherHttp(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("User-Agent", "Herald-Bot/1.0")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 200 && response.statusCode() < 400) {
            return response.body();
        }
        return "";
    }
}
