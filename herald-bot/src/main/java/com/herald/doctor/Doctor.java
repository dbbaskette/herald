package com.herald.doctor;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.herald.doctor.checks.DatabaseCheck;
import com.herald.doctor.checks.EnvVarCheck;
import com.herald.doctor.checks.ExternalCliCheck;
import com.herald.doctor.checks.JavaRuntimeCheck;
import com.herald.doctor.checks.MemoryDirCheck;
import com.herald.doctor.checks.PortCheck;
import com.herald.doctor.checks.SkillsDirCheck;

/**
 * Standalone runner that executes a battery of {@link HealthCheck}s and
 * prints a pass/warn/fail report. Intentionally does NOT boot Spring so
 * {@code herald doctor} starts in hundreds of ms instead of seconds — you
 * want to know you have a problem before the bot is running, not after.
 *
 * <p>Invoke via {@code java -cp herald-bot.jar com.herald.doctor.Doctor}
 * or {@code ./run.sh doctor}. Accepts {@code --json} and {@code --quiet}
 * flags; exit code is {@code 0} (clean), {@code 1} (warnings only), or
 * {@code 2} (any failure).</p>
 */
public final class Doctor {

    public static void main(String[] args) {
        OutputMode mode = parseMode(args);
        Doctor doctor = new Doctor(defaultChecks(), System.out);
        int exitCode = doctor.run(mode);
        System.exit(exitCode);
    }

    public enum OutputMode { HUMAN, JSON, QUIET }

    private final List<HealthCheck> checks;
    private final PrintStream out;

    public Doctor(List<HealthCheck> checks, PrintStream out) {
        this.checks = List.copyOf(checks);
        this.out = out;
    }

