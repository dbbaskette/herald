package com.herald.tools;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    private final List<Pattern> blocklist;
    private final ShellSecurityConfig securityConfig;
    private final ShellCommandExecutor delegate;
    private final MessageSender telegramSender;
    private final JdbcTemplate jdbcTemplate;
    private final ApprovalGate approvalGate;

    @Autowired
    public HeraldShellDecorator(ShellSecurityConfig securityConfig,
                         Optional<ShellCommandExecutor> delegate,
                         Optional<MessageSender> messageSender,
                         JdbcTemplate jdbcTemplate,
                         ApprovalGate approvalGate) {
        this.securityConfig = securityConfig;
        this.jdbcTemplate = jdbcTemplate;
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

    @Tool(description = "Execute a shell command on the host system. Destructive commands are blocked for safety. Commands requiring elevated privileges may need confirmation.")
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
            pb.redirectErrorStream(true);
            // Ensure Obsidian CLI is in PATH for obsidian skill commands
            String path = pb.environment().getOrDefault("PATH", "");
            if (!path.contains(OBSIDIAN_CLI_DIR)) {
                pb.environment().put("PATH", path + ":" + OBSIDIAN_CLI_DIR);
            }
            // Inject Google credentials from Settings DB for gws commands
            if (command.trim().startsWith("gws ")) {
                injectGwsCredentials(pb.environment());
            }
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Shell command timed out after {}s: [{}]", timeoutSeconds, redactForLog(command));
                return "ERROR: Command timed out after " + timeoutSeconds + " seconds and was terminated.";
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return "Exit code: " + exitCode + "\n" + output;
            }
            return output.isEmpty() ? "(no output)" : output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "ERROR: Command execution was interrupted.";
        } catch (Exception e) {
            log.error("Shell command failed: [{}] — {}", redactForLog(command), e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Inject Google OAuth credentials from Settings DB into the process environment.
     * Settings DB values take precedence over .env values already in the environment.
     */
    private void injectGwsCredentials(Map<String, String> env) {
        if (jdbcTemplate == null) return;
        try {
            List<String> clientIds = jdbcTemplate.queryForList(
                    "SELECT value FROM settings WHERE key = 'google.client-id'", String.class);
            if (!clientIds.isEmpty() && !clientIds.get(0).isBlank()) {
                env.put("GOOGLE_WORKSPACE_CLI_CLIENT_ID", clientIds.get(0));
            }
            List<String> secrets = jdbcTemplate.queryForList(
                    "SELECT value FROM settings WHERE key = 'google.client-secret'", String.class);
            if (!secrets.isEmpty() && !secrets.get(0).isBlank()) {
                env.put("GOOGLE_WORKSPACE_CLI_CLIENT_SECRET", secrets.get(0));
            }
        } catch (Exception e) {
            log.debug("Could not read Google credentials from settings: {}", e.getMessage());
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
