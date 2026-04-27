package com.herald.doctor;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class PreflightTest {

    private static Map<String, String> validAssistantEnv(Path tempDir) {
        Map<String, String> env = new HashMap<>();
        env.put("ANTHROPIC_API_KEY", "sk-ant-test");
        env.put("HERALD_TELEGRAM_BOT_TOKEN", "1234:abc");
        env.put("HERALD_TELEGRAM_ALLOWED_CHAT_ID", "12345");
        env.put("HERALD_DB_PATH", tempDir.resolve("herald.db").toString());
        return env;
    }

    private static PrintStream sink(ByteArrayOutputStream out) {
        return new PrintStream(out, true, StandardCharsets.UTF_8);
    }

    @Test
    void doctorArgSkipsPreflight() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Preflight.Result r = Preflight.run(new String[]{"--doctor"}, Map.of(), sink(out));
        assertThat(r.mode()).isEqualTo(Preflight.Mode.DOCTOR);
        assertThat(r.fatalErrors()).isEmpty();
        assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void taskModeSkipsTelegramAndDbChecks(@TempDir Path tempDir) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Map<String, String> env = Map.of("ANTHROPIC_API_KEY", "sk-ant-test");
        Preflight.Result r = Preflight.run(
                new String[]{"--agents=" + tempDir.resolve("agent.md")}, env, sink(out));
        assertThat(r.mode()).isEqualTo(Preflight.Mode.TASK);
        assertThat(r.fatalErrors()).isEmpty();
    }

    @Test
    void taskModeStillRequiresApiKey(@TempDir Path tempDir) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Preflight.Result r = Preflight.run(
                new String[]{"--agents=" + tempDir.resolve("agent.md")}, Map.of(), sink(out));
        assertThat(r.fatalErrors()).hasSize(1);
        assertThat(r.fatalErrors().get(0).message()).contains("ANTHROPIC_API_KEY");
        String report = out.toString(StandardCharsets.UTF_8);
        assertThat(report)
                .contains("Herald can't start: ANTHROPIC_API_KEY is not set.")
                .contains("Fix: ")
                .contains("See: docs/getting-started-101.md");
    }

    @Test
    void assistantModeFailsOnMissingApiKey(@TempDir Path tempDir) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Map<String, String> env = validAssistantEnv(tempDir);
        env.remove("ANTHROPIC_API_KEY");
        Preflight.Result r = Preflight.run(new String[0], env, sink(out));
        assertThat(r.fatalErrors()).extracting(Preflight.Issue::message)
                .anyMatch(m -> m.contains("ANTHROPIC_API_KEY"));
    }

    @Test
    void assistantModeFailsOnMissingTelegramToken(@TempDir Path tempDir) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Map<String, String> env = validAssistantEnv(tempDir);
        env.remove("HERALD_TELEGRAM_BOT_TOKEN");
        Preflight.Result r = Preflight.run(new String[0], env, sink(out));
        assertThat(r.fatalErrors()).extracting(Preflight.Issue::message)
                .anyMatch(m -> m.contains("HERALD_TELEGRAM_BOT_TOKEN"));
    }

    @Test
    void assistantModeFailsOnMissingChatId(@TempDir Path tempDir) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Map<String, String> env = validAssistantEnv(tempDir);
        env.remove("HERALD_TELEGRAM_ALLOWED_CHAT_ID");
        Preflight.Result r = Preflight.run(new String[0], env, sink(out));
        assertThat(r.fatalErrors()).extracting(Preflight.Issue::message)
                .anyMatch(m -> m.contains("HERALD_TELEGRAM_ALLOWED_CHAT_ID"));
    }

    @Test
    void allFatalIssuesPrintTogetherBeforeExiting(@TempDir Path tempDir) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Wipe all required vars and use a fresh tempDir so db check still passes.
        Map<String, String> env = Map.of("HERALD_DB_PATH", tempDir.resolve("herald.db").toString());
        Preflight.Result r = Preflight.run(new String[0], env, sink(out));
        // Three errors expected: API key, bot token, chat id.
        assertThat(r.fatalErrors()).hasSize(3);
        // All printed in one go, not just the first.
        String report = out.toString(StandardCharsets.UTF_8);
        assertThat(report)
                .contains("ANTHROPIC_API_KEY")
                .contains("HERALD_TELEGRAM_BOT_TOKEN")
                .contains("HERALD_TELEGRAM_ALLOWED_CHAT_ID");
    }

    @Test
    void validAssistantConfigPasses(@TempDir Path tempDir) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Preflight.Result r = Preflight.run(new String[0], validAssistantEnv(tempDir), sink(out));
        assertThat(r.fatalErrors()).isEmpty();
        assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void detectsUnwritableDbDir(@TempDir Path tempDir) throws Exception {
        // Make the parent dir read-only so SQLite can't create WAL files.
        Path lockedDir = tempDir.resolve("locked");
        Files.createDirectories(lockedDir);
        boolean readOnlySet = lockedDir.toFile().setWritable(false);
        if (!readOnlySet) {
            // Some filesystems silently ignore chmod (e.g. macOS root-owned tmpfs);
            // skip if we can't enforce read-only.
            return;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Map<String, String> env = validAssistantEnv(tempDir);
            env.put("HERALD_DB_PATH", lockedDir.resolve("herald.db").toString());
            Preflight.Result r = Preflight.run(new String[0], env, sink(out));
            assertThat(r.fatalErrors()).extracting(Preflight.Issue::message)
                    .anyMatch(m -> m.contains("Database directory not writable"));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            lockedDir.toFile().setWritable(true);
        }
    }

    @Test
    void parsesAgentsArg() {
        assertThat(Preflight.getPrefixArg(new String[]{"--agents=foo.md"}, "--agents="))
                .isEqualTo("foo.md");
        assertThat(Preflight.hasPrefixArg(new String[]{"--agents=foo.md"}, "--agents="))
                .isTrue();
        assertThat(Preflight.hasArg(new String[]{"--doctor"}, "--doctor")).isTrue();
        assertThat(Preflight.hasArg(new String[]{"--quiet"}, "--doctor")).isFalse();
    }
}
