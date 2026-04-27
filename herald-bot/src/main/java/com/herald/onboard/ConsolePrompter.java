package com.herald.onboard;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Real-terminal {@link Prompter}. Uses {@link Console} when available so
 * {@link Console#readPassword} can hide secrets from the screen; falls back
 * to {@link System#in} when run inside an IDE / piped session where
 * {@code System.console()} returns null.
 */
public class ConsolePrompter implements Prompter {

    private final Console console;
    private final BufferedReader fallbackIn;
    private final PrintStream out;
    private final Map<String, String> presetAnswers;

    public ConsolePrompter(PrintStream out, Map<String, String> presetAnswers) {
        this.console = System.console();
        this.fallbackIn = console == null
                ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
                : null;
        this.out = out;
        this.presetAnswers = presetAnswers == null ? Map.of() : presetAnswers;
    }

    @Override
    public String prompt(String message) {
        // Look up a non-interactive --answer=key:value first, so scripted runs
        // skip terminal IO entirely.
        String preset = presetFor(message);
        if (preset != null) {
            out.println(message + " [scripted: " + redactIfSecret(message, preset) + "]");
            return preset;
        }
        if (console != null) {
            String line = console.readLine("%s ", message);
            return line == null ? "" : line;
        }
        out.print(message + " ");
        out.flush();
        try {
            String line = fallbackIn.readLine();
            return line == null ? "" : line;
        } catch (IOException e) {
            return "";
        }
    }

    @Override
    public String promptSecret(String message) {
        String preset = presetFor(message);
        if (preset != null) {
            out.println(message + " [scripted: " + redactIfSecret(message, preset) + "]");
            return preset;
        }
        if (console != null) {
            char[] secret = console.readPassword("%s ", message);
            return secret == null ? "" : new String(secret);
        }
        // Without a real Console, fall back to plain readLine and warn — this
        // is the IDE-run case where echo can't be suppressed. The non-interactive
        // --answer= path covers CI tests.
        out.println("(warning: no terminal — input will echo)");
        return prompt(message);
    }

    @Override
    public boolean confirm(String message, boolean defaultYes) {
        String suffix = defaultYes ? " [Y/n]" : " [y/N]";
        String response = prompt(message + suffix).trim().toLowerCase();
        if (response.isEmpty()) return defaultYes;
        return response.startsWith("y");
    }

    @Override
    public void waitForEnter(String message) {
        if (presetAnswers.containsKey("__skip_wait__")) {
            // Tests can opt out of the literal wait.
            return;
        }
        prompt(message);
    }

    @Override
    public void println(String line) {
        out.println(line);
    }

    /**
     * @return the scripted answer for this prompt's hint key, or null if none.
     *         The hint key is the trailing {@code (key)} marker in a prompt
     *         message, e.g. {@code "(api-key)"} → {@code presetAnswers.get("api-key")}.
     */
    private String presetFor(String message) {
        int open = message.lastIndexOf('(');
        int close = message.lastIndexOf(')');
        if (open < 0 || close <= open) return null;
        String key = message.substring(open + 1, close).trim();
        return presetAnswers.get(key);
    }

    private static String redactIfSecret(String message, String value) {
        String lower = message.toLowerCase();
        boolean isSecret = lower.contains("key") || lower.contains("token") || lower.contains("secret");
        if (!isSecret || value.length() < 4) return value;
        return "…" + value.substring(value.length() - 4);
    }
}
