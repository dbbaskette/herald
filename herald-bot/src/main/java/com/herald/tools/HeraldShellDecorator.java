package com.herald.tools;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class HeraldShellDecorator {

    private static final Logger log = LoggerFactory.getLogger(HeraldShellDecorator.class);

    private final List<Pattern> blocklist;
    private final ShellSecurityConfig securityConfig;

    HeraldShellDecorator(ShellSecurityConfig securityConfig) {
        this.securityConfig = securityConfig;
        this.blocklist = securityConfig.getBlocklistPatterns().stream()
                .map(Pattern::compile)
                .toList();
    }

    @Tool(description = "Execute a shell command on the host system. Destructive commands are blocked for safety. Commands requiring elevated privileges may need confirmation.")
    public String shell_exec(
            @ToolParam(description = "The shell command to execute") String command) {

        String blocked = checkBlocklist(command);
        if (blocked != null) {
            log.warn("BLOCKED shell command: [{}] — matched pattern: {}", command, blocked);
            return "BLOCKED: This command matches a destructive pattern (" + blocked
                    + ") and has been rejected for safety. Please use a safer alternative.";
        }

        if (requiresConfirmation(command)) {
            log.info("Shell command requires confirmation: [{}]", command);
            return "CONFIRMATION REQUIRED: This command requires user approval before execution. "
                    + "Command: " + command + " — Please ask the user to confirm via Telegram.";
        }

        return executeCommand(command);
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
        String trimmed = command.trim();
        if (trimmed.startsWith("sudo ") || trimmed.contains(" sudo ")) {
            return true;
        }
        if (trimmed.matches(".*>\\s*/(?:etc|usr|var|sys|boot|proc|dev)(?:/.*|$).*")) {
            return true;
        }
        if (trimmed.matches(".*\\|\\s*(?:sh|bash|zsh|dash|ksh)\\b.*")) {
            return true;
        }
        return false;
    }

    private String executeCommand(String command) {
        log.info("Executing shell command: [{}]", command);
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            int exitCode = process.waitFor();
            log.info("Shell command completed: [{}] exitCode={}", command, exitCode);

            if (exitCode != 0) {
                return "Exit code: " + exitCode + "\n" + output;
            }
            return output.isEmpty() ? "(no output)" : output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Shell command interrupted: [{}]", command);
            return "ERROR: Command execution was interrupted.";
        } catch (Exception e) {
            log.error("Shell command failed: [{}] — {}", command, e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }
}