    /** Run every configured check and print a report. @return exit code (0/1/2). */
    public int run(OutputMode mode) {
        List<CheckResult> results = new ArrayList<>(checks.size());
        for (HealthCheck check : checks) {
            HealthCheck.Result result;
            try {
                result = check.run();
            } catch (RuntimeException e) {
                result = HealthCheck.Result.fail(
                        "check threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            results.add(new CheckResult(check.name(), result));
        }
        switch (mode) {
            case JSON -> printJson(results);
            case QUIET -> printHuman(results, true);
            default -> printHuman(results, false);
        }
        return exitCodeFor(results);
    }

    private void printHuman(List<CheckResult> results, boolean quiet) {
        if (!quiet) {
            out.println("▶ Herald Doctor — " + Instant.now());
            out.println();
        }
        int ok = 0, warn = 0, fail = 0;
        for (CheckResult cr : results) {
            switch (cr.result.status()) {
                case OK -> ok++;
                case WARN -> warn++;
                case FAIL -> fail++;
            }
            if (quiet && cr.result.status() == HealthCheck.Status.OK) {
                continue;
            }
            String glyph = switch (cr.result.status()) {
                case OK -> "\u2713  ";  // check
                case WARN -> "\u26A0  "; // warning sign
                case FAIL -> "\u2717  "; // cross
            };
            out.println(glyph + pad(cr.name, 26) + cr.result.message());
            if (cr.result.fixHint() != null && !cr.result.fixHint().isBlank()) {
                out.println("     Fix: " + cr.result.fixHint());
            }
        }
        out.println();
        out.printf("Summary: %d ok, %d warning%s, %d failure%s%n",
                ok, warn, warn == 1 ? "" : "s", fail, fail == 1 ? "" : "s");
    }

    private void printJson(List<CheckResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"timestamp\": \"").append(Instant.now()).append("\",\n");
        sb.append("  \"checks\": [\n");
        for (int i = 0; i < results.size(); i++) {
            CheckResult cr = results.get(i);
            sb.append("    {");
            sb.append("\"name\": \"").append(escape(cr.name)).append("\", ");
            sb.append("\"status\": \"").append(cr.result.status()).append("\", ");
            sb.append("\"message\": \"").append(escape(cr.result.message())).append("\"");
            if (cr.result.fixHint() != null) {
                sb.append(", \"fix\": \"").append(escape(cr.result.fixHint())).append("\"");
            }
            sb.append("}");
            if (i < results.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n}\n");
        out.print(sb);
    }

    static int exitCodeFor(List<CheckResult> results) {
        boolean anyFail = results.stream().anyMatch(r -> r.result.status() == HealthCheck.Status.FAIL);
        if (anyFail) return 2;
        boolean anyWarn = results.stream().anyMatch(r -> r.result.status() == HealthCheck.Status.WARN);
        return anyWarn ? 1 : 0;
    }

    static OutputMode parseMode(String[] args) {
        for (String arg : args) {
            if ("--json".equals(arg)) return OutputMode.JSON;
            if ("--quiet".equals(arg) || "-q".equals(arg)) return OutputMode.QUIET;
        }
        return OutputMode.HUMAN;
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) return s + " ";
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /**
     * @return the default set of checks, ordered by conceptual stack layer
     *         (runtime → config → data → skills → external CLIs → ports).
     */
    public static List<HealthCheck> defaultChecks() {
        Map<String, String> env = System.getenv();
        List<HealthCheck> list = new ArrayList<>();

        // Runtime.
        list.add(new JavaRuntimeCheck());

        // API keys — presence-only; full validation would hit the network and
        // cost real dollars on some providers.
        list.add(new EnvVarCheck("Anthropic API key", "ANTHROPIC_API_KEY",
                EnvVarCheck.Severity.FAIL,
                "Run `claude setup-token` and copy the token into .env"));
        list.add(new EnvVarCheck("OpenAI API key (optional)", "OPENAI_API_KEY",
                EnvVarCheck.Severity.WARN,
                "Optional — only needed when using OpenAI provider"));
        list.add(new EnvVarCheck("Gemini API key (optional)", "GEMINI_API_KEY",
                EnvVarCheck.Severity.WARN,
                "Optional — only needed when using Gemini provider"));

        // Telegram.
        list.add(new EnvVarCheck("Telegram bot token", "HERALD_TELEGRAM_BOT_TOKEN",
                EnvVarCheck.Severity.FAIL,
                "Create a bot via @BotFather and paste the token in .env"));
        list.add(new EnvVarCheck("Telegram allowed chat ID", "HERALD_TELEGRAM_ALLOWED_CHAT_ID",
                EnvVarCheck.Severity.WARN,
                "Without this, Herald ignores every message. Set to your Telegram chat id"));

        // Data.
        list.add(new DatabaseCheck());
        list.add(new MemoryDirCheck());

        // Skills.
        list.add(new SkillsDirCheck());

        // External CLIs — all optional; each skill's Step 0 installs what it needs.
        list.add(new ExternalCliCheck("Google Workspace CLI", "gws",
                "brew install googleworkspace-cli  (then `gws auth login`)"));
        list.add(new ExternalCliCheck("Reminders CLI (macOS)", "reminders",
                "brew install keith/formulae/reminders-cli"));
        list.add(new ExternalCliCheck("GitHub CLI", "gh",
                "brew install gh  (needed by github / skill-browser skills)"));
        list.add(new ExternalCliCheck("Whisper (voice transcription)", "whisper",
                "pipx install openai-whisper  — or run /skill voice-handling"));
        list.add(new ExternalCliCheck("PDF text extractor", "pdftotext",
                new String[]{"-v"},
                "brew install poppler  — or run /skill pdf-extract", false));

        // Ports.
        int botPort = parseInt(env.getOrDefault("HERALD_SERVER_PORT", "8081"), 8081);
        list.add(new PortCheck("herald-bot", botPort));
        list.add(new PortCheck("herald-ui", 8080));

        return list;
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    record CheckResult(String name, HealthCheck.Result result) {
    }
}
