package com.herald.tools;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.herald.telegram.TelegramSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
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
    private final TelegramSender telegramSender;
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pendingConfirmations = new ConcurrentHashMap<>();

    HeraldShellDecorator(ShellSecurityConfig securityConfig,
                         Optional<ShellCommandExecutor> delegate,
                         Optional<TelegramSender> telegramSender) {
        this.securityConfig = securityConfig;
        this.blocklist = securityConfig.getBlocklistPatterns().stream()
                .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE))
                .toList();
        this.delegate = delegate.orElse(command -> executeCommandInternal(command, securityConfig.getShellTimeoutSeconds()));
        this.telegramSender = telegramSender.orElse(null);
    }

    // Package-private constructor for testing without Optional wrappers
    HeraldShellDecorator(ShellSecurityConfig securityConfig) {
        this(securityConfig, Optional.empty(), Optional.empty());
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
            return handleConfirmation(command);
        }

        log.info("Executing shell command: [{}]", redactForLog(command));
        String result = delegate.execute(command);
        log.info("Shell command completed: [{}]", redactForLog(command));
        return result;
    }

    String checkBlocklist(String command) {
        for (int i = 0; i < blocklist.size(); i++) {
            if (blocklist.get(i).matcher(command).find()) {
                return securityConfig.getBlocklistPatterns().get(i);
            }
        }
        return null;
    }

    boolean requiresConfirmation(String command) {
        if (SUDO_PATTERN.matcher(command).find()) {
            return true;
        }
        if (command.matches("(?i).*>\\s*/(?:etc|usr|var|sys|boot|proc|dev)(?:/.*|$).*")) {
            return true;
        }
        if (command.matches("(?i).*\\|\\s*(?:sh|bash|zsh|dash|ksh)\\b.*")) {
            return true;
        }
        return false;
    }

    /**
     * Called externally (e.g., by TelegramPoller) to approve or deny a pending command.
     * TODO: Wire into TelegramPoller to handle user YES/NO responses.
     * AskUserQuestionTool integration is a follow-up.
     */
    public void confirmCommand(String confirmId, boolean approved) {
        CompletableFuture<Boolean> future = pendingConfirmations.get(confirmId);
        if (future != null) {
            future.complete(approved);
        }
    }

    private String handleConfirmation(String command) {
        String confirmId = UUID.randomUUID().toString().substring(0, 8);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pendingConfirmations.put(confirmId, future);

        if (telegramSender != null) {
            telegramSender.sendMessage("Shell command requires confirmation:\n"
                    + command + "\n"
                    + "Reply: /confirm " + confirmId + " yes  OR  /confirm " + confirmId + " no");
        } else {
            log.warn("TelegramSender not available; cannot send confirmation prompt for command: [{}]",
                    redactForLog(command));
            pendingConfirmations.remove(confirmId);
            return "CONFIRMATION REQUIRED: This command requires user approval before execution. "
                    + "Command: " + command + " — TelegramSender is not configured.";
        }

        try {
            Boolean approved = future.get(securityConfig.getConfirmationTimeoutSeconds(), TimeUnit.SECONDS);
            if (Boolean.TRUE.equals(approved)) {
                log.info("Command confirmed by user, executing: [{}]", redactForLog(command));
                return delegate.execute(command);
            }
            log.info("Command denied by user: [{}]", redactForLog(command));
            return "DENIED: Command was rejected by user: " + command;
        } catch (TimeoutException e) {
            log.warn("Confirmation timed out for command: [{}]", redactForLog(command));
            return "TIMEOUT: Confirmation timed out after " + securityConfig.getConfirmationTimeoutSeconds()
                    + "s. Command was not executed: " + command;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "ERROR: Confirmation was interrupted.";
        } catch (Exception e) {
            log.error("Error during confirmation for command: [{}] — {}", redactForLog(command), e.getMessage());
            return "ERROR: " + e.getMessage();
        } finally {
            pendingConfirmations.remove(confirmId);
        }
    }

    private String executeCommandInternal(String command, int timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.redirectErrorStream(true);
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

    private static String redactForLog(String command) {
        String redacted = SENSITIVE_PATTERN.matcher(command).replaceAll("$1[REDACTED]");
        if (redacted.length() > MAX_LOG_LENGTH) {
            return redacted.substring(0, MAX_LOG_LENGTH) + "...[truncated]";
        }
        return redacted;
    }
}
