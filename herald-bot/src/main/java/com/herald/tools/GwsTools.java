package com.herald.tools;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class GwsTools {

    private static final Logger log = LoggerFactory.getLogger(GwsTools.class);
    private static final int TIMEOUT_SECONDS = 30;
    private static final String UNAVAILABLE_ERROR =
            "{\"error\": \"Google Workspace CLI (gws) not configured. Run 'gws auth login' to set up.\"}";

    @FunctionalInterface
    interface ProcessRunner {
        ProcessResult run(List<String> command) throws Exception;
    }

    record ProcessResult(int exitCode, String output, boolean timedOut) {}

    private final GwsAvailabilityChecker gwsAvailabilityChecker;
    private final ProcessRunner processRunner;

    @Autowired
    public GwsTools(GwsAvailabilityChecker gwsAvailabilityChecker) {
        this(gwsAvailabilityChecker, GwsTools::executeProcess);
    }

    GwsTools(GwsAvailabilityChecker gwsAvailabilityChecker, ProcessRunner processRunner) {
        this.gwsAvailabilityChecker = gwsAvailabilityChecker;
        this.processRunner = processRunner;
    }

    @Tool(description = "List Gmail threads. Returns JSON array of recent email threads with subject, sender, and snippet. Output is always JSON (--format json).")
    public String gmail_threads_list() {
        return runGwsCommand(List.of("gws", "gmail", "threads", "list", "--format", "json"));
    }

    @Tool(description = "List Google Calendar events. Returns JSON array of upcoming calendar events with title, time, and attendees. Output is always JSON (--format json).")
    public String calendar_events_list() {
        return runGwsCommand(List.of("gws", "calendar", "events", "list", "--format", "json"));
    }

    private String runGwsCommand(List<String> command) {
        if (!gwsAvailabilityChecker.isAvailable()) {
            return UNAVAILABLE_ERROR;
        }
        try {
            ProcessResult result = processRunner.run(command);
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

    private static ProcessResult executeProcess(List<String> command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
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
