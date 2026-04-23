package com.herald.tools;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Detects whether Apple Reminders integration is available on this host.
 *
 * <p>Two conditions must hold:</p>
 * <ol>
 *   <li>The JVM is running on macOS (Apple Reminders is an Apple-only API).</li>
 *   <li>The {@code reminders} CLI (<a href="https://github.com/keith/reminders-cli">keith/reminders-cli</a>)
 *       is on PATH.</li>
 * </ol>
 *
 * <p>Check runs once at startup. Non-macOS hosts skip the CLI probe entirely
 * so the Linux/Windows log stays quiet.</p>
 */
@Component
public class RemindersAvailabilityChecker {

    private static final Logger log = LoggerFactory.getLogger(RemindersAvailabilityChecker.class);

    private boolean remindersAvailable;
    private boolean isMac;
    private String version;

    @FunctionalInterface
    interface CommandRunner {
        CommandResult run(String command) throws Exception;
    }

    record CommandResult(int exitCode, String output) {}

    private final CommandRunner commandRunner;
    private final String osName;

    @Autowired
    public RemindersAvailabilityChecker() {
        this(RemindersAvailabilityChecker::executeCommand, System.getProperty("os.name", ""));
    }

    RemindersAvailabilityChecker(CommandRunner commandRunner, String osName) {
        this.commandRunner = commandRunner;
        this.osName = osName == null ? "" : osName;
    }

    @PostConstruct
    void checkAvailability() {
        isMac = osName.toLowerCase().startsWith("mac");
        if (!isMac) {
            remindersAvailable = false;
            log.debug("Apple Reminders integration skipped — not macOS (os.name={})", osName);
            return;
        }
        try {
            CommandResult result = commandRunner.run("reminders --version");
            if (result.exitCode() == 0 && !result.output().isBlank()) {
                remindersAvailable = true;
                version = result.output().trim();
                log.info("Apple Reminders CLI detected: {}", version);
            } else {
                remindersAvailable = false;
                log.warn("Apple Reminders CLI (reminders) not found in PATH — "
                        + "Reminders skills will be unavailable. "
                        + "Install with: brew install keith/formulae/reminders-cli");
            }
        } catch (Exception e) {
            remindersAvailable = false;
            log.warn("Apple Reminders CLI (reminders) not found in PATH — "
                    + "Reminders skills will be unavailable. "
                    + "Install with: brew install keith/formulae/reminders-cli");
        }
    }

    public boolean isAvailable() {
        return remindersAvailable;
    }

    public boolean isMac() {
        return isMac;
    }

    String getVersion() {
        return version;
    }

    private static CommandResult executeCommand(String command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }
        boolean finished = process.waitFor(5, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new CommandResult(-1, "");
        }
        return new CommandResult(process.exitValue(), output);
    }
}
