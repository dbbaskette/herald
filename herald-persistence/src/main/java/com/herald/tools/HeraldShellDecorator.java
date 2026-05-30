package com.herald.tools;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.herald.agent.ApprovalGate;
import com.herald.agent.MessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class HeraldShellDecorator {

    private static final Logger log = LoggerFactory.getLogger(HeraldShellDecorator.class);
    private static final Pattern SUDO_PATTERN = Pattern.compile("(?:^|[;&|(\\s])sudo\\s", Pattern.CASE_INSENSITIVE);
    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            "(?i)(Authorization:\\s*Bearer\\s+|--password[= ]\\s*|api[_-]?key[= ]\\s*|token[= ]\\s*)\\S+");
    private static final int MAX_LOG_LENGTH = 200;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<Pattern> blocklist;
    private final ShellSecurityConfig securityConfig;
    private final ShellCommandExecutor delegate;
    private final MessageSender telegramSender;
    private final ApprovalGate approvalGate;

    @Autowired
    public HeraldShellDecorator(ShellSecurityConfig securityConfig,
                         Optional<ShellCommandExecutor> delegate,
                         Optional<MessageSender> messageSender,
                         @SuppressWarnings("unused") JdbcTemplate jdbcTemplate,
                         ApprovalGate approvalGate) {
        // jdbcTemplate parameter retained for binary compatibility with callers
        // (HeraldAgentConfig + tests). No longer used — Google creds come from
        // process env, not the settings table. Drop in a future refactor.
        this.securityConfig = securityConfig;
        this.blocklist = securityConfig.getShellBlocklist().stream()
                .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE))
                .toList();
        this.delegate = delegate.orElse(command -> executeCommandInternal(command, securityConfig.getShellTimeoutSeconds()));
        this.telegramSender = messageSender.orElse(null);
        this.approvalGate = approvalGate;
    }

    HeraldShellDecorator(ShellSecurityConfig securityConfig) {
        this(securityConfig, Optional.empty(), Optional.empty(), null,
             new ApprovalGate(Optional.empty(), 60));
    }

    @Tool(name = "shell", description = "Execute a shell command on the host system. Destructive commands are blocked for safety. Commands requiring elevated privileges may need confirmation.")
    public String shell_exec(
            @ToolParam(description = "The shell command to execute") String command) {

        if (command == null || command.isBlank()) {
            return "ERROR: No command provided.";
        }

        String blocked = checkBlocklist(command);
        if (blocked != null) {
            log.warn("BLOCKED shell command: [{}] — matched pattern: {}", redactForLog(command), blocked);
            return "BLOCKED: This command matches a destructive pattern (" + blocked
                    + ") and has been rejected for safety. Please use a safer alternative.";
        }

        if (requiresConfirmation(command)) {
            log.info("Shell command requires confirmation: [{}]", redactForLog(command));
            String approval = approvalGate.requestApproval("Shell command: " + redactForLog(command));
            if ("APPROVED".equals(approval)) {
                log.info("Command confirmed by user, executing: [{}]", redactForLog(command));
                String result = delegate.execute(command);
                log.info("Shell command completed: [{}]", redactForLog(command));
                return result;
            }
            if ("TIMEOUT".equals(approval)) {
                return "TIMEOUT: Confirmation timed out after "
                        + securityConfig.getConfirmationTimeoutSeconds()
                        + "s. Command was not executed: " + redactForLog(command);
            }
            return "DENIED: Command was rejected by user: " + redactForLog(command);
        }

        log.info("Executing shell command: [{}]", redactForLog(command));
        String result = delegate.execute(command);
        log.info("Shell command completed: [{}]", redactForLog(command));
        return result;
    }

    String checkBlocklist(String command) {
        for (int i = 0; i < blocklist.size(); i++) {
            if (blocklist.get(i).matcher(command).find()) {
                return securityConfig.getShellBlocklist().get(i);
            }
        }
        return null;
    }

    boolean requiresConfirmation(String command) {
        // Autonomous mode: the user has granted full authority, so don't gate on
        // confirmation. The catastrophic blocklist still applies independently.
        if (!securityConfig.isRequireConfirmation()) {
            return false;
        }
        if (SUDO_PATTERN.matcher(command).find()) {
            return true;
        }
        // Check for writes to system directories, but exclude /dev/null (safe redirect)
        if (command.matches("(?i).*>\\s*/(?:etc|usr|var|sys|boot|proc)(?:/.*|$).*")) {
            return true;
        }
        if (command.matches("(?i).*>\\s*/dev/(?!null\\b).*")) {
            return true;
        }
        if (command.matches("(?i).*\\|\\s*(?:sh|bash|zsh|dash|ksh)\\b.*")) {
            return true;
        }
        return false;
    }

    private static final String OBSIDIAN_CLI_DIR = "/Applications/Obsidian.app/Contents/MacOS";

    private String executeCommandInternal(String command, int timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            // Keep stderr separate so we can build a structured envelope: success path
            // returns clean stdout, failure path surfaces stderr distinctly, and JSON
            // error envelopes (Google API style) get a prepended summary line.
            String path = pb.environment().getOrDefault("PATH", "");
            if (!path.contains(OBSIDIAN_CLI_DIR)) {
                pb.environment().put("PATH", path + ":" + OBSIDIAN_CLI_DIR);
            }
            // No special-case for gws: GOOGLE_WORKSPACE_CLI_CLIENT_ID/SECRET come
            // from the process env (loaded from .env by run.sh). gws's credential
            // priority chain handles fallback to ~/.config/gws/client_secret.json.
            Process process = pb.start();

            // Drain stderr on a thread so it can't deadlock against a full pipe buffer
            // while we're reading stdout on the main thread.
            AtomicReference<String> stderrRef = new AtomicReference<>("");
            Thread stderrPump = new Thread(() -> stderrRef.set(drain(process.getErrorStream())),
                    "herald-shell-stderr");
            stderrPump.setDaemon(true);
            stderrPump.start();

            String stdout = drain(process.getInputStream());

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Shell command timed out after {}s: [{}]", timeoutSeconds, redactForLog(command));
                return "ERROR: Command timed out after " + timeoutSeconds + " seconds and was terminated.";
            }
            stderrPump.join(500);
            String stderr = stderrRef.get();
            int exitCode = process.exitValue();

            return formatResult(exitCode, stdout, stderr);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "ERROR: Command execution was interrupted.";
        } catch (Exception e) {
            log.error("Shell command failed: [{}] — {}", redactForLog(command), e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Build the string the model sees from raw process output. Contracts preserved:
     * success returns bare stdout, success with no output returns "(no output)",
     * non-zero exit prefixes "Exit code: N". On top of that, Google-API-style JSON
     * error envelopes get a prepended one-line summary so the model doesn't have to
     * scan a wall of JSON to know what failed.
     */
    static String formatResult(int exitCode, String stdout, String stderr) {
        String trimmedOut = stdout == null ? "" : stdout;
        String trimmedErr = stderr == null ? "" : stderr;

        if (exitCode == 0) {
            String summary = jsonErrorSummary(trimmedOut);
            if (summary != null) {
                return summary + "\n\n" + trimmedOut;
            }
            if (trimmedOut.isEmpty() && !trimmedErr.isEmpty()) {
                // Some CLIs log progress to stderr; surface it on success-with-empty-stdout.
                return trimmedErr;
            }
            return trimmedOut.isEmpty() ? "(no output)" : trimmedOut;
        }

        StringBuilder out = new StringBuilder();
        out.append("Exit code: ").append(exitCode);
        String summary = jsonErrorSummary(trimmedOut);
        if (summary != null) {
            out.append(" — ").append(summary);
        }
        if (!trimmedErr.isEmpty()) {
            out.append("\n--- stderr ---\n").append(trimmedErr);
        }
        if (!trimmedOut.isEmpty()) {
            out.append(trimmedErr.isEmpty() ? "\n" : "\n--- stdout ---\n").append(trimmedOut);
        }
        return out.toString();
    }

    /**
     * If body looks like a Google API JSON error envelope, return a single-line summary
     * (with an actionable hint when we can infer one); else null. Fast-paths the non-JSON
     * case by checking the first non-whitespace char.
     */
    private static String jsonErrorSummary(String body) {
        if (body == null || body.isEmpty()) return null;
        int i = 0;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) i++;
        if (i >= body.length() || (body.charAt(i) != '{' && body.charAt(i) != '[')) return null;
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode error = root.isArray() && !root.isEmpty() ? root.get(0).get("error") : root.get("error");
            if (error == null || !error.isObject()) return null;
            JsonNode msg = error.get("message");
            JsonNode code = error.get("code");
            JsonNode status = error.get("status");
            if (msg == null) return null;
            StringBuilder s = new StringBuilder("ERROR");
            if (code != null) s.append(" ").append(code.asText());
            if (status != null) s.append(" ").append(status.asText());
            s.append(": ").append(msg.asText());
            String hint = googleErrorHint(code, status, msg);
            if (hint != null) s.append(" — ").append(hint);
            return s.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Map a Google API error to actionable advice the model can relay to the user.
     * Returning null means "no specific hint" — the bare error message stands on its own.
     */
    static String googleErrorHint(JsonNode code, JsonNode status, JsonNode msg) {
        int c = code == null ? -1 : code.asInt(-1);
        String st = status == null ? "" : status.asText("");
        String m = msg == null ? "" : msg.asText("");

        if (c == 401 || "UNAUTHENTICATED".equals(st)) {
            return "Google token is expired or revoked. Reconnect via Settings → Connect Google, "
                    + "or run `./run.sh auth`.";
        }
        if (c == 403 && "PERMISSION_DENIED".equals(st)) {
            // Two flavors of 403 PERMISSION_DENIED — missing scope vs missing permission.
            // "insufficient" / "scope" wording in the message points at the scope case.
            String lc = m.toLowerCase();
            if (lc.contains("scope") || lc.contains("insufficient")) {
                return "Missing OAuth scope. Reconnect via Settings → Connect Google with the needed scope set.";
            }
            return "The signed-in Google account lacks permission for this resource — try a different account or ask the owner to grant access.";
        }
        if (c == 403 && ("SERVICE_DISABLED".equals(st) || m.contains("API has not been used"))) {
            return "Required Google API is not enabled in this GCP project. Enable it at "
                    + "https://console.cloud.google.com/apis/library, then retry.";
        }
        if (c == 404 || "NOT_FOUND".equals(st)) {
            return "The requested resource was not found — verify the ID/name and that it belongs to the signed-in account.";
        }
        if (c == 429 || "RESOURCE_EXHAUSTED".equals(st)) {
            return "Google rate-limited the request. Back off and retry in a moment.";
        }
        if (c >= 500) {
            return "Google returned a server error. Transient — retry the request.";
        }
        return null;
    }

    private static String drain(InputStream is) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            return "";
        }
    }

    private static String redactForLog(String command) {
        String redacted = SENSITIVE_PATTERN.matcher(command).replaceAll("$1[REDACTED]");
        if (redacted.length() > MAX_LOG_LENGTH) {
            return redacted.substring(0, MAX_LOG_LENGTH) + "...[truncated]";
        }
        return redacted;
    }
}
