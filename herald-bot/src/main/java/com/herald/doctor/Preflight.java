package com.herald.doctor;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pre-Spring boot validation. Runs in {@link com.herald.HeraldApplication#main}
 * before {@code SpringApplication.run} so the common misconfigurations
 * (missing API key, JDK below 21, unwritable db-path) surface as one-line
 * actionable errors instead of 40-line {@code BeanCreationException} stack
 * traces. See issue #283.
 *
 * <p>Mode-aware:
 * <ul>
 *   <li>{@code --doctor} — preflight is skipped; the doctor command runs the
 *       same checks itself with richer output.</li>
 *   <li>{@code --agents=<path>} — task-agent mode; only the provider key + Java
 *       runtime are required. Telegram and DB checks are skipped.</li>
 *   <li>otherwise — personal-assistant mode; full check set.</li>
 * </ul>
 *
 * <p>Failed checks render as:
 * <pre>
 * Herald can't start: ANTHROPIC_API_KEY is not set.
 * Fix: `export ANTHROPIC_API_KEY=sk-ant-...` or add it to .env.
 * See: docs/getting-started-101.md#prerequisites
 * </pre>
 */
public final class Preflight {

    /**
     * Run preflight, printing friendly errors and calling {@link System#exit}
     * with code 1 if anything fatal is found. Returns normally on success.
     */
    public static void runOrExit(String[] args) {
        Result result = run(args, System.getenv(), System.err);
        if (!result.fatalErrors.isEmpty()) {
            System.exit(1);
        }
    }

    /**
     * Test-friendly variant — returns a {@link Result} instead of calling
     * {@code System.exit}. Lets unit tests assert on what was checked + reported.
     */
    public static Result run(String[] args, Map<String, String> env, PrintStream err) {
        // --doctor short-circuits the entire app — let it handle its own checks.
        if (hasArg(args, "--doctor")) {
            return new Result(Mode.DOCTOR, List.of());
        }
        Mode mode = hasPrefixArg(args, "--agents=") ? Mode.TASK : Mode.ASSISTANT;
        List<Issue> issues = new ArrayList<>();

        // Java version — universal.
        Runtime.Version v = Runtime.version();
        if (v.feature() < 21) {
            issues.add(new Issue(
                    "Java " + v.feature() + " is too old (need 21+).",
                    "Install JDK 21 — `brew install openjdk@21` on macOS, or "
                            + "use SDKMAN: `sdk install java 21-tem`.",
                    "docs/getting-started-101.md#prerequisites"));
        }

        // Provider key — at minimum Anthropic, since that's the default provider
        // and the one the bundled config templates assume. We don't validate
        // alternative-provider keys here (too many to enumerate); the AgentService
        // will surface a clean error if the user picked one without a key.
        if (isBlank(env.get("ANTHROPIC_API_KEY"))) {
            issues.add(new Issue(
                    "ANTHROPIC_API_KEY is not set.",
                    "`export ANTHROPIC_API_KEY=sk-ant-...` or add it to .env.",
                    "docs/getting-started-101.md#prerequisites"));
        }

        if (mode == Mode.ASSISTANT) {
            // Telegram is the default surface — without a token + chat id the bot
            // boots but ignores everything, which is confusing and looks like a hang.
            if (isBlank(env.get("HERALD_TELEGRAM_BOT_TOKEN"))) {
                issues.add(new Issue(
                        "HERALD_TELEGRAM_BOT_TOKEN is not set.",
                        "Create a bot via @BotFather and put the token in .env. "
                                + "For task-agent mode (no Telegram), pass `--agents=path.md` "
                                + "and Herald skips the Telegram requirement.",
                        "docs/getting-started-101.md#step-1--create-a-telegram-bot"));
            }
            if (isBlank(env.get("HERALD_TELEGRAM_ALLOWED_CHAT_ID"))) {
                issues.add(new Issue(
                        "HERALD_TELEGRAM_ALLOWED_CHAT_ID is not set.",
                        "Without it, Herald rejects every message. Find your chat id at "
                                + "https://api.telegram.org/bot<TOKEN>/getUpdates after sending "
                                + "your bot a message.",
                        "docs/getting-started-101.md#step-1--create-a-telegram-bot"));
            }

            // db-path parent must be writable so SQLite can create the WAL files.
            Path dbPath = resolveDbPath(env);
            Path parent = dbPath.getParent();
            if (parent != null) {
                try {
                    Files.createDirectories(parent);
                } catch (Exception ex) {
                    issues.add(new Issue(
                            "Database directory not writable: " + parent + " (" + ex.getMessage() + ").",
                            "`chmod u+w " + parent + "`, or override the location via "
                                    + "`HERALD_DB_PATH=/some/writable/path/herald.db`.",
                            "README.md#environment-variables"));
                }
                if (Files.exists(parent) && !Files.isWritable(parent)) {
                    issues.add(new Issue(
                            "Database directory not writable: " + parent + ".",
                            "`chmod u+w " + parent + "`, or override the location via "
                                    + "`HERALD_DB_PATH=/some/writable/path/herald.db`.",
                            "README.md#environment-variables"));
                }
            }
        }

        for (Issue issue : issues) {
            err.println("Herald can't start: " + issue.message);
            err.println("Fix: " + issue.fixHint);
            err.println("See: " + issue.docsLink);
            err.println();
        }
        return new Result(mode, issues);
    }

    /** @return value of {@code --foo=bar} as {@code "bar"}, or null if not present. */
    static String getPrefixArg(String[] args, String prefix) {
        for (String arg : args) {
            if (arg != null && arg.startsWith(prefix)) {
                return arg.substring(prefix.length());
            }
        }
        return null;
    }

    static boolean hasPrefixArg(String[] args, String prefix) {
        return getPrefixArg(args, prefix) != null;
    }

    static boolean hasArg(String[] args, String exact) {
        for (String arg : args) {
            if (exact.equals(arg)) return true;
        }
        return false;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static Path resolveDbPath(Map<String, String> env) {
        String raw = env.getOrDefault("HERALD_DB_PATH", "~/.herald/herald.db");
        if (raw.startsWith("~/")) {
            raw = System.getProperty("user.home") + raw.substring(1);
        }
        return Path.of(raw);
    }

    public enum Mode { ASSISTANT, TASK, DOCTOR }

    public record Issue(String message, String fixHint, String docsLink) {
    }

    public record Result(Mode mode, List<Issue> fatalErrors) {
        public boolean ok() {
            return fatalErrors.isEmpty();
        }
    }

    private Preflight() {
    }
}
