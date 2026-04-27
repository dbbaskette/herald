package com.herald.onboard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.herald.onboard.TelegramOnboardingClient.OnboardingException;

/**
 * Interactive setup wizard. Prompts for the few values that actually matter
 * (Anthropic API key, Telegram bot token, chat id, memories dir), validates
 * each as it goes, and writes them to {@code .env} via
 * {@link EnvFileWriter#merge}. See issue #306.
 *
 * <p>Design notes:</p>
 * <ul>
 *   <li>Standalone — no Spring boot. Keeps cold start under a second so
 *       {@code ./run.sh onboard} feels like a CLI.</li>
 *   <li>Idempotent — re-running on an existing install updates only what the
 *       user changes; comments and unrelated keys are preserved.</li>
 *   <li>Bails gracefully on Ctrl-C — {@link Files#write} is the only mutation
 *       and it's the very last step, so partial wizard runs leave nothing behind.</li>
 *   <li>Non-interactive friendly — the {@link Prompter} layer accepts
 *       {@code --answer=<key>:<value>} CLI flags so CI tests can drive it.</li>
 * </ul>
 */
public class Onboard {

    /** Hint keys that map {@code --answer=<key>:<value>} flags to specific prompts. */
    static final String KEY_ANTHROPIC = "anthropic";
    static final String KEY_BOT_TOKEN = "bot-token";
    static final String KEY_CHAT_ID = "chat-id";
    static final String KEY_MEMORIES = "memories-dir";

    public static void main(String[] args) {
        Map<String, String> presets = parseAnswers(args);
        Path envPath = Path.of(".env");
        Path overrideEnv = parseEnvPath(args);
        if (overrideEnv != null) envPath = overrideEnv;
        Prompter prompter = new ConsolePrompter(System.out, presets);
        Onboard wizard = new Onboard(prompter, new TelegramOnboardingClient(), envPath);
        int code = wizard.run();
        System.exit(code);
    }

    private final Prompter prompter;
    private final TelegramOnboardingClient telegram;
    private final Path envPath;
    private final long pollIntervalMs;

    public Onboard(Prompter prompter, TelegramOnboardingClient telegram, Path envPath) {
        this(prompter, telegram, envPath, 1500L);
    }

    /** Test-friendly constructor that lets tests collapse the 1.5s poll wait. */
    Onboard(Prompter prompter, TelegramOnboardingClient telegram, Path envPath, long pollIntervalMs) {
        this.prompter = prompter;
        this.telegram = telegram;
        this.envPath = envPath;
        this.pollIntervalMs = pollIntervalMs;
    }

