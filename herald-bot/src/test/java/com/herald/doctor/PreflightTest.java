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
    void assistantModeAllowsMissingTelegramToken(@TempDir Path tempDir) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Map<String, String> env = validAssistantEnv(tempDir);
        env.remove("HERALD_TELEGRAM_BOT_TOKEN");
        Preflight.Result r = Preflight.run(new String[0], env, sink(out));
        assertThat(r.fatalErrors()).extracting(Preflight.Issue::message)
                .noneMatch(m -> m.contains("HERALD_TELEGRAM_BOT_TOKEN"));
    }

    @Test
    void assistantModeAllowsMissingChatId(@TempDir Path tempDir) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Map<String, String> env = validAssistantEnv(tempDir);
        env.remove("HERALD_TELEGRAM_ALLOWED_CHAT_ID");
        Preflight.Result r = Preflight.run(new String[0], env, sink(out));
        assertThat(r.fatalErrors()).extracting(Preflight.Issue::message)
                .noneMatch(m -> m.contains("HERALD_TELEGRAM_ALLOWED_CHAT_ID"));
    }

    @Test
    void allFatalIssuesPrintTogetherBeforeExiting(@TempDir Path tempDir) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Wipe all required vars and use a fresh tempDir so db check still passes.
        Map<String, String> env = Map.of("HERALD_DB_PATH", tempDir.resolve("herald.db").toString());
        Preflight.Result r = Preflight.run(new String[0], env, sink(out));
        // Telegram is optional; only the missing model provider is fatal.
        assertThat(r.fatalErrors()).hasSize(1);
        // All printed in one go, not just the first.
        String report = out.toString(StandardCharsets.UTF_8);
        assertThat(report)
                .contains("ANTHROPIC_API_KEY")
                .doesNotContain("HERALD_TELEGRAM_BOT_TOKEN", "HERALD_TELEGRAM_ALLOWED_CHAT_ID");
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"openai,OPENAI_API_KEY,fixture", "gemini,GEMINI_API_KEY,fixture",
            "ollama,OLLAMA_BASE_URL,http://localhost:11434", "lmstudio,LMSTUDIO_BASE_URL,http://localhost:1234"})
    void alternativeProviderOnlyStartsWithoutTelegram(String provider, String key, String value, @TempDir Path dir) {
        var env = Map.of("HERALD_DEFAULT_PROVIDER", provider, key, value,
                "HERALD_DB_PATH", dir.resolve("herald.db").toString());
        assertThat(Preflight.run(new String[0], env, sink(new ByteArrayOutputStream())).ok()).isTrue();
        assertThat(Preflight.run(new String[]{"--agents=fixture.md"}, env, sink(new ByteArrayOutputStream())).ok()).isTrue();
    }

    @Test void configuredFallbackPassesAndDoctorMatches(@TempDir Path dir) {
        var env = Map.of("HERALD_DEFAULT_PROVIDER", "anthropic", "OPENAI_API_KEY", "fixture",
                "HERALD_DB_PATH", dir.resolve("herald.db").toString());
        assertThat(Preflight.run(new String[0], env, sink(new ByteArrayOutputStream())).ok()).isTrue();
        var check = new com.herald.doctor.checks.ProviderCapabilityCheck(DiagnosticEnvironment.load(new String[0], env));
        assertThat(check.run().message()).contains("openai", "fallback from anthropic");
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
