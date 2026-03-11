package com.herald.ui;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/gws")
class GwsAuthController {

    private static final Logger log = LoggerFactory.getLogger(GwsAuthController.class);
    private final JdbcTemplate jdbcTemplate;
    private volatile Process loginProcess;

    GwsAuthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/status")
    ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Check if gws is installed
        if (!isGwsInstalled()) {
            result.put("installed", false);
            result.put("authenticated", false);
            result.put("message", "gws CLI not found. Install with: brew install googleworkspace-cli");
            return ResponseEntity.ok(result);
        }
        result.put("installed", true);

        // Check if client credentials are configured
        String clientId = getSetting("google.client-id");
        boolean hasCredentials = clientId != null && !clientId.isBlank();
        result.put("clientConfigured", hasCredentials);

        // Check auth status
        try {
            String output = runGws(List.of("gws", "auth", "status"), buildGwsEnv());
            // auth_method is "none" when not authenticated, "oauth2" when connected
            boolean authenticated = !output.contains("\"auth_method\": \"none\"")
                    && !output.contains("\"auth_method\":\"none\"")
                    && (output.contains("\"auth_method\": \"oauth2\"")
                        || output.contains("\"auth_method\":\"oauth2\""));
            result.put("authenticated", authenticated);
        } catch (Exception e) {
            result.put("authenticated", false);
            result.put("error", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    @PostMapping("/login")
    ResponseEntity<Map<String, String>> login() {
        Map<String, String> result = new LinkedHashMap<>();

        if (!isGwsInstalled()) {
            result.put("status", "error");
            result.put("message", "gws CLI not found");
            return ResponseEntity.ok(result);
        }

        String clientId = getSetting("google.client-id");
        String clientSecret = getSetting("google.client-secret");
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            result.put("status", "error");
            result.put("message", "Google client ID and secret must be configured in Settings first");
            return ResponseEntity.ok(result);
        }

        // Kill stale login process if it's still hanging around
        if (loginProcess != null) {
            if (loginProcess.isAlive()) {
                loginProcess.destroyForcibly();
                log.info("Killed stale gws auth login process");
            }
            loginProcess = null;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("gws", "auth", "login", "-s", "gmail,calendar,drive");
            pb.environment().putAll(buildGwsEnv());
            pb.redirectErrorStream(true);
            // Don't use inheritIO — let the process manage its own I/O
            // gws opens the browser via system call, doesn't need our stdout
            loginProcess = pb.start();

            result.put("status", "launched");
            result.put("message", "Browser opened for Google sign-in. Complete the authorization, then check status.");
        } catch (Exception e) {
            log.error("Failed to launch gws auth login: {}", e.getMessage(), e);
            result.put("status", "error");
            result.put("message", "Failed to launch login: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    @PostMapping("/logout")
    ResponseEntity<Map<String, String>> logout() {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            runGws(List.of("gws", "auth", "logout"), buildGwsEnv());
            result.put("status", "ok");
            result.put("message", "Google account disconnected");
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

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
        List<String> values = jdbcTemplate.queryForList(
                "SELECT value FROM settings WHERE key = ?", String.class, key);
        return values.isEmpty() ? null : values.get(0);
    }

    private boolean isGwsInstalled() {
        try {
            Process p = new ProcessBuilder("which", "gws").start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String runGws(List<String> command, Map<String, String> extraEnv) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.environment().putAll(extraEnv);
        Process process = pb.start();
        String output;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("gws command timed out");
        }
        return output;
    }
}
