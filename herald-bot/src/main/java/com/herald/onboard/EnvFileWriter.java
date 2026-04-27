package com.herald.onboard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Idempotent {@code .env} merger. Reads the existing file (if any), updates
 * each requested key in place — preserving comments, blank lines, and any
 * other keys the user has set — and appends keys that didn't previously
 * exist. Designed so re-running the wizard never destroys hand-edited config.
 */
public final class EnvFileWriter {

    private EnvFileWriter() {
    }

    /**
     * Update {@code path} with {@code updates}. Existing keys are rewritten in
     * place; new keys are appended at the end. Lines starting with {@code #}
     * or empty lines are preserved as-is. Values are double-quoted only when
     * they contain whitespace or special characters — the format follows the
     * de-facto {@code .env} convention used by tools like {@code dotenv} and
     * {@code direnv}.
     *
     * @return ordered map of {@code key → action} where action is
     *         {@code "added"}, {@code "updated"}, or {@code "unchanged"}.
     */
    public static Map<String, String> merge(Path path, Map<String, String> updates) throws IOException {
        List<String> existingLines = Files.exists(path) ? Files.readAllLines(path) : new ArrayList<>();
        Map<String, String> actions = new LinkedHashMap<>();
        Map<String, String> remaining = new LinkedHashMap<>(updates);
        List<String> output = new ArrayList<>();

        for (String line : existingLines) {
            String trimmed = line.stripLeading();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                output.add(line);
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                output.add(line);
                continue;
            }
            String key = line.substring(0, eq).trim();
            if (remaining.containsKey(key)) {
                String newValue = remaining.remove(key);
                String oldValue = stripQuotes(line.substring(eq + 1).trim());
                if (oldValue.equals(newValue)) {
                    output.add(line);
                    actions.put(key, "unchanged");
                } else {
                    output.add(key + "=" + escape(newValue));
                    actions.put(key, "updated");
                }
            } else {
                output.add(line);
            }
        }

        if (!remaining.isEmpty()) {
            // Trailing blank line + section header before the appended block,
            // but only when the file already had content.
            if (!output.isEmpty()) {
                String last = output.get(output.size() - 1);
                if (!last.isBlank()) {
                    output.add("");
                }
            }
            output.add("# Added by `herald onboard`");
            for (Map.Entry<String, String> e : remaining.entrySet()) {
                output.add(e.getKey() + "=" + escape(e.getValue()));
                actions.put(e.getKey(), "added");
            }
        }

        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.write(path, output);
        return actions;
    }

    static String escape(String value) {
        if (value == null) return "";
        // Quote when the value contains whitespace, quote chars, or '#' — match
        // the dotenv loader's behavior (everything else is plain).
        boolean needsQuote = value.matches(".*[\\s\"'#].*");
        if (!needsQuote) {
            return value;
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static String stripQuotes(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
