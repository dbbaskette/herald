package com.herald.tools;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Apple Reminders tools. Uses the <a href="https://github.com/keith/reminders-cli">
 * {@code reminders}</a> CLI to read and write Apple Reminders on macOS. Activated
 * only when {@link RemindersAvailabilityChecker#isAvailable()} returns true.
 *
 * <p>All methods return JSON strings so the model can parse structured data.
 * Errors come back as {@code {"error": "..."}} objects rather than exceptions.</p>
 */
@Component
public class RemindersTools {

    private static final Logger log = LoggerFactory.getLogger(RemindersTools.class);
    private static final int TIMEOUT_SECONDS = 15;
    private static final String UNAVAILABLE_ERROR =
            "{\"error\": \"Apple Reminders CLI (reminders) is not available. "
                    + "Requires macOS + 'brew install keith/formulae/reminders-cli'.\"}";

    @FunctionalInterface
    interface ProcessRunner {
        ProcessResult run(List<String> command) throws Exception;
    }

    record ProcessResult(int exitCode, String output, boolean timedOut) {}

    private final RemindersAvailabilityChecker availabilityChecker;
    private final ProcessRunner processRunner;

    @Autowired
    public RemindersTools(RemindersAvailabilityChecker availabilityChecker) {
        this(availabilityChecker, RemindersTools::executeProcess);
    }

    RemindersTools(RemindersAvailabilityChecker availabilityChecker, ProcessRunner processRunner) {
        this.availabilityChecker = availabilityChecker;
        this.processRunner = processRunner;
    }

    @Tool(description = "List the names of the user's Apple Reminders lists. Returns a JSON array of strings.")
    public String reminders_list_names() {
        if (!availabilityChecker.isAvailable()) {
            return UNAVAILABLE_ERROR;
        }
        try {
            ProcessResult result = processRunner.run(List.of("reminders", "show-lists"));
            if (result.timedOut()) {
                return timeoutError();
            }
            if (result.exitCode() != 0) {
                return failureError(result);
            }
            // show-lists output is one name per line; convert to JSON array.
            List<String> names = result.output().lines()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
            return toJsonStringArray(names);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "{\"error\": \"Command execution was interrupted.\"}";
        } catch (Exception e) {
            log.error("Failed to list reminders lists: {}", e.getMessage());
            return "{\"error\": \"Failed to list reminders lists: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    @Tool(description = "Show incomplete reminders in one of the user's Apple Reminders lists. "
            + "Returns a JSON array of reminders with index, title, due date, and notes. "
            + "Use reminders_list_names() first to find valid list names.")
    public String reminders_show(
            @ToolParam(description = "List name. Use the exact name from reminders_list_names(). "
                    + "Pass null or empty string to show reminders across all lists.") String listName) {
        if (!availabilityChecker.isAvailable()) {
            return UNAVAILABLE_ERROR;
        }
        List<String> command = new ArrayList<>();
        command.add("reminders");
        if (listName == null || listName.isBlank()) {
            command.add("show-all");
        } else {
            command.add("show");
            command.add(listName);
        }
        command.add("--format");
        command.add("json");
        try {
            ProcessResult result = processRunner.run(command);
            if (result.timedOut()) {
                return timeoutError();
            }
            if (result.exitCode() != 0) {
                return failureError(result);
            }
            return result.output().isEmpty() ? "[]" : result.output();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "{\"error\": \"Command execution was interrupted.\"}";
        } catch (Exception e) {
            log.error("Failed to show reminders for list {}: {}", listName, e.getMessage());
            return "{\"error\": \"Failed to show reminders: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    @Tool(description = "Create a new reminder in one of the user's Apple Reminders lists. "
            + "Returns a JSON object confirming the created reminder. "
            + "Use reminders_list_names() first to find valid list names.")
    public String reminders_create(
            @ToolParam(description = "Target list name. Use the exact name from reminders_list_names().") String listName,
            @ToolParam(description = "Reminder title (required). Keep it short and imperative.") String title,
            @ToolParam(description = "Due date in natural language ('tomorrow 3pm', 'Thursday 9am') or ISO-8601. "
                    + "Pass null or empty string for no due date.", required = false) String dueDate,
            @ToolParam(description = "Optional notes / description for the reminder. "
                    + "Pass null or empty string for no notes.", required = false) String notes) {
        if (!availabilityChecker.isAvailable()) {
            return UNAVAILABLE_ERROR;
        }
        if (listName == null || listName.isBlank() || title == null || title.isBlank()) {
            return "{\"error\": \"listName and title are required\"}";
        }
        List<String> command = new ArrayList<>();
        command.add("reminders");
        command.add("add");
        command.add(listName);
        command.add(title);
        if (dueDate != null && !dueDate.isBlank()) {
            command.add("--due-date");
            command.add(dueDate);
        }
        if (notes != null && !notes.isBlank()) {
            command.add("--notes");
            command.add(notes);
        }
        return runWriteCommand(command, "create");
    }

    @Tool(description = "Mark a reminder complete. Use reminders_show(listName) first to find the reminder's index.")
    public String reminders_complete(
            @ToolParam(description = "List name containing the reminder.") String listName,
            @ToolParam(description = "Reminder index (0-based) within the list, as reported by reminders_show().") int index) {
        if (!availabilityChecker.isAvailable()) {
            return UNAVAILABLE_ERROR;
        }
        if (listName == null || listName.isBlank() || index < 0) {
            return "{\"error\": \"listName and a non-negative index are required\"}";
        }
        return runWriteCommand(
                List.of("reminders", "complete", listName, Integer.toString(index)),
                "complete");
    }

    @Tool(description = "Delete a reminder. Use reminders_show(listName) first to find the reminder's index. "
            + "Prefer reminders_complete() over delete when the reminder is simply done.")
    public String reminders_delete(
            @ToolParam(description = "List name containing the reminder.") String listName,
            @ToolParam(description = "Reminder index (0-based) within the list, as reported by reminders_show().") int index) {
        if (!availabilityChecker.isAvailable()) {
            return UNAVAILABLE_ERROR;
        }
        if (listName == null || listName.isBlank() || index < 0) {
            return "{\"error\": \"listName and a non-negative index are required\"}";
        }
        return runWriteCommand(
                List.of("reminders", "delete", listName, Integer.toString(index)),
                "delete");
    }

    private String runWriteCommand(List<String> command, String op) {
        try {
            ProcessResult result = processRunner.run(command);
            if (result.timedOut()) {
                return timeoutError();
            }
            if (result.exitCode() != 0) {
                return failureError(result);
            }
            return "{\"ok\": true, \"operation\": \"" + op + "\", \"output\": \""
                    + escapeJson(result.output().trim()) + "\"}";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "{\"error\": \"Command execution was interrupted.\"}";
        } catch (Exception e) {
            log.error("Failed to execute reminders {}: {}", op, e.getMessage());
            return "{\"error\": \"Failed to " + op + " reminder: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    private static String timeoutError() {
        return "{\"error\": \"Command timed out after " + TIMEOUT_SECONDS + " seconds\"}";
    }

    private static String failureError(ProcessResult result) {
        return "{\"error\": \"reminders command failed with exit code " + result.exitCode()
                + "\", \"output\": \"" + escapeJson(result.output()) + "\"}";
    }

    private static String toJsonStringArray(List<String> items) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escapeJson(items.get(i))).append("\"");
        }
        sb.append("]");
        return sb.toString();
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
