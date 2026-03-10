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
import org.springframework.stereotype.Component;

import com.herald.config.HeraldConfig;
import com.herald.tools.GwsAvailabilityChecker;

@Component
public class BriefingJob {

    private static final Logger log = LoggerFactory.getLogger(BriefingJob.class);
    private static final int TIMEOUT_SECONDS = 10;
    static final String MORNING_BRIEFING_NAME = "morning-briefing";

    @FunctionalInterface
    interface WeatherFetcher {
        String fetch(String url) throws Exception;
    }

    private final HeraldConfig config;
    private final GwsAvailabilityChecker gwsChecker;
    private final WeatherFetcher weatherFetcher;

    public BriefingJob(HeraldConfig config, GwsAvailabilityChecker gwsChecker) {
        this(config, gwsChecker, BriefingJob::fetchWeatherHttp);
    }

    BriefingJob(HeraldConfig config, GwsAvailabilityChecker gwsChecker, WeatherFetcher weatherFetcher) {
        this.config = config;
        this.gwsChecker = gwsChecker;
        this.weatherFetcher = weatherFetcher;
    }

    public String buildPrompt(String basePrompt) {
        var sb = new StringBuilder();

        LocalDate today = LocalDate.now();
        String dayOfWeek = today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        sb.append("Today is ").append(dayOfWeek).append(", ")
                .append(today.format(DateTimeFormatter.ofPattern("MMMM d, yyyy")))
                .append(".\n\n");

        String weather = fetchWeather();
        if (!weather.isEmpty()) {
            sb.append("Current weather: ").append(weather).append("\n\n");
        }

        if (!gwsChecker.isAvailable()) {
            sb.append("Note: Google Workspace CLI (gws) is not available. ")
                    .append("Calendar and email sections should be omitted.\n\n");
        }

        sb.append(basePrompt);
        return sb.toString();
    }

    private String fetchWeather() {
        try {
            String location = config.weatherLocation();
            String url = "https://wttr.in/" + (location.isEmpty() ? "" : location) + "?format=3";
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
