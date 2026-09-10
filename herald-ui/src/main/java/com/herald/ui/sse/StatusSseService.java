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
            } catch (Exception e) {
                // Client disconnected (broken pipe, illegal state, etc.) — remove silently
                emitters.remove(emitter);
            }
        }
    }

    public void publishSkillReload(String timestamp) {
        Map<String, String> payload = Map.of("timestamp", timestamp);
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("skill-reload").data(payload));
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        }
    }

    public Map<String, Object> buildStatus() {
        Integer messageCount = safeCount("SELECT COUNT(*) FROM messages");
        Integer pendingCommandCount = safeCount("SELECT COUNT(*) FROM commands WHERE status = 'pending'");
        Integer memoryCount = safeCount("SELECT COUNT(*) FROM memory");
        Integer cronCount = safeCount("SELECT COUNT(*) FROM cron_jobs");
        Integer skillCount = countSkills();
        List<Map<String, Object>> cron = cronSnapshot();

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
        model.put("name", getActiveModelName());
        model.put("requestsToday", messageCount != null ? messageCount : 0);
        model.put("estimatedTokenSpend", "—");

        Map<String, Object> skills = new HashMap<>();
        skills.put("totalLoaded", skillCount != null ? skillCount : 0);
        skills.put("lastReload", null);
        skills.put("parseErrors", List.of());

        Map<String, Object> result = new HashMap<>();
        result.put("healthy", botRunning);
        result.put("bot", bot);
        result.put("model", model);
        result.put("mcp", List.of());
        result.put("skills", skills);
        result.put("memory", memory);
        result.put("cron", cron != null ? cron : List.of());
        result.put("capabilities", Map.of(
                "mcp", Map.of("state", "unknown", "message", "MCP connection telemetry is unavailable in this console."),
                "memory", capability(memoryCount != null, "Memory database could not be read."),
                "skills", capability(skillCount != null, "One or more skill directories could not be read."),
                "cron", capability(cron != null, "Cron jobs could not be read.")));
        result.put("recentActivity", List.of());
        result.put("messageCount", messageCount != null ? messageCount : 0);
        result.put("pendingCommandCount", pendingCommandCount != null ? pendingCommandCount : 0);
        result.put("cronCount", cronCount != null ? cronCount : 0);
        result.put("timestamp", Instant.now().toString());

        return result;
    }

    private static Map<String, String> capability(boolean available, String failure) {
        return Map.of("state", available ? "available" : "failed", "message", available ? "" : failure);
    }

    private List<Map<String, Object>> cronSnapshot() {
        try {
            return jdbcTemplate.queryForList("SELECT c.name, c.last_run, e.status AS last_result FROM cron_jobs c LEFT JOIN cron_execution e ON e.job_id = c.id ORDER BY c.name").stream().map(row -> {
                Map<String, Object> job = new HashMap<>();
                job.put("name", row.get("name"));
                job.put("lastRun", row.get("last_run"));
                job.put("nextRun", null);
                job.put("lastResult", row.get("last_result"));
                return job;
            }).toList();
        } catch (Exception e) {
            return null;
        }
    }

    private String getActiveModelName() {
        try {
            var rows = jdbcTemplate.queryForList(
                    "SELECT provider, model FROM model_overrides ORDER BY updated_at DESC LIMIT 1");
            if (!rows.isEmpty()) {
                return rows.get(0).get("provider") + "/" + rows.get(0).get("model");
            }
        } catch (Exception e) {
            // table may not exist yet
        }
        return "anthropic/claude-sonnet-4-5";
    }

    private Integer countSkills() {
        // Deduplicate across directories — local skills shadow bundled ones with the same name
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Path dir : skillsDirs) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> entries = Files.list(dir)) {
                entries.filter(Files::isDirectory)
                        .filter(d -> Files.exists(d.resolve("SKILL.md")))
                        .forEach(d -> seen.add(d.getFileName().toString()));
            } catch (IOException e) {
                return null;
            }
        }
        return seen.size();
    }

    private Integer safeCount(String sql) {
        try {
            return jdbcTemplate.queryForObject(sql, Integer.class);
        } catch (Exception e) {
            // Unknown is not an observed zero.
            return null;
        }
    }

    boolean checkBotHealth() {
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
