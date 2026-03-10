package com.herald.tools;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GwsAvailabilityChecker {

    private static final Logger log = LoggerFactory.getLogger(GwsAvailabilityChecker.class);

    private boolean gwsAvailable;
    private String gwsVersion;

    @FunctionalInterface
    interface CommandRunner {
        CommandResult run(String command) throws Exception;
    }

    record CommandResult(int exitCode, String output) {}

    private final CommandRunner commandRunner;

    GwsAvailabilityChecker() {
        this(GwsAvailabilityChecker::executeCommand);
    }

    GwsAvailabilityChecker(CommandRunner commandRunner) {
        this.commandRunner = commandRunner;
    }

    @PostConstruct
    void checkGwsAvailability() {
        try {
            CommandResult result = commandRunner.run("gws --version");
            if (result.exitCode() == 0 && !result.output().isBlank()) {
                gwsAvailable = true;
                gwsVersion = result.output().trim();
                log.info("Google Workspace CLI (gws) detected: {}", gwsVersion);
            } else {
                gwsAvailable = false;
                log.warn("Google Workspace CLI (gws) not found in PATH — Google skills will be unavailable. See docs/gws-setup.md for setup instructions.");
            }
        } catch (Exception e) {
            gwsAvailable = false;
            log.warn("Google Workspace CLI (gws) not found in PATH — Google skills will be unavailable. See docs/gws-setup.md for setup instructions.");
        }
    }

    public boolean isAvailable() {
        return gwsAvailable;
    }

    String getVersion() {
        return gwsVersion;
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
