package com.herald.ui;

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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives Google Workspace OAuth from the Settings UI. The "Connect Google"
 * button hits {@link #login}; the page polls {@link #status} until the user
 * completes consent. We shell out to {@code gws} for the heavy lifting —
 * Herald never sees user OAuth tokens directly.
 */
@RestController
@RequestMapping("/api/gws")
class GwsAuthController {

    private static final Logger log = LoggerFactory.getLogger(GwsAuthController.class);
    /** Default scope set when the UI doesn't pass one — covers every Workspace service Herald touches. */
    static final String DEFAULT_SCOPES = "gmail,calendar,drive,docs,sheets,tasks,people";
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private volatile Process loginProcess;

    GwsAuthController() {
    }

    @GetMapping("/status")
    ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> result = new LinkedHashMap<>();

        if (!isGwsInstalled()) {
            result.put("installed", false);
            result.put("authenticated", false);
            result.put("message", "gws CLI not found. Install with: brew install googleworkspace-cli");
            return ResponseEntity.ok(result);
        }
        result.put("installed", true);

        String envClientId = System.getenv("GOOGLE_WORKSPACE_CLI_CLIENT_ID");
        result.put("clientConfigured", envClientId != null && !envClientId.isBlank());

        try {
            String output = runGws(List.of("gws", "auth", "status"));
            JsonNode root = parseGwsJson(output);
            if (root == null) {
                result.put("authenticated", false);
                result.put("message", "Could not parse gws auth status output");
                return ResponseEntity.ok(result);
            }
            boolean tokenValid = root.path("token_valid").asBoolean(false);
            boolean hasRefresh = root.path("has_refresh_token").asBoolean(false);
            String authMethod = root.path("auth_method").asString("none");
            boolean authenticated = !"none".equals(authMethod) && tokenValid;
            result.put("authenticated", authenticated);
            result.put("tokenValid", tokenValid);
            result.put("hasRefreshToken", hasRefresh);
            result.put("user", root.path("user").asString(""));
            result.put("projectId", root.path("project_id").asString(""));
            result.put("authMethod", authMethod);
            result.put("credentialSource", root.path("credential_source").asString(""));
            result.put("keyringBackend", root.path("keyring_backend").asString(""));
            result.put("scopeCount", root.path("scope_count").asInt(0));
            result.put("scopes", asStringList(root.path("scopes")));
            result.put("enabledApiCount", root.path("enabled_api_count").asInt(0));
            result.put("enabledApis", asStringList(root.path("enabled_apis")));
        } catch (Exception e) {
            log.warn("gws auth status failed: {}", e.getMessage());
            result.put("authenticated", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/login")
    ResponseEntity<Map<String, String>> login(@RequestBody(required = false) Map<String, String> body) {
        Map<String, String> result = new LinkedHashMap<>();

        if (!isGwsInstalled()) {
            result.put("status", "error");
            result.put("message", "gws CLI not found");
            return ResponseEntity.ok(result);
        }

        String envClientId = System.getenv("GOOGLE_WORKSPACE_CLI_CLIENT_ID");
        String envClientSecret = System.getenv("GOOGLE_WORKSPACE_CLI_CLIENT_SECRET");
        if (envClientId == null || envClientId.isBlank() || envClientSecret == null || envClientSecret.isBlank()) {
            result.put("status", "error");
            result.put("message", "GOOGLE_WORKSPACE_CLI_CLIENT_ID and GOOGLE_WORKSPACE_CLI_CLIENT_SECRET must be set in .env, then restart with ./run.sh all");
            return ResponseEntity.ok(result);
        }

        String scopes = body != null && body.get("scopes") != null && !body.get("scopes").isBlank()
                ? body.get("scopes") : DEFAULT_SCOPES;

        // Kill stale login process — only one in-flight OAuth flow at a time.
        if (loginProcess != null) {
            if (loginProcess.isAlive()) {
                loginProcess.destroyForcibly();
                log.info("Killed stale gws auth login process");
            }
            loginProcess = null;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("gws", "auth", "login", "-s", scopes);
            // No env injection — gws reads GOOGLE_WORKSPACE_CLI_CLIENT_ID/SECRET
            // from the process env, which run.sh populates from .env.
            pb.redirectErrorStream(true);
            loginProcess = pb.start();

            // gws prints the auth URL on stdout, then blocks waiting for the OAuth
            // callback. DO NOT close the stream — gws needs it open to complete. We
            // peek the first ~5s to grab the URL and return it to the UI; the
            // subprocess keeps running until the user finishes (or we kill it).
            String authUrl = readAuthUrl(loginProcess, 5_000);
            result.put("status", "launched");
            result.put("scopes", scopes);
            if (authUrl != null) {
                result.put("authUrl", authUrl);
                result.put("message", "Click the link to sign in with Google.");
            } else {
                result.put("message", "Login started — check your browser or terminal for the auth URL.");
            }
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
            runGws(List.of("gws", "auth", "logout"));
            result.put("status", "ok");
            result.put("message", "Google account disconnected");
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    private boolean isGwsInstalled() {
        try {
            Process p = new ProcessBuilder("which", "gws").start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String runGws(List<String> command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        // No env override — inherits process env (loaded from .env by run.sh).
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

    private static String readAuthUrl(Process p, long timeoutMillis) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                if (reader.ready()) {
                    String line = reader.readLine();
                    if (line != null && line.trim().startsWith("http")) {
                        return line.trim();
                    }
                } else {
                    Thread.sleep(100);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // fall through — caller treats null as "URL not captured"
        }
        return null;
    }

    /**
     * {@code gws auth status} prefixes its JSON with a "Using keyring backend: …"
     * line. Skip past it to the first {@code {} before handing to Jackson.
     */
    static JsonNode parseGwsJson(String output) {
        if (output == null) return null;
        int start = output.indexOf('{');
        if (start < 0) return null;
        try {
            return JSON.readTree(output.substring(start));
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> asStringList(JsonNode node) {
        List<String> out = new java.util.ArrayList<>();
        if (node == null || !node.isArray()) return out;
        for (JsonNode item : node) {
            out.add(item.asString(""));
        }
        return out;
    }
}
