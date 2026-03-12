package com.herald.tools;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class GwsTools {

    private static final Logger log = LoggerFactory.getLogger(GwsTools.class);
    private static final int TIMEOUT_SECONDS = 30;
    private static final String UNAVAILABLE_ERROR =
            "{\"error\": \"Google Workspace CLI (gws) not configured. Run 'gws auth login' to set up.\"}";

    @FunctionalInterface
    interface ProcessRunner {
        ProcessResult run(List<String> command, Map<String, String> extraEnv) throws Exception;
    }

    record ProcessResult(int exitCode, String output, boolean timedOut) {}

    private final GwsAvailabilityChecker gwsAvailabilityChecker;
    private final JdbcTemplate jdbcTemplate;
    private final ProcessRunner processRunner;

    @Autowired
    public GwsTools(GwsAvailabilityChecker gwsAvailabilityChecker, JdbcTemplate jdbcTemplate) {
        this(gwsAvailabilityChecker, jdbcTemplate, GwsTools::executeProcess);
    }

    GwsTools(GwsAvailabilityChecker gwsAvailabilityChecker, JdbcTemplate jdbcTemplate, ProcessRunner processRunner) {
        this.gwsAvailabilityChecker = gwsAvailabilityChecker;
        this.jdbcTemplate = jdbcTemplate;
        this.processRunner = processRunner;
    }

    @Tool(description = "List Gmail threads. Returns JSON array of recent email threads with subject, sender, and snippet. Output is always JSON.")
    public String gmail_threads_list() {
        return runGwsCommand(List.of("gws", "gmail", "users", "threads", "list",
                "--params", "{\"userId\": \"me\", \"maxResults\": 10}", "--format", "json"));
    }

    @Tool(description = "List Google Calendar events for today. Returns JSON array of today's calendar events with title, time, and attendees. Output is always JSON.")
    public String calendar_events_list() {
        String today = java.time.LocalDate.now().toString();
        String params = String.format(
                "{\"calendarId\": \"primary\", \"timeMin\": \"%sT00:00:00Z\", \"timeMax\": \"%sT23:59:59Z\", \"singleEvents\": true, \"orderBy\": \"startTime\"}",
                today, today);
        return runGwsCommand(List.of("gws", "calendar", "events", "list",
                "--params", params, "--format", "json"));
    }

    private String runGwsCommand(List<String> command) {
        if (!gwsAvailabilityChecker.isAvailable()) {
            return UNAVAILABLE_ERROR;
        }
        try {
            Map<String, String> env = buildGwsEnv();
            ProcessResult result = processRunner.run(command, env);
            if (result.timedOut()) {
                return "{\"error\": \"Command timed out after " + TIMEOUT_SECONDS + " seconds\"}";
            }
            if (result.exitCode() != 0) {
                log.warn("gws command {} exited with code {}", command, result.exitCode());
                log.debug("gws output: {}", result.output());
                return "{\"error\": \"gws command failed with exit code " + result.exitCode() + "\", \"output\": \"" + escapeJson(result.output()) + "\"}";
            }
            return result.output().isEmpty() ? "[]" : result.output();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "{\"error\": \"Command execution was interrupted.\"}";
        } catch (Exception e) {
            log.error("Failed to execute gws command {}: {}", command, e.getMessage());
            return "{\"error\": \"Failed to execute gws command: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    /**
     * Build env vars for gws commands. Prefers credentials from the Settings DB
     * (configured via the UI), falls back to process environment vars from .env.
     */
    private Map<String, String> buildGwsEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        String clientId = getSetting("google.client-id");
        String clientSecret = getSetting("google.client-secret");
        if (clientId != null && !clientId.isBlank()) {
            env.put("GOOGLE_WORKSPACE_CLI_CLIENT_ID", clientId);
        }
        if (clientSecret != null && !clientSecret.isBlank()) {
            env.put("GOOGLE_WORKSPACE_CLI_CLIENT_SECRET", clientSecret);
        }
        return env;
    }

    private String getSetting(String key) {
        try {
            List<String> values = jdbcTemplate.queryForList(
                    "SELECT value FROM settings WHERE key = ?", String.class, key);
            return values.isEmpty() ? null : values.get(0);
        } catch (Exception e) {
            log.debug("Could not read setting '{}': {}", key, e.getMessage());
            return null;
        }
    }

    private static ProcessResult executeProcess(List<String> command, Map<String, String> extraEnv) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        if (extraEnv != null && !extraEnv.isEmpty()) {
            pb.environment().putAll(extraEnv);
        }
        Process process = pb.start();
        String output;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }
        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new ProcessResult(-1, output, true);
        }
        return new ProcessResult(process.exitValue(), output, false);
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