    /** @return process exit code (0 success, 1 abort) */
    public int run() {
        prompter.println("▶ Herald Setup Wizard");
        prompter.println("Writes/updates: " + envPath.toAbsolutePath());
        prompter.println("Press Ctrl-C at any time — nothing is written until you finish.");
        prompter.println("");

        Map<String, String> updates = new LinkedHashMap<>();

        // Step 1 — Anthropic key.
        prompter.println("▸ Step 1/4 — Anthropic API key");
        prompter.println("  Get one at https://console.anthropic.com");
        String apiKey = prompter.promptSecret("  Paste it here, or press Enter to skip: (" + KEY_ANTHROPIC + ")").trim();
        if (!apiKey.isEmpty()) {
            if (!apiKey.startsWith("sk-")) {
                prompter.println("  ⚠  That doesn't look like an Anthropic key (should start with 'sk-'). Saving anyway.");
            }
            updates.put("ANTHROPIC_API_KEY", apiKey);
            prompter.println("  ✓ Saved.");
        } else {
            prompter.println("  Skipped — you'll need to set ANTHROPIC_API_KEY before Herald can talk to Claude.");
        }
        prompter.println("");

        // Step 2 — Telegram bot token (skippable for task-agent-only installs).
        prompter.println("▸ Step 2/4 — Telegram bot token (optional)");
        prompter.println("  Chat with @BotFather on Telegram, send /newbot, paste the token here.");
        prompter.println("  Skip with Enter to run as task-agent only (no Telegram).");
        String botToken = prompter.promptSecret("  Token: (" + KEY_BOT_TOKEN + ")").trim();
        String chatId = null;
        if (!botToken.isEmpty()) {
            try {
                String username = telegram.validateToken(botToken);
                if (username != null) {
                    prompter.println("  ✓ Token valid — bot is @" + username);
                } else {
                    prompter.println("  ✓ Token accepted by Telegram.");
                }
                updates.put("HERALD_TELEGRAM_BOT_TOKEN", botToken);

                // Step 3 — chat id auto-detect.
                prompter.println("");
                prompter.println("▸ Step 3/4 — Telegram chat ID");
                prompter.println("  On Telegram, open a chat with @" + (username == null ? "your-bot" : username) + " and send any message.");
                chatId = autoDetectChatId(botToken);
                if (chatId == null) {
                    String manualId = prompter.prompt("  Couldn't find one. Paste your chat ID manually, or Enter to skip: (" + KEY_CHAT_ID + ")").trim();
                    if (!manualId.isEmpty()) chatId = manualId;
                }
                if (chatId != null) {
                    updates.put("HERALD_TELEGRAM_ALLOWED_CHAT_ID", chatId);
                    prompter.println("  ✓ Chat ID saved: " + chatId);
                } else {
                    prompter.println("  Skipped — set HERALD_TELEGRAM_ALLOWED_CHAT_ID later or Herald rejects all messages.");
                }
            } catch (OnboardingException e) {
                prompter.println("  ✗ " + e.getMessage());
                prompter.println("  Skipping Telegram for now — you can re-run `./run.sh onboard` to retry.");
            }
        } else {
            prompter.println("  Skipped — task-agent mode (`./run.sh ... --agents=foo.md`) doesn't need this.");
        }
        prompter.println("");

        // Step 4 — memories dir (default vs custom path).
        prompter.println("▸ Step 4/4 — Memory directory");
        prompter.println("  Where should long-term memory live? (default: ~/.herald/memories)");
        prompter.println("  Tip: point this at an Obsidian vault folder to enable wikilink mode.");
        String memDir = prompter.prompt("  Path, or Enter to keep the default: (" + KEY_MEMORIES + ")").trim();
        if (!memDir.isEmpty()) {
            updates.put("HERALD_MEMORIES_DIR", memDir);
            prompter.println("  ✓ Saved.");
        }
        prompter.println("");

        if (updates.isEmpty()) {
            prompter.println("Nothing to save. Re-run when you're ready to enter your keys.");
            return 0;
        }

        try {
            Map<String, String> actions = EnvFileWriter.merge(envPath, updates);
            prompter.println("✓ Wrote " + envPath.toAbsolutePath());
            for (Map.Entry<String, String> e : actions.entrySet()) {
                prompter.println("  • " + e.getKey() + " " + e.getValue());
            }
        } catch (IOException e) {
            prompter.println("✗ Failed to write " + envPath + ": " + e.getMessage());
            return 1;
        }

        prompter.println("");
        prompter.println("Next:");
        prompter.println("  1. `./run.sh doctor` to verify the setup.");
        prompter.println("  2. `./run.sh` to start Herald.");
        prompter.println("  3. Send your bot a message on Telegram — you should get a reply from Claude.");
        return 0;
    }

    /**
     * Polls {@code getUpdates} a few times so the user has time to send a
     * message after the prompt. Returns {@code null} if nothing arrives
     * within the timeout — the wizard then offers manual entry as a fallback.
     */
    private String autoDetectChatId(String botToken) {
        prompter.waitForEnter("  Press Enter once you've sent the message...");
        for (int attempt = 1; attempt <= 6; attempt++) {
            try {
                Long id = telegram.pollChatId(botToken);
                if (id != null) {
                    return id.toString();
                }
            } catch (OnboardingException e) {
                prompter.println("  ⚠ " + e.getMessage());
                return null;
            }
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            prompter.println("  … still looking (attempt " + attempt + "/6)");
        }
        return null;
    }

    /** Parse {@code --answer=key:value} flags into a map. */
    static Map<String, String> parseAnswers(String[] args) {
        Map<String, String> result = new HashMap<>();
        for (String arg : args) {
            if (arg == null || !arg.startsWith("--answer=")) continue;
            String body = arg.substring("--answer=".length());
            int colon = body.indexOf(':');
            if (colon <= 0) continue;
            result.put(body.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    body.substring(colon + 1));
        }
        // A test escape hatch — `--answer=__skip_wait__:1` lets unit tests skip
        // the literal "press Enter" pause.
        if (hasFlag(args, "--no-wait")) {
            result.put("__skip_wait__", "1");
        }
        return result;
    }

    static Path parseEnvPath(String[] args) {
        for (String arg : args) {
            if (arg != null && arg.startsWith("--env=")) {
                return Path.of(arg.substring("--env=".length()));
            }
        }
        return null;
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String a : args) if (flag.equals(a)) return true;
        return false;
    }
}
