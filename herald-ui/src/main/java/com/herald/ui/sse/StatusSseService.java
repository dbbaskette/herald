package com.herald.ui.sse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class StatusSseService {

    private static final Logger log = LoggerFactory.getLogger(StatusSseService.class);

    private final JdbcTemplate jdbcTemplate;
    private final String botHealthUrl;
    private final HttpClient httpClient;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final List<Path> skillsDirs;
    private Instant botStartTime;

    public StatusSseService(JdbcTemplate jdbcTemplate,
                            @Value("${herald.ui.bot-port:8081}") int botPort,
                            @Value("${herald.ui.skills-path:~/.herald/skills}") String skillsPath,
                            @Value("${herald.ui.bundled-skills-path:}") String bundledSkillsPath) {
        this.jdbcTemplate = jdbcTemplate;
        this.botHealthUrl = "http://localhost:" + botPort + "/actuator/health";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.skillsDirs = buildSkillsDirs(skillsPath, bundledSkillsPath);
    }

    private static List<Path> buildSkillsDirs(String skillsPath, String bundledSkillsPath) {
        var dirs = new java.util.ArrayList<Path>();
        dirs.add(resolvePath(skillsPath));
        if (bundledSkillsPath != null && !bundledSkillsPath.isBlank()) {
            dirs.add(resolvePath(bundledSkillsPath));
        }
        return List.copyOf(dirs);
    }

    private static Path resolvePath(String path) {
        if (path.startsWith("~")) {
            path = System.getProperty("user.home") + path.substring(1);
        }
        return Path.of(path);
    }

    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    @Scheduled(fixedDelay = 5000)
    void pushStatus() {
        if (emitters.isEmpty()) {
            return;
        }

        Map<String, Object> status = buildStatus();

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("status").data(status));
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        }
    }

    public Map<String, Object> buildStatus() {
        Integer messageCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM messages", Integer.class);
        Integer pendingCommandCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM commands WHERE status = 'pending'", Integer.class);
        Integer memoryCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM memory", Integer.class);
        Integer cronCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cron_jobs", Integer.class);

        boolean botRunning = checkBotHealth();

        if (botRunning && botStartTime == null) {
            botStartTime = Instant.now();
        } else if (!botRunning) {
            botStartTime = null;
        }

        Map<String, Object> bot = new HashMap<>();
        bot.put("running", botRunning);
        bot.put("pid", null);
        bot.put("uptime", botStartTime != null ? formatUptime(botStartTime) : "—");
        bot.put("restartCount", 0);

        Map<String, Object> memory = new HashMap<>();
        memory.put("entryCount", memoryCount != null ? memoryCount : 0);
        memory.put("databaseFileSize", "—");

        Map<String, Object> model = new HashMap<>();
        model.put("name", "claude-sonnet-4-5");
        model.put("requestsToday", messageCount != null ? messageCount : 0);
        model.put("estimatedTokenSpend", "—");

        Map<String, Object> skills = new HashMap<>();
        skills.put("totalLoaded", countSkills());
        skills.put("lastReload", null);
        skills.put("parseErrors", List.of());

        Map<String, Object> result = new HashMap<>();
        result.put("healthy", botRunning);
        result.put("bot", bot);
        result.put("model", model);
        result.put("mcp", List.of());
        result.put("skills", skills);
        result.put("memory", memory);
        result.put("cron", List.of());
        result.put("recentActivity", List.of());
        result.put("messageCount", messageCount != null ? messageCount : 0);
        result.put("pendingCommandCount", pendingCommandCount != null ? pendingCommandCount : 0);
        result.put("cronCount", cronCount != null ? cronCount : 0);
        result.put("timestamp", Instant.now().toString());

        return result;
    }

    private int countSkills() {
        int count = 0;
        for (Path dir : skillsDirs) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> entries = Files.list(dir)) {
                count += (int) entries
                        .filter(Files::isDirectory)
                        .filter(d -> Files.exists(d.resolve("SKILL.md")))
                        .count();
            } catch (IOException e) {
                // skip unreadable dirs
            }
        }
        return count;
    }

    private boolean checkBotHealth() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(botHealthUrl))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private String formatUptime(Instant start) {
        Duration d = Duration.between(start, Instant.now());
        long hours = d.toHours();
        long mins = d.toMinutesPart();
        if (hours > 0) {
            return hours + "h " + mins + "m";
        }
        long secs = d.toSecondsPart();
        if (mins > 0) {
            return mins + "m " + secs + "s";
        }
        return secs + "s";
    }
}
